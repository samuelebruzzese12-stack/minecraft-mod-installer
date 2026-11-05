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
  });"""
    )

    /** Il file `scripts/main.js` con i soli componenti usati dall'add-on (null se non ne usa nessuno). */
    fun mainJs(used: Set<String>): String? {
        val parts = JS.filterKeys { it in used }.values
        if (parts.isEmpty()) return null
        return buildString {
            append("// Scritto da Minecraft Mod Installer (JAVA TO BEDROCK): comportamenti delle mod Java ricostruiti con la Script API.\n")
            append("import { system, GameMode, EquipmentSlot } from \"@minecraft/server\";\n")
            append("function warn(err) { console.warn(\"[mmi] \" + err); }\n")
            append("system.beforeEvents.startup.subscribe((ev) => {")
            for (p in parts) append(p).append('\n')
            append("});\n")
        }
    }
}
