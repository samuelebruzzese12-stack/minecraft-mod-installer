package com.mmi.modinstaller.convert

/**
 * Comportamenti Java ricostruiti con la Script API di Bedrock: ogni comportamento è un «componente personalizzato» che il
 * file dell'oggetto o del blocco dichiara (con i suoi parametri) e che `scripts/main.js` registra all'avvio del mondo.
 *
 * Tutto quello che c'è qui è stato provato nel server Bedrock ufficiale (BDS 1.26.62) con un giocatore simulato:
 *  * gli attrezzi personalizzati NON si consumano da soli scavando (si consumano solo colpendo, di 2): serve [TOOL_WEAR];
 *  * gli effetti dei cibi arrivano al giocatore con durata e livello giusti ([FOOD_EFFECTS]);
 *  * i minerali lasciano le sfere di esperienza ([XP_ON_BREAK]).
 * I parametri dei componenti non possono contenere liste di oggetti (Bedrock: «Exceeded the object and array depth limit»):
 * per questo gli effetti viaggiano in una stringa.
 */
internal object Behaviors {
    /** Versione della Script API usata (stabile, nel gioco dalla 1.21.90). */
    const val SCRIPT_API = "2.0.0"
    /** I file con componenti personalizzati con parametri devono avere questo formato (o più nuovo). */
    const val FORMAT = "1.21.90"
    val MIN_ENGINE = listOf(1L, 21L, 90L)

    const val FOOD_EFFECTS = "mmi:food_effects"
    const val XP_ON_BREAK = "mmi:xp_on_break"
    const val TOOL_WEAR = "mmi:tool_wear"
    const val OPENABLE = "mmi:openable"
    const val SLAB = "mmi:slab"
    const val BUTTON = "mmi:button"
    const val PLATE = "mmi:pressure_plate"
    const val CROP = "mmi:crop"

    /** Effetti di Minecraft Bedrock (dai dati ufficiali di Mojang, mojang-effects.json). */
    private val BEDROCK_EFFECTS = setOf(
        "absorption", "bad_omen", "blindness", "breath_of_the_nautilus", "conduit_power", "darkness", "fatal_poison", "fire_resistance",
        "freezing", "haste", "health_boost", "hunger", "infested", "instant_damage", "instant_health", "invisibility", "jump_boost",
        "levitation", "mining_fatigue", "nausea", "night_vision", "oozing", "poison", "raid_omen", "regeneration", "resistance",
        "saturation", "slow_falling", "slowness", "speed", "strength", "trial_omen", "village_hero", "water_breathing", "weakness",
        "weaving", "wind_charged", "wither"
    )
    private val RENAMED_EFFECTS = mapOf("hero_of_the_village" to "village_hero")

    /** L'effetto Bedrock per un effetto Java (`minecraft:regeneration`), o null se in Bedrock non c'è (es. quelli delle mod). */
    fun bedrockEffect(java: String): String? {
        if (!java.startsWith("minecraft:")) return null
        val name = java.removePrefix("minecraft:").let { RENAMED_EFFECTS[it] ?: it }
        return name.takeIf { it in BEDROCK_EFFECTS }
    }

    class Effect(val id: String, val ticks: Int, val amplifier: Int, val chance: Double)

    /** Gli effetti di un cibo come parametro del componente: «nome,durata in tick,livello,probabilità;…». */
    fun effectsParam(effects: List<Effect>): String =
        effects.joinToString(";") { e -> e.id + "," + e.ticks + "," + e.amplifier + "," + num(e.chance) }

    private fun num(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

    private val JS = mapOf(
        FOOD_EFFECTS to """
  // Effetti del cibo (FoodProperties.effect in Java): a fine pasto, ognuno con la sua probabilità.
  ev.itemComponentRegistry.registerCustomComponent("mmi:food_effects", {
    onConsume(e, p) {
      for (const s of String((p.params && p.params.effects) || "").split(";")) {
        const [id, dur, amp, ch] = s.split(",");
        if (!id || (+ch < 1 && Math.random() >= +ch)) continue;
        try { e.source.addEffect(id, Math.max(1, +dur | 0), { amplifier: +amp | 0 }); } catch (err) { warn(err); }
      }
    }
  });""",
        XP_ON_BREAK to """
  // Esperienza dei minerali (DropExperienceBlock in Java): non in creativa e non con il tocco di velluto.
  ev.blockComponentRegistry.registerCustomComponent("mmi:xp_on_break", {
    onPlayerBreak(e, p) {
      const pl = e.player;
      if (!pl || pl.getGameMode() === GameMode.Creative) return;
      try {
        const tool = pl.getComponent("minecraft:equippable")?.getEquipment(EquipmentSlot.Mainhand);
        if (tool?.getComponent("minecraft:enchantable")?.hasEnchantment("silk_touch")) return;
      } catch (err) { warn(err); }
      const min = (p.params && p.params.min) | 0, max = Math.max(min, (p.params && p.params.max) | 0);
      let n = min + Math.floor(Math.random() * (max - min + 1));
      const c = e.block.center();
      while (n-- > 0) e.dimension.spawnEntity("minecraft:xp_orb", c);
    }
  });""",
        TOOL_WEAR to """
  // Usura degli attrezzi: Bedrock non consuma da sé gli attrezzi delle add-on quando scavano (provato nel server).
  // Come in Java: «mine» punti a blocco scavato, «hit» a colpo; Indistruttibilità li evita con probabilità livello/(livello+1).
  ev.itemComponentRegistry.registerCustomComponent("mmi:tool_wear", {
    onMineBlock(e, p) {
      const pl = e.source;
      if (!pl || (pl.getGameMode && pl.getGameMode() === GameMode.Creative)) return;
      const slot = pl.getComponent("minecraft:equippable")?.getEquipmentSlot(EquipmentSlot.Mainhand);
      const it = slot && slot.getItem();
      const d = it && it.getComponent("minecraft:durability");
      if (!d) return;
      const unb = it.getComponent("minecraft:enchantable")?.getEnchantment("unbreaking")?.level ?? 0;
      if (unb > 0 && Math.random() >= 1 / (unb + 1)) return;
      const amount = (p.params && p.params.mine) ?? 1;
      if (d.damage + amount >= d.maxDurability) {
        slot.setItem(undefined);
        try { pl.dimension.playSound("random.break", pl.location); } catch (err) { warn(err); }
        return;
      }
      d.damage += amount;
      slot.setItem(it);
    },
    onBeforeDurabilityDamage(e, p) {
      if (p.params && p.params.hit != null) e.durabilityDamage = p.params.hit;
    }
  });""",
        OPENABLE to """
  // Porte, botole e cancelli (BLOCKBRIDGE): si aprono con la mano (non quelli di ferro) e con la pietrarossa, come in Java.
  const OPEN_SOUNDS = {
    door: ["random.door_open", "random.door_close"], iron_door: ["open.iron_door", "close.iron_door"],
    trapdoor: ["random.door_open", "random.door_close"], iron_trapdoor: ["open.iron_trapdoor", "close.iron_trapdoor"],
    fence_gate: ["random.door_open", "random.door_close"]
  };
  const powered = new Map();
  function setOpen(b, open, p) {
    const perm = b.permutation;
    if (perm.getState("mmi:open") === open) return;
    b.setPermutation(perm.withState("mmi:open", open));
    if (p.pair) {
      const other = perm.getState("minecraft:multi_block_part") === 1 ? b.below() : b.above();
      if (other && other.typeId === b.typeId) other.setPermutation(other.permutation.withState("mmi:open", open));
    }
    const s = OPEN_SOUNDS[p.sound] || OPEN_SOUNDS.door;
    try { b.dimension.playSound(open ? s[0] : s[1], b.center()); } catch (err) { warn(err); }
  }
  ev.blockComponentRegistry.registerCustomComponent("mmi:openable", {
    onPlayerInteract(e, { params: p }) {
      if (p.hand === false) return;
      const b = e.block;
      const open = !b.permutation.getState("mmi:open");
      if (p.gate && open && e.player) {
        // Java: il cancello si apre verso il lato opposto al giocatore.
        const v = e.player.getViewDirection();
        const dir = Math.abs(v.x) > Math.abs(v.z) ? (v.x > 0 ? "east" : "west") : (v.z > 0 ? "south" : "north");
        const opp = { north: "south", south: "north", east: "west", west: "east" };
        if (b.permutation.getState("minecraft:cardinal_direction") === opp[dir]) b.setPermutation(b.permutation.withState("minecraft:cardinal_direction", dir));
      }
      setOpen(b, open, p);
    },
    onRedstoneUpdate(e, { params: p }) {
      const b = e.block, key = b.dimension.id + b.location.x + "," + b.location.y + "," + b.location.z;
      const on = e.powerLevel > 0;
      if ((powered.get(key) ?? false) === on) return;
      powered.set(key, on);
      setOpen(b, on, p);
    }
  });""",
        SLAB to """
  // Lastre (BLOCKBRIDGE): una lastra sopra una lastra uguale diventa una lastra doppia, come in Java.
  ev.blockComponentRegistry.registerCustomComponent("mmi:slab", {
    beforeOnPlayerPlace(e) {
      const id = e.permutationToPlace.type.id;
      const target = e.face === Direction.Up ? e.block.below() : e.face === Direction.Down ? e.block.above() : undefined;
      if (!target || target.typeId !== id) return;
      const t = target.permutation;
      if (t.getState("mmi:double")) return;
      const half = t.getState("minecraft:vertical_half");
      if ((e.face === Direction.Up && half !== "bottom") || (e.face === Direction.Down && half !== "top")) return;
      e.cancel = true;
      const pl = e.player;
      system.run(() => {
        try {
          target.setPermutation(t.withState("mmi:double", true));
          if (pl && pl.getGameMode() !== GameMode.Creative) takeOne(pl, id);
          target.dimension.playSound("use.stone", target.center());
        } catch (err) { warn(err); }
      });
    }
  });""",
        BUTTON to """
  // Pulsanti (BLOCKBRIDGE): danno il segnale di pietrarossa per 1 secondo (pietra) o 1,5 secondi (legno), come in Java.
  ev.blockComponentRegistry.registerCustomComponent("mmi:button", {
    onPlayerInteract(e, { params: p }) {
      const b = e.block;
      if (b.permutation.getState("mmi:powered")) return;
      b.setPermutation(b.permutation.withState("mmi:powered", true));
      try { b.dimension.playSound("random.click", b.center()); } catch (err) { warn(err); }
      const loc = b.location, dim = b.dimension, id = b.typeId;
      system.runTimeout(() => {
        try { const x = dim.getBlock(loc); if (x && x.typeId === id) x.setPermutation(x.permutation.withState("mmi:powered", false)); } catch (err) { warn(err); }
      }, p.ticks || 20);
    }
  });""",
        PLATE to """
  // Pedane (BLOCKBRIDGE): ogni mezzo secondo guardano se c'è qualcuno sopra (come Java) e danno il segnale di pietrarossa.
  ev.blockComponentRegistry.registerCustomComponent("mmi:pressure_plate", {
    onTick(e) {
      const b = e.block, l = b.location;
      let on = false;
      try {
        for (const en of b.dimension.getEntities({ location: b.center(), maxDistance: 1.5 })) {
          const q = en.location;
          if (q.x >= l.x && q.x < l.x + 1 && q.z >= l.z && q.z < l.z + 1 && q.y >= l.y - 0.01 && q.y < l.y + 0.6) { on = true; break; }
        }
      } catch (err) { warn(err); }
      if (b.permutation.getState("mmi:powered") === on) return;
      b.setPermutation(b.permutation.withState("mmi:powered", on));
      try { b.dimension.playSound(on ? "random.click" : "random.click", b.center(), { pitch: on ? 0.6 : 0.5 }); } catch (err) { warn(err); }
    }
  });""",
        CROP to """
  // Colture (BLOCKBRIDGE): crescono da sole (più in fretta sulla terra bagnata) e con la farina d'ossa, come in Java.
  ev.blockComponentRegistry.registerCustomComponent("mmi:crop", {
    onRandomTick(e, { params: p }) {
      const b = e.block, age = b.permutation.getState("mmi:age");
      if (age >= p.max) return;
      const below = b.below();
      const wet = below && below.typeId === "minecraft:farmland" && (below.permutation.getState("moisturized_amount") ?? 0) > 0;
      if (Math.random() < (wet ? 1 / 7 : 1 / 13)) b.setPermutation(b.permutation.withState("mmi:age", age + 1));
    },
    onPlayerInteract(e, { params: p }) {
      const pl = e.player;
      const it = pl && pl.getComponent("minecraft:equippable")?.getEquipment(EquipmentSlot.Mainhand);
      if (!it || it.typeId !== "minecraft:bone_meal") return;
      const b = e.block, age = b.permutation.getState("mmi:age");
      if (age >= p.max) return;
      b.setPermutation(b.permutation.withState("mmi:age", Math.min(p.max, age + 2 + Math.floor(Math.random() * 4))));
      if (pl.getGameMode() !== GameMode.Creative) takeOne(pl, "minecraft:bone_meal");
      try { b.dimension.spawnParticle("minecraft:crop_growth_emitter", b.center()); } catch (err) { warn(err); }
    }
  });"""
    )

    /** Il file `scripts/main.js` con i soli componenti usati dall'add-on (null se non ne usa nessuno). */
    fun mainJs(used: Set<String>): String? {
        val parts = JS.filterKeys { it in used }.values
        if (parts.isEmpty()) return null
        return buildString {
            append("// Scritto da Minecraft Mod Installer (JAVA TO BEDROCK): comportamenti delle mod Java ricostruiti con la Script API.\n")
            append("import { system, GameMode, EquipmentSlot, Direction } from \"@minecraft/server\";\n")
            append("function warn(err) { console.warn(\"[mmi] \" + err); }\n")
            append("function takeOne(pl, id) {\n")
            append("  const slot = pl.getComponent(\"minecraft:equippable\")?.getEquipmentSlot(EquipmentSlot.Mainhand);\n")
            append("  const it = slot && slot.getItem();\n")
            append("  if (!it || it.typeId !== id) return;\n")
            append("  if (it.amount > 1) { it.amount -= 1; slot.setItem(it); } else slot.setItem(undefined);\n")
            append("}\n")
            append("system.beforeEvents.startup.subscribe((ev) => {")
            for (p in parts) append(p).append('\n')
            append("});\n")
        }
    }
}
