package com.mmi.modinstaller.convert

import com.mmi.modinstaller.core.Json
import com.mmi.modinstaller.core.asJsonList
import com.mmi.modinstaller.core.asJsonObject

/**
 * Le armi da fuoco di TACZ (Timeless and Classics Zero) e delle mod che ne aggiungono altre. In TACZ le armi non sono
 * registrate nel codice una per una: stanno nei «pacchetti di armi» (cartelle con `gunpack.meta.json`, dentro la mod o in
 * un archivio a parte), descritte da file JSON (dati, aspetto, ricette) con modelli e animazioni già nel formato di Bedrock.
 * Qui si leggono quei file (senza eseguire nulla) e se ne fa un oggetto Bedrock per arma e per munizione, con il modello
 * 3D in mano, i suoni e il comportamento (sparo, caricatore, ricarica, banchi della mod) ricostruito con la Script API.
 */
internal class TaczGun(
    val id: String,
    val nameKey: String,
    val type: String,
    val sort: Int,
    /** La voce del JAR con l'icona (la «slot» di TACZ) e il percorso della texture nel pacchetto di risorse. */
    val iconEntry: String,
    val iconPath: String,
    val modelEntry: String?,
    val textureEntry: String?,
    val texturePath: String,
    val animEntries: List<String>,
    val thirdPersonScale: Double,
    /** Ossi da nascondere (caricatori estesi, accessori non montati, segnaposto delle mani). */
    val hidden: List<String>,
    /** Gli accessori di serie montati (gli altri figli del supporto degli accessori restano nascosti). */
    val builtinShown: Set<String>,
    /** I dati per lo script (danni, ritmo, caricatore, ricarica, suoni…). */
    val runtime: Map<String, Any?>,
    val melee: Double,
    val rpm: Int,
    val auto: Boolean
)

internal class TaczAmmo(val id: String, val javaId: String, val nameKey: String, val iconEntry: String?, val iconPath: String, val stack: Int)

internal class TaczBlock(
    val id: String,
    val nameKey: String,
    val modelEntry: String?,
    val textureEntry: String?,
    val texturePath: String,
    val tabs: List<Pair<String, String>>
)

internal class TaczRecipe(val tab: String, val result: String, val count: Int, val nameKey: String, val icon: String?, val materials: List<Map<String, Any?>>)

internal class TaczContent(
    val mod: JavaModContent,
    val packs: List<String>,
    val guns: List<TaczGun>,
    val ammo: List<TaczAmmo>,
    val blocks: List<TaczBlock>,
    val recipes: List<TaczRecipe>,
    /** Lingua Bedrock → chiave → testo. */
    val lang: Map<String, Map<String, String>>,
    /** Suono Bedrock → voce .ogg nel JAR. */
    val sounds: Map<String, String>,
    val recipeSkips: Map<String, Int>,
    val attachments: Int,
    val skippedGuns: List<String>,
    /** Le cartelle dei pacchetti di armi nel JAR. */
    val roots: List<String> = emptyList(),
    /** Ricette del banco da lavoro per i banchi di TACZ (percorso → documento Bedrock). */
    val crafting: List<Pair<String, Map<String, Any?>>> = emptyList(),
    val craftingNotes: List<String> = emptyList()
) {
    val ids: Set<String> get() = (guns.map { it.id } + ammo.map { it.id } + blocks.map { it.id }).toSet()
}

internal object TaczReader {

    /** Le voci di TACZ che nel codice rappresentano «un'arma qualsiasi»: in Bedrock le sostituiscono le armi dei pacchetti. */
    val GENERIC = setOf(
        "modern_kinetic_gun", "ammo", "attachment", "gun_smith_table", "workbench_a", "workbench_b", "workbench_c",
        "statue", "target", "target_minecart", "creative_ammo_box", "bullet", "gun_smith_table_b"
    )

    private const val MAX_SOUND_BYTES = 40L * 1024 * 1024

    /** Le parti che TACZ nasconde quando sull'arma non c'è nessun accessorio (DisplayGunModelInstance e BedrockGunModel). */
    private val TACZ_HIDDEN = listOf(
        "lefthand_pos", "righthand_pos", "additional_magazine", "muzzle_flash", "shell", "mag_extended_1", "mag_extended_2", "mag_extended_3",
        "sight_folded", "mount", "handguard_tactical", "scope_pos", "muzzle_pos", "stock_pos", "grip_pos", "laser_pos", "extended_mag_pos"
    )

    fun read(mod: JavaModContent, limits: ConvertLimits, resolver: ItemResolver): TaczContent? {
        val jar = mod.jar
        val roots = jar.allNames().filter { it.endsWith("gunpack.meta.json") }.map { it.removeSuffix("gunpack.meta.json") }.distinct().toList()
        if (roots.isEmpty()) return null
        val guns = ArrayList<TaczGun>()
        val ammo = ArrayList<TaczAmmo>()
        val blocks = ArrayList<TaczBlock>()
        val recipes = ArrayList<TaczRecipe>()
        val lang = LinkedHashMap<String, MutableMap<String, String>>()
        val sounds = LinkedHashMap<String, String>()
        val skips = LinkedHashMap<String, Int>()
        val skipped = ArrayList<String>()
        var soundBytes = 0L
        var attachments = 0
        val all = jar.allNames().toList()
        fun obj(name: String): Map<String, Any?>? = jar.json(name, limits.maxJsonBytes).asJsonObject()

        val ammoIds = HashMap<String, String>()
        // Due giri: prima le munizioni di tutti i pacchetti (le armi di un pacchetto aggiuntivo usano spesso quelle di base).
        for (pass in 0..1) for (root in roots) {
            val indexRe = Regex("^" + Regex.escape(root) + "data/([a-z0-9_.-]+)/index/(guns|ammo|blocks|attachments)/(.+)\\.json$")
            val index = all.mapNotNull { n -> indexRe.find(n)?.let { m -> Triple(m.groupValues[2], Rl(m.groupValues[1], m.groupValues[3]), n) } }
            // ---- testi: tutte le lingue del pacchetto
            if (pass == 1) for (n in all) {
                val m = Regex("^" + Regex.escape(root) + "assets/[a-z0-9_.-]+/lang/([a-z_A-Z-]+)\\.json$").find(n) ?: continue
                val code = ModConverter.LANGUAGES[m.groupValues[1].lowercase().replace('-', '_')] ?: continue
                val map = obj(n) ?: continue
                val dest = lang.getOrPut(code) { LinkedHashMap() }
                for ((k, v) in map) if (v is String) dest[k] = v
            }
            fun asset(rl: Rl?, folder: String, ext: String): String? {
                if (rl == null) return null
                val name = root + "assets/${rl.ns}/$folder/${rl.path}.$ext"
                return if (jar.has(name)) name else null
            }
            fun data(rl: Rl?, folder: String): String? {
                if (rl == null) return null
                val name = root + "data/${rl.ns}/$folder/${rl.path}.json"
                return if (jar.has(name)) name else null
            }
            fun soundOf(text: String?): String? {
                val rl = Rl.parse(text, "tacz") ?: return null
                val entry = asset(rl, "tacz_sounds", "ogg") ?: return null
                val id = (rl.ns + "." + rl.path).replace('/', '.').lowercase()
                if (id !in sounds) {
                    val size = jar.size(entry) ?: 0L
                    if (soundBytes + size > MAX_SOUND_BYTES) return null
                    soundBytes += size
                    sounds[id] = entry
                }
                return id
            }

            // ---- munizioni
            if (pass == 0) {
            for ((kind, rl, entry) in index) if (kind == "ammo") {
                val idx = obj(entry) ?: continue
                val disp = displayOf(jar, root, idx["display"] as? String, "ammo", limits)
                val slot = Rl.parse(disp?.get("slot") as? String, rl.ns)
                val icon = asset(slot, "textures", "png")
                val bid = rl.ns + ":ammo_" + clean(rl.path)
                ammoIds[rl.toString()] = bid
                ammo.add(
                    TaczAmmo(
                        bid, rl.toString(), idx["name"] as? String ?: "${rl.ns}.ammo.${rl.path}.name", icon,
                        "textures/mmi/${rl.ns}/" + (slot?.path ?: "ammo/${rl.path}"), ((idx["stack_size"] as? Number)?.toInt() ?: 64).coerceIn(1, 64)
                    )
                )
            }
            attachments += index.count { it.first == "attachments" }
            continue
            }

            // ---- armi
            for ((kind, rl, entry) in index) if (kind == "guns") {
                val idx = obj(entry) ?: continue
                val itemType = idx["item_type"] as? String ?: "modern_kinetic"
                val disp = displayOf(jar, root, idx["display"] as? String, "guns", limits)
                val dat = Rl.parse(idx["data"] as? String, rl.ns)?.let { obj(root + "data/${it.ns}/data/guns/${it.path}.json") }
                if (disp == null || dat == null) {
                    skipped.add(rl.toString() + if (disp == null) " (aspetto mancante)" else " (dati mancanti)")
                    continue
                }
                if (itemType != "modern_kinetic") {
                    skipped.add("$rl (arma di tipo «$itemType»)")
                    continue
                }
                val slot = Rl.parse(disp["slot"] as? String, rl.ns)
                val icon = asset(slot, "textures", "png")
                if (icon == null) {
                    skipped.add("$rl (icona mancante)")
                    continue
                }
                val model = asset(Rl.parse(disp["model"] as? String, rl.ns), "geo_models", "json")
                val texRl = Rl.parse(disp["texture"] as? String, rl.ns)
                val texture = asset(texRl, "textures", "png")
                val animRl = Rl.parse(disp["animation"] as? String, rl.ns)
                val anims = ArrayList<String>()
                asset(animRl, "animations", "animation.json")?.let { anims.add(it) }
                (disp["use_default_animation"] as? String)?.let { def ->
                    // Le animazioni di base di TACZ stanno nella mod (fuori dal pacchetto).
                    val base = "assets/tacz/animations/${def}_default.animation.json"
                    if (jar.has(base)) anims.add(base)
                }
                val bid = rl.ns + ":" + clean(rl.path).let { if (it.first().isDigit()) "gun_$it" else it }
                val bullet = (dat["bullet"] as? Map<*, *>).asJsonObject() ?: emptyMap()
                val extra = (bullet["extra_damage"] as? Map<*, *>).asJsonObject() ?: emptyMap()
                val reload = (dat["reload"] as? Map<*, *>).asJsonObject() ?: emptyMap()
                val feed = (reload["feed"] as? Map<*, *>).asJsonObject() ?: emptyMap()
                val cooldown = (reload["cooldown"] as? Map<*, *>).asJsonObject() ?: emptyMap()
                val inacc = (dat["inaccuracy"] as? Map<*, *>).asJsonObject() ?: emptyMap()
                val expl = (bullet["explosion"] as? Map<*, *>).asJsonObject()?.takeIf { it["explode"] == true }
                val ign = bullet["ignite"]
                val igniteEntity = ign == true || ((ign as? Map<*, *>)?.get("entity") == true)
                val reloadType = reload["type"] as? String ?: "magazine"
                val modes = (dat["fire_mode"] as? List<*>).orEmpty().filterIsInstance<String>().filter { it in setOf("auto", "semi", "burst") }.ifEmpty { listOf("semi") }
                val burst = (dat["burst_data"] as? Map<*, *>).asJsonObject()
                val rpm = num(dat["rpm"], 300.0).toInt().coerceIn(1, 1200)
                val life = num(bullet["life"], 1.0)
                val speed = num(bullet["speed"], 300.0) * 2.0
                val falloff = (extra["damage_adjust"] as? List<*>).orEmpty().mapNotNull { a ->
                    val m = (a as? Map<*, *>).asJsonObject() ?: return@mapNotNull null
                    val d = m["distance"]
                    val dist = if (d is Number) d.toDouble() else 100000.0
                    listOf(dist, num(m["damage"], num(bullet["damage"], 5.0)))
                }
                val sndMap = (disp["sounds"] as? Map<*, *>).asJsonObject() ?: emptyMap()
                fun snd(key: String) = soundOf(sndMap[key] as? String)
                val animDoc = anims.firstOrNull()?.let { jar.json(it, limits.maxJsonBytes).asJsonObject() }
                fun keyed(anim: String): List<List<Any?>>? {
                    for (a in anims) {
                        val doc = if (a == anims.first()) animDoc else jar.json(a, limits.maxJsonBytes).asJsonObject()
                        val clip = (doc?.get("animations") as? Map<*, *>)?.get(anim) as? Map<*, *> ?: continue
                        val fx = (clip["sound_effects"] as? Map<*, *>) ?: return emptyList()
                        val out = ArrayList<List<Any?>>()
                        for ((t, v) in fx) {
                            val time = (t as? String)?.toDoubleOrNull() ?: continue
                            val effects = if (v is List<*>) v else listOf(v)
                            for (e in effects) {
                                val id = soundOf(((e as? Map<*, *>)?.get("effect") as? String)) ?: continue
                                out.add(listOf(Math.round(time * 1000.0) / 1000.0, id))
                            }
                        }
                        return out.sortedBy { (it[0] as Double) }
                    }
                    return null
                }
                val shootSound = snd("shoot") ?: snd("shoot_3p")
                val runtime = linkedMapOf<String, Any?>(
                    "name" to (idx["name"] as? String ?: "${rl.ns}.gun.${rl.path}.name"),
                    "ammo" to ammoIds[(dat["ammo"] as? String)?.let { Rl.parse(it, rl.ns)?.toString() }],
                    "mag" to ((dat["ammo_amount"] as? Number)?.toInt() ?: 1).coerceAtLeast(1).toLong(),
                    "inv" to (reloadType == "inventory"),
                    "fuel" to (reloadType == "fuel"),
                    "rpm" to rpm.toLong(),
                    "modes" to modes,
                    "burst" to burst?.let { b ->
                        linkedMapOf("count" to num(b["count"], 3.0).toLong(), "bpm" to num(b["bpm"], rpm.toDouble()), "interval" to num(b["min_interval"], 0.3))
                    },
                    "bolt" to (if (dat["bolt"] == "manual_action") num(dat["bolt_action_time"], 0.0) else 0.0),
                    "pellets" to num(bullet["bullet_amount"], 1.0).toLong().coerceIn(1, 32),
                    "dmg" to num(bullet["damage"], 5.0),
                    "falloff" to falloff.ifEmpty { null },
                    "head" to num(extra["head_shot_multiplier"], 1.5),
                    "pierce" to num(bullet["pierce"], 1.0).toLong().coerceAtLeast(1),
                    "range" to (speed * life).coerceIn(8.0, 160.0).let { Math.round(it * 10.0) / 10.0 },
                    "kb" to num(bullet["knockback"], 0.0),
                    "fire" to (if (igniteEntity) num(bullet["ignite_entity_time"], 2.0) else 0.0),
                    "expl" to expl?.let { e ->
                        linkedMapOf("r" to num(e["radius"], 3.0), "dmg" to num(e["damage"], 10.0), "destroy" to (e["destroy_block"] == true))
                    },
                    "inacc" to linkedMapOf(
                        "stand" to num(inacc["stand"], 5.0), "move" to num(inacc["move"], num(inacc["stand"], 5.0)),
                        "aim" to num(inacc["aim"], num(inacc["sneak"], 1.0))
                    ),
                    "reload" to linkedMapOf(
                        "ef" to num(feed["empty"], 2.0), "ec" to num(cooldown["empty"], num(feed["empty"], 2.0)),
                        "tf" to num(feed["tactical"], num(feed["empty"], 2.0)), "tc" to num(cooldown["tactical"], num(cooldown["empty"], 2.0))
                    ),
                    "draw" to num(dat["draw_time"], 0.3),
                    "tracer" to (bullet["tracer_count_interval"] != null),
                    "shake" to (recoilShake(dat["recoil"])),
                    "snd" to linkedMapOf(
                        "shoot" to shootSound, "shoot3p" to (snd("shoot_3p") ?: shootSound), "dry" to snd("dry_fire"),
                        "head" to snd("head_hit"), "flesh" to snd("flesh_hit"), "kill" to snd("kill"), "mode" to snd("fire_select"),
                        "re" to (keyed("reload_empty") ?: listOfNotNull(snd("reload_empty")?.let { listOf(0.0, it) })),
                        "rt" to (keyed("reload_tactical") ?: listOfNotNull(snd("reload_tactical")?.let { listOf(0.0, it) })),
                        "draw" to (keyed("draw") ?: listOfNotNull(snd("draw")?.let { listOf(0.0, it) }))
                    )
                )
                // Accessori di serie (calci, mirini…): si vede solo quello montato di base, come in Java.
                val hidden = LinkedHashSet<String>(TACZ_HIDDEN)
                val keep = LinkedHashSet<String>()
                val builtin = (dat["builtin_attachments"] as? Map<*, *>).asJsonObject().orEmpty()
                for ((_, v) in builtin) {
                    val m = (v as? Map<*, *>).asJsonObject()
                    val def = (if (m != null) m["default_attached"] else v) as? String
                    def?.let { Rl.parse(it)?.path }?.let { keep.add(it) }
                    for (a in (m?.get("attachments") as? List<*>).orEmpty()) {
                        val p = Rl.parse(a as? String)?.path ?: continue
                        if (p !in keep) hidden.add(p)
                    }
                }
                hidden.removeAll(keep)
                val tp = (((disp["transform"] as? Map<*, *>)?.get("scale") as? Map<*, *>)?.get("thirdperson") as? List<*>)?.firstOrNull() as? Number
                guns.add(
                    TaczGun(
                        bid, runtime["name"] as String, idx["type"] as? String ?: "misc", (idx["sort"] as? Number)?.toInt() ?: 0,
                        icon, "textures/mmi/${rl.ns}/" + slot!!.path, model, texture, "textures/mmi/${rl.ns}/" + (texRl?.path ?: "gun/uv/${rl.path}"),
                        anims, tp?.toDouble() ?: 0.6, hidden.toList(), keep, runtime,
                        num(((dat["melee"] as? Map<*, *>)?.get("default") as? Map<*, *>)?.get("damage"), 1.0),
                        rpm, "auto" in modes
                    )
                )
            }

            // ---- banchi della mod
            for ((kind, rl, entry) in index) if (kind == "blocks") {
                val idx = obj(entry) ?: continue
                val disp = displayOf(jar, root, idx["display"] as? String, "blocks", limits)
                val dat = Rl.parse(idx["data"] as? String, rl.ns)?.let { obj(root + "data/${it.ns}/data/blocks/${it.path}.json") }
                val texRl = Rl.parse(disp?.get("texture") as? String, rl.ns)
                val tabs = (dat?.get("tabs") as? List<*>).orEmpty().mapNotNull { t ->
                    val m = (t as? Map<*, *>).asJsonObject() ?: return@mapNotNull null
                    val id = m["id"] as? String ?: return@mapNotNull null
                    id to (m["name"] as? String ?: id)
                }
                blocks.add(
                    TaczBlock(
                        rl.ns + ":" + clean(rl.path), idx["name"] as? String ?: "${rl.ns}.block.${rl.path}.name",
                        asset(Rl.parse(disp?.get("model") as? String, rl.ns), "geo_models", "json"), asset(texRl, "textures", "png"),
                        "textures/mmi/${rl.ns}/" + (texRl?.path ?: "block/${rl.path}"), tabs
                    )
                )
            }

            // ---- ricette dei banchi
            val gunTypes = guns.associate { g -> g.id to g.type }
            val recipeRe = Regex("^" + Regex.escape(root) + "data/([a-z0-9_.-]+)/recipes/(.+)\\.json$")
            for (n in all) {
                recipeRe.find(n) ?: continue
                val r = obj(n) ?: continue
                if ((r["type"] as? String)?.endsWith("gun_smith_table_crafting") != true) continue
                val result = (r["result"] as? Map<*, *>).asJsonObject() ?: continue
                val type = result["type"] as? String ?: "custom"
                val rid = Rl.parse(result["id"] as? String, "tacz") ?: continue
                val count = ((result["count"] as? Number)?.toInt() ?: 1).coerceIn(1, 64)
                val out: Triple<String, String, String>? = when (type) {
                    "gun" -> guns.firstOrNull { it.id == rid.ns + ":" + clean(rid.path).let { p -> if (p.first().isDigit()) "gun_$p" else p } }?.let { g ->
                        Triple(g.id, g.nameKey, "tacz:" + (gunTypes[g.id] ?: "misc"))
                    }
                    "ammo" -> ammo.firstOrNull { it.javaId == rid.toString() }?.let { a ->
                        Triple(a.id, a.nameKey, "tacz:" + ((result["group"] as? String) ?: "ammo"))
                    }
                    "attachment" -> {
                        skips["accessorio (in Bedrock non si monta sulle armi)"] = (skips["accessorio (in Bedrock non si monta sulle armi)"] ?: 0) + 1
                        null
                    }
                    else -> null
                }
                if (out == null) {
                    if (type != "attachment") skips["risultato sconosciuto ($type)"] = (skips["risultato sconosciuto ($type)"] ?: 0) + 1
                    continue
                }
                val mats = ArrayList<Map<String, Any?>>()
                var bad: String? = null
                for (m in (r["materials"] as? List<*>).orEmpty()) {
                    val mm = (m as? Map<*, *>).asJsonObject() ?: continue
                    val cnt = ((mm["count"] as? Number)?.toInt() ?: 1).coerceAtLeast(1)
                    try {
                        when (val ing = resolver.ingredient(mm["item"])) {
                            is Ingredient.Item -> mats.add(linkedMapOf("items" to listOf(ing.item.id), "count" to cnt.toLong(), "label" to ing.item.id.removePrefix("minecraft:").replace('_', ' ')))
                            is Ingredient.Tag -> mats.add(linkedMapOf("tag" to ing.tag, "count" to cnt.toLong(), "label" to tagLabel(ing.tag)))
                        }
                    } catch (s: Skip) {
                        bad = s.reason
                    }
                }
                if (bad != null) {
                    skips[bad] = (skips[bad] ?: 0) + 1
                    continue
                }
                val icon = guns.firstOrNull { it.id == out.first }?.iconPath ?: ammo.firstOrNull { it.id == out.first }?.iconPath
                recipes.add(TaczRecipe(out.third, out.first, count, out.second, icon, mats))
            }
        }
        if (guns.isEmpty() && ammo.isEmpty()) return null

        // ---- i banchi si costruiscono nel banco da lavoro (ricette della mod, fuori dai pacchetti; in Java il banco è
        // un oggetto generico con il nome del banco nei dati: qui ogni banco è un blocco a sé)
        val tableIds = blocks.map { it.id }.toSet()
        val crafting = ArrayList<Pair<String, Map<String, Any?>>>()
        val notes = ArrayList<String>()
        val recipeAt = Regex("^data/([a-z0-9_.-]+)/recipes?/(.+)\\.json$")
        for (n in all) {
            if (roots.any { n.startsWith(it) } || recipeAt.find(n) == null) continue
            val r = obj(n) ?: continue
            if (r["type"] != "minecraft:crafting_shaped") continue
            val res = (r["result"] as? Map<*, *>).asJsonObject() ?: continue
            val item = res["item"] as? String ?: res["id"] as? String ?: continue
            val target = ((res["nbt"] as? Map<*, *>)?.get("BlockId") as? String) ?: item
            val bid = Rl.parse(target)?.let { it.ns + ":" + clean(it.path) } ?: continue
            if (bid !in tableIds || crafting.any { it.first.endsWith("/" + clean(bid.substringAfter(':')) + ".json") }) continue
            val pattern = (r["pattern"] as? List<*>).orEmpty().filterIsInstance<String>()
            if (pattern.isEmpty() || pattern.size > 3 || pattern.any { it.length > 3 }) continue
            val width = pattern.maxOf { it.length }
            val rows = pattern.map { it.padEnd(width, ' ') }
            val keyIn = (r["key"] as? Map<*, *>).asJsonObject() ?: continue
            val key = LinkedHashMap<String, Any?>()
            for (ch in rows.joinToString("").toSet()) {
                if (ch == ' ') continue
                val v = keyIn[ch.toString()] ?: continue
                val ing = try {
                    resolver.ingredient(v)
                } catch (e: Skip) {
                    // Es. la cassa di munizioni di TACZ (in Java ha un modello 3D e livelli): al suo posto una cassa.
                    notes.add("${label(lang, blocks.first { it.id == bid }.nameKey)}: nella ricetta «${(v as? Map<*, *>)?.get("item") ?: v}» è sostituito da una cassa")
                    Ingredient.Item(BedrockItem("minecraft:chest"))
                }
                key[ch.toString()] = ing.json()
            }
            val id = "mmi:tacz_" + clean(bid.substringAfter(':'))
            crafting.add(
                "mmi_tacz/" + clean(bid.substringAfter(':')) + ".json" to linkedMapOf(
                    "format_version" to "1.20.10",
                    "minecraft:recipe_shaped" to linkedMapOf(
                        "description" to linkedMapOf("identifier" to id), "tags" to listOf("crafting_table"), "pattern" to rows, "key" to key,
                        "unlock" to linkedMapOf("context" to "AlwaysUnlocked"), "result" to linkedMapOf("item" to bid)
                    )
                )
            )
        }
        return TaczContent(
            mod, roots.map { it.trimEnd('/').substringAfterLast('/').ifEmpty { mod.jar.label } }, guns.sortedWith(compareBy({ it.type }, { it.sort })),
            ammo, blocks, recipes, lang, sounds, skips, attachments, skipped, roots, crafting, notes.distinct()
        )
    }

    private fun label(lang: Map<String, Map<String, String>>, key: String): String = lang["it_IT"]?.get(key) ?: lang["en_US"]?.get(key) ?: key

    private fun displayOf(jar: JavaJar, root: String, ref: String?, folder: String, limits: ConvertLimits): Map<String, Any?>? {
        val rl = Rl.parse(ref, "tacz") ?: return null
        return jar.json(root + "assets/${rl.ns}/display/$folder/${rl.path}.json", limits.maxJsonBytes).asJsonObject()
    }

    private fun num(v: Any?, def: Double): Double = (v as? Number)?.toDouble() ?: def

    fun clean(s: String): String = s.lowercase().map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")

    /** La scossa della visuale a ogni colpo: dal rinculo verticale più forte dell'arma (in Java la visuale si alza). */
    private fun recoilShake(v: Any?): Double {
        val pitch = ((v as? Map<*, *>)?.get("pitch") as? List<*>).orEmpty()
        var max = 0.0
        for (k in pitch) {
            val vals = ((k as? Map<*, *>)?.get("value") as? List<*>).orEmpty()
            for (x in vals) (x as? Number)?.toDouble()?.let { max = maxOf(max, Math.abs(it)) }
        }
        if (max <= 0.0) return 0.0
        return (Math.round((0.02 + max * 0.03).coerceIn(0.02, 0.25) * 1000.0) / 1000.0)
    }

    private fun tagLabel(tag: String): String = when (tag) {
        "minecraft:logs" -> "Tronchi (qualsiasi)"
        "minecraft:planks" -> "Assi (qualsiasi)"
        "minecraft:wool" -> "Lana (qualsiasi)"
        "minecraft:coals" -> "Carbone"
        else -> tag.substringAfter(':').replace('_', ' ') + " (gruppo)"
    }
}

/**
 * I file Bedrock delle armi: oggetti, modelli in mano (con le pose di prima e terza persona calcolate dai segnaposto del
 * modello di TACZ), animazioni di sparo ed estrazione, suoni, banchi e i dati per lo script.
 */
internal object TaczWriter {

    /** Nel modello del giocatore di Bedrock: spalla destra, punto della mano e posa del braccio (terza e prima persona). */
    private val ARM = doubleArrayOf(-5.0, 22.0, 0.0)
    private val ITEM = doubleArrayOf(-6.0, 15.0, 1.0)
    /** Dove sta l'occhio nella vista in prima persona (nello spazio del modello del braccio). */
    private val EYE = doubleArrayOf(0.0, 25.0, 0.0)

    class Out(val js: String, val geometryCount: Int, val soundFiles: Int)

    fun write(
        all: List<TaczContent>, bp: String, rp: String, limits: ConvertLimits,
        itemTextures: MutableMap<String, Any?>, terrain: MutableMap<String, Any?>, blocksJson: MutableMap<String, Any?>,
        soundDefs: MutableMap<String, Any?>, sink: (String, ByteArray) -> Unit
    ): Out {
        fun json(v: Any?, pretty: Boolean = true) = Json.stringify(v, pretty).toByteArray(Charsets.UTF_8)
        val gunsJs = LinkedHashMap<String, Any?>()
        val ammoJs = LinkedHashMap<String, Any?>()
        val tablesJs = LinkedHashMap<String, Any?>()
        val recipesJs = ArrayList<Any?>()
        val copied = HashSet<String>()
        var geos = 0
        var soundFiles = 0
        fun copy(jar: JavaJar, entry: String?, path: String) {
            if (entry == null || !copied.add(path)) return
            jar.bytes(entry, limits.maxPngBytes)?.let { sink(rp + path + ".png", it) }
        }
        for (t in all) {
            val jar = t.mod.jar
            for (g in t.guns) {
                val key = "mmi_" + TaczReader.clean(g.iconPath.removePrefix("textures/mmi/"))
                itemTextures[key] = linkedMapOf("textures" to g.iconPath)
                copy(jar, g.iconEntry, g.iconPath)
                val tags = listOf("mmi:tacz_gun", "mmi:tacz_" + TaczReader.clean(g.type))
                val c = linkedMapOf<String, Any?>(
                    "minecraft:icon" to key,
                    "minecraft:display_name" to linkedMapOf("value" to g.nameKey),
                    "minecraft:max_stack_size" to 1L,
                    "minecraft:hand_equipped" to true,
                    "minecraft:use_modifiers" to linkedMapOf("use_duration" to 3600L, "movement_modifier" to 0.85),
                    "minecraft:damage" to Math.round(g.melee.coerceIn(1.0, 40.0)).toLong(),
                    "minecraft:can_destroy_in_creative" to false,
                    "minecraft:tags" to linkedMapOf("tags" to tags),
                    "mmi:tacz_gun" to emptyMap<String, Any?>()
                )
                sink(
                    bp + "items/" + g.id.replace(':', '/') + ".json",
                    json(item(g.id, "equipment", c))
                )
                val geo = g.modelEntry?.let { jar.json(it, 8 * 1024 * 1024).asJsonObject() }?.let { gunGeometry(it, g) }
                if (geo != null && g.textureEntry != null) {
                    val path = g.id.replace(':', '/')
                    sink(rp + "models/entity/mmi_tacz/$path.geo.json", json(geo.first, pretty = false))
                    copy(jar, g.textureEntry, g.texturePath)
                    val anims = animations(jar, g, geo.second, limits)
                    sink(rp + "animations/mmi_tacz/$path.animation.json", json(anims.first))
                    sink(rp + "animation_controllers/mmi_tacz/$path.controller.json", json(controller(g, anims.second)))
                    sink(rp + "render_controllers/mmi_tacz/$path.render.json", json(render(g, geo.second)))
                    sink(rp + "attachables/mmi_tacz/$path.json", json(attachable(g, anims.second)))
                    geos++
                }
                gunsJs[g.id] = g.runtime
            }
            for (a in t.ammo) {
                val key = "mmi_" + TaczReader.clean(a.iconPath.removePrefix("textures/mmi/"))
                if (a.iconEntry != null) {
                    itemTextures[key] = linkedMapOf("textures" to a.iconPath)
                    copy(jar, a.iconEntry, a.iconPath)
                }
                val c = linkedMapOf<String, Any?>(
                    "minecraft:display_name" to linkedMapOf("value" to a.nameKey),
                    "minecraft:max_stack_size" to a.stack.toLong(),
                    "minecraft:tags" to linkedMapOf("tags" to listOf("mmi:tacz_ammo"))
                )
                if (a.iconEntry != null) c["minecraft:icon"] = key
                sink(bp + "items/" + a.id.replace(':', '/') + ".json", json(item(a.id, "items", c)))
                ammoJs[a.id] = linkedMapOf("name" to a.nameKey)
            }
            for (b in t.blocks) {
                val path = b.id.replace(':', '/')
                val geoDoc = b.modelEntry?.let { jar.json(it, limits.maxJsonBytes).asJsonObject() }?.let { blockGeometry(it, "geometry.mmi.tacz.block." + TaczReader.clean(b.id.substringAfter(':'))) }
                val key = "mmi_" + TaczReader.clean(b.texturePath.removePrefix("textures/mmi/"))
                if (b.textureEntry != null) {
                    terrain[key] = linkedMapOf("textures" to b.texturePath)
                    copy(jar, b.textureEntry, b.texturePath)
                }
                if (geoDoc != null) sink(rp + "models/blocks/mmi_tacz/$path.geo.json", json(geoDoc, pretty = false))
                sink(bp + "blocks/$path.json", json(blockDoc(b, geoDoc != null && b.textureEntry != null, key)))
                blocksJson[b.id] = linkedMapOf("sound" to "metal")
                tablesJs[b.id] = linkedMapOf(
                    "name" to b.nameKey,
                    "tabs" to b.tabs.map { (id, n) -> linkedMapOf("id" to id, "name" to n) },
                    "empty" to "§7In Bedrock qui non si crea niente: gli accessori delle armi non si montano"
                )
            }
            for (r in t.recipes) {
                recipesJs.add(
                    linkedMapOf("tab" to r.tab, "result" to r.result, "count" to r.count.toLong(), "name" to r.nameKey, "icon" to r.icon, "materials" to r.materials)
                )
            }
            for ((id, entry) in t.sounds) {
                val path = "sounds/mmi/" + id.replace('.', '/')
                soundDefs[id] = linkedMapOf(
                    "category" to "player", "min_distance" to 1.0, "max_distance" to if (id.contains("shoot")) 96.0 else 24.0,
                    "sounds" to listOf(linkedMapOf("name" to path, "volume" to 1.0, "load_on_low_memory" to true))
                )
                jar.bytes(entry, 8 * 1024 * 1024)?.let { sink(rp + "$path.ogg", it); soundFiles++ }
            }
        }
        val data = buildString {
            append("// Dati delle armi convertite dai pacchetti di TACZ (Minecraft Mod Installer).\n")
            append("export const GUNS = ").append(Json.stringify(gunsJs)).append(";\n")
            append("export const AMMO = ").append(Json.stringify(ammoJs)).append(";\n")
            append("export const TABLES = ").append(Json.stringify(tablesJs)).append(";\n")
            append("export const RECIPES = ").append(Json.stringify(recipesJs)).append(";\n")
        }
        sink(bp + "scripts/mmi_tacz_data.js", data.toByteArray(Charsets.UTF_8))
        val runtime = TaczWriter::class.java.getResourceAsStream("/com/mmi/modinstaller/convert/tacz_runtime.js")?.use { it.readBytes() }
            ?: throw IllegalStateException("tacz_runtime.js mancante")
        sink(bp + "scripts/mmi_tacz.js", runtime)
        return Out("import \"./mmi_tacz.js\";\n", geos, soundFiles)
    }

    private fun item(id: String, category: String, components: Map<String, Any?>) = linkedMapOf(
        "format_version" to Behaviors.FORMAT,
        "minecraft:item" to linkedMapOf(
            "description" to linkedMapOf("identifier" to id, "menu_category" to linkedMapOf("category" to category)),
            "components" to components
        )
    )

    // ------------------------------------------------------------------------------------------------ modello in mano

    /** Il modello di TACZ con un osso in più («mmi_gun») legato alla mano; restituisce il documento e i nomi degli ossi. */
    private fun gunGeometry(doc: Map<String, Any?>, g: TaczGun): Pair<Map<String, Any?>, GunBones>? {
        val geo = (doc["minecraft:geometry"] as? List<*>)?.firstOrNull().asJsonObject() ?: return null
        val bones = (geo["bones"] as? List<*>).orEmpty().mapNotNull { it.asJsonObject() }
        if (bones.isEmpty()) return null
        val names = bones.mapNotNull { it["name"] as? String }.toSet()
        fun pivot(name: String): DoubleArray? = bones.firstOrNull { it["name"] == name }?.let { b ->
            (b["pivot"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.size == 3 }?.toDoubleArray()
        }
        val out = ArrayList<Any?>()
        out.add(linkedMapOf("name" to "mmi_gun", "binding" to "q.item_slot_to_bone_name(c.item_slot)", "pivot" to listOf(0L, 24L, 0L)))
        for (b in bones) {
            val m = LinkedHashMap(b)
            if (m["parent"] == null) m["parent"] = "mmi_gun"
            out.add(m)
        }
        val desc = LinkedHashMap(geo["description"].asJsonObject() ?: emptyMap())
        desc["identifier"] = "geometry.mmi.tacz." + TaczReader.clean(g.id.substringAfter(':'))
        desc["visible_bounds_width"] = 4L
        desc["visible_bounds_height"] = 4L
        desc["visible_bounds_offset"] = listOf(0L, 1L, 0L)
        val hand = pivot("thirdperson_hand") ?: doubleArrayOf(0.0, 6.0, 4.0)
        val eye = pivot("idle_view") ?: pivot("camera") ?: doubleArrayOf(2.5, 12.0, 14.0)
        return linkedMapOf<String, Any?>(
            "format_version" to (doc["format_version"] as? String ?: "1.12.0"),
            "minecraft:geometry" to listOf(linkedMapOf("description" to desc, "bones" to out))
        ) to GunBones(desc["identifier"] as String, names, hand, eye, bones.filter { it["parent"] == "attachment_adapter" }.mapNotNull { it["name"] as? String })
    }

    class GunBones(val identifier: String, val names: Set<String>, val hand: DoubleArray, val eye: DoubleArray, val adapterChildren: List<String>)

    private class Anims(val hold1: String, val hold3: String, val shoot: String?, val draw: String?)

    /** Le pose in mano (calcolate) e, dalle animazioni di TACZ, lo sparo e l'estrazione. */
    private fun animations(jar: JavaJar, g: TaczGun, bones: GunBones, limits: ConvertLimits): Pair<Map<String, Any?>, Anims> {
        val base = "animation.mmi.tacz." + TaczReader.clean(g.id.substringAfter(':'))
        val out = LinkedHashMap<String, Any?>()
        val (r3, p3) = holdThirdPerson(bones.hand, g.thirdPersonScale)
        out["$base.hold3"] = linkedMapOf("loop" to true, "bones" to linkedMapOf("mmi_gun" to linkedMapOf("rotation" to r3, "position" to p3, "scale" to r(g.thirdPersonScale))))
        val (r1, p1) = holdFirstPerson(bones.eye)
        out["$base.hold1"] = linkedMapOf("loop" to true, "bones" to linkedMapOf("mmi_gun" to linkedMapOf("rotation" to r1, "position" to p1)))
        var shoot: String? = null
        var draw: String? = null
        for (a in g.animEntries) {
            val clips = (jar.json(a, limits.maxJsonBytes).asJsonObject()?.get("animations") as? Map<*, *>) ?: continue
            if (shoot == null) (clips["shoot"] as? Map<*, *>)?.let { s ->
                val clip = LinkedHashMap(s.asJsonObject()!!)
                clip.remove("sound_effects")
                if (g.auto) {
                    // Raffica: si ripete l'inizio del colpo al ritmo dell'arma.
                    val gap = 60.0 / g.rpm
                    clip["loop"] = true
                    clip["anim_time_update"] = "math.mod(q.anim_time + q.delta_time, ${r(gap)})"
                } else clip["loop"] = "hold_on_last_frame"
                shoot = "$base.shoot"
                out[shoot!!] = clip
            }
            if (draw == null) (clips["draw"] as? Map<*, *>)?.let { d ->
                val clip = LinkedHashMap(d.asJsonObject()!!)
                clip.remove("sound_effects")
                clip["loop"] = false
                draw = "$base.draw"
                out[draw!!] = clip
            }
        }
        return linkedMapOf("format_version" to "1.8.0", "animations" to out) to Anims("$base.hold1", "$base.hold3", shoot, draw)
    }

    private fun controller(g: TaczGun, a: Anims): Map<String, Any?> {
        val use = "q.main_hand_item_use_duration > 0.0"
        val states = LinkedHashMap<String, Any?>()
        val toFire = if (a.shoot != null) listOf(linkedMapOf("fire" to use)) else emptyList()
        states["draw"] = linkedMapOf(
            "animations" to if (a.draw != null) listOf(linkedMapOf("draw" to "c.is_first_person")) else emptyList<Any?>(),
            "transitions" to toFire + listOf(linkedMapOf("idle" to if (a.draw != null) "q.all_animations_finished" else "1.0"))
        )
        states["idle"] = linkedMapOf("transitions" to toFire)
        if (a.shoot != null) states["fire"] = linkedMapOf(
            "animations" to listOf("shoot"),
            "transitions" to listOf(linkedMapOf("idle" to "!($use)"))
        )
        return linkedMapOf(
            "format_version" to "1.10.0",
            "animation_controllers" to linkedMapOf(
                "controller.animation.mmi.tacz." + TaczReader.clean(g.id.substringAfter(':')) to linkedMapOf("initial_state" to "draw", "states" to states)
            )
        )
    }

    private fun render(g: TaczGun, bones: GunBones): Map<String, Any?> {
        val vis = ArrayList<Any?>()
        vis.add(linkedMapOf("*" to true))
        val hide = LinkedHashSet(g.hidden)
        hide.addAll(bones.names.filter { Regex("shell_\\d+").matches(it) })
        hide.addAll(bones.adapterChildren.filter { it !in g.builtinShown })
        for (h in hide) if (h in bones.names) vis.add(linkedMapOf(h to false))
        return linkedMapOf(
            "format_version" to "1.8.0",
            "render_controllers" to linkedMapOf(
                "controller.render.mmi.tacz." + TaczReader.clean(g.id.substringAfter(':')) to linkedMapOf(
                    "geometry" to "Geometry.default",
                    "materials" to listOf(linkedMapOf("*" to "Material.default")),
                    "textures" to listOf("Texture.default"),
                    "part_visibility" to vis
                )
            )
        )
    }

    private fun attachable(g: TaczGun, a: Anims): Map<String, Any?> {
        val clean = TaczReader.clean(g.id.substringAfter(':'))
        val animations = linkedMapOf<String, Any?>("hold1" to a.hold1, "hold3" to a.hold3, "ctrl" to "controller.animation.mmi.tacz.$clean")
        a.shoot?.let { animations["shoot"] = it }
        a.draw?.let { animations["draw"] = it }
        return linkedMapOf(
            "format_version" to "1.10.0",
            "minecraft:attachable" to linkedMapOf(
                "description" to linkedMapOf(
                    "identifier" to g.id,
                    "materials" to linkedMapOf("default" to "entity_alphatest", "enchanted" to "entity_alphatest_glint"),
                    "textures" to linkedMapOf("default" to g.texturePath, "enchanted" to "textures/misc/enchanted_item_glint"),
                    "geometry" to linkedMapOf("default" to "geometry.mmi.tacz.$clean"),
                    "animations" to animations,
                    "scripts" to linkedMapOf("animate" to listOf(linkedMapOf("hold1" to "c.is_first_person"), linkedMapOf("hold3" to "!c.is_first_person"), "ctrl")),
                    "render_controllers" to listOf("controller.render.mmi.tacz.$clean")
                )
            )
        )
    }

    // ---- pose calcolate (catena di ossa del giocatore di Bedrock: braccio destro → punto della mano → arma)

    /** Terza persona: l'impugnatura («thirdperson_hand» di TACZ) nella mano, la canna dritta in avanti. */
    fun holdThirdPerson(hand: DoubleArray, scale: Double): Pair<List<Any?>, List<Any?>> {
        val arm = Mat.Bone(ARM, doubleArrayOf(-18.0, 0.0, 0.0), DoubleArray(3), null)
        val item = Mat.Bone(ITEM, DoubleArray(3), DoubleArray(3), arm)
        val handAt = item.apply(ITEM)
        val ad = Mat.scale(Mat.identity(), scale)
        val td = Mat.sub(handAt, Mat.mul(ad, hand))
        return solve(item, ad, td, scale)
    }

    /** Prima persona: il punto di vista di TACZ («idle_view») sull'occhio, la canna verso il centro dello schermo. */
    fun holdFirstPerson(eye: DoubleArray): Pair<List<Any?>, List<Any?>> {
        val arm = Mat.Bone(ARM, doubleArrayOf(95.0, -45.0, 115.0), doubleArrayOf(13.5, -10.0, 12.0), null)
        val item = Mat.Bone(ITEM, DoubleArray(3), doubleArrayOf(0.0, ARM[1] - ITEM[1] - 7.0, -ITEM[2]), arm)
        // Nella prima persona di Bedrock il davanti è +Z: l'arma (che guarda verso -Z) si gira di mezzo giro.
        val ad = Mat.rotY(Math.PI)
        val td = Mat.sub(EYE, Mat.mul(ad, eye))
        return solve(item, ad, td, 1.0)
    }

    /** Rotazione e spostamento dell'osso «mmi_gun» (perno 0,24,0) perché la catena dia la trasformazione voluta. */
    private fun solve(item: Mat.Bone, ad: Array<DoubleArray>, td: DoubleArray, s: Double): Pair<List<Any?>, List<Any?>> {
        val off = Mat.sub(ITEM, doubleArrayOf(0.0, 24.0, 0.0))
        val o = item.apply(off)
        val cols = (0..2).map { i -> Mat.sub(item.apply(Mat.add(off, DoubleArray(3).also { it[i] = 1.0 })), o) }
        val ac = Array(3) { r -> DoubleArray(3) { c -> cols[c][r] } }
        val rb = Mat.scale(Mat.mul(Mat.transpose(ac), ad), 1.0 / s)
        val pivot = doubleArrayOf(0.0, 24.0, 0.0)
        val pos = Mat.sub(Mat.add(Mat.mul(Mat.transpose(ac), Mat.sub(td, o)), Mat.mul(rb, Mat.scale(pivot, s))), pivot)
        val e = Mat.euler(rb)
        return e.map { r(it) } to pos.map { r(it) }
    }

    private fun r(v: Double): Any = Math.round(v * 1000.0) / 1000.0

    // ------------------------------------------------------------------------------------------------ banchi

    /** Il modello del banco ridotto (se serve) entro i limiti di Bedrock per i blocchi: 30 pixel per lato, centrato. */
    private fun blockGeometry(doc: Map<String, Any?>, identifier: String): Map<String, Any?>? {
        val geo = (doc["minecraft:geometry"] as? List<*>)?.firstOrNull().asJsonObject() ?: return null
        val bones = (geo["bones"] as? List<*>).orEmpty().mapNotNull { it.asJsonObject() }
        val lo = doubleArrayOf(1e9, 1e9, 1e9); val hi = doubleArrayOf(-1e9, -1e9, -1e9)
        for (b in bones) for (c in (b["cubes"] as? List<*>).orEmpty()) {
            val cm = c.asJsonObject() ?: continue
            val o = (cm["origin"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: 0.0 } ?: continue
            val s = (cm["size"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: 0.0 } ?: continue
            for (i in 0..2) { lo[i] = minOf(lo[i], o[i]); hi[i] = maxOf(hi[i], o[i] + s[i]) }
        }
        if (lo[0] > hi[0]) return null
        val size = (0..2).map { hi[it] - lo[it] }
        val k = minOf(1.0, 30.0 / (size.maxOrNull() ?: 1.0))
        val shift = doubleArrayOf(-(lo[0] + hi[0]) / 2.0, -lo[1], -(lo[2] + hi[2]) / 2.0)
        fun tr(v: Any?, inflateOnly: Boolean = false): Any? {
            val l = (v as? List<*>)?.map { (it as? Number)?.toDouble() ?: 0.0 } ?: return v
            return if (inflateOnly) l.map { r(it * k) } else l.mapIndexed { i, x -> r((x + shift[i]) * k) }
        }
        val out = bones.map { b ->
            val m = LinkedHashMap(b)
            m["pivot"] = tr(b["pivot"])
            m.remove("binding")
            m["cubes"] = (b["cubes"] as? List<*>)?.map { c ->
                val cm = LinkedHashMap(c.asJsonObject() ?: emptyMap())
                cm["origin"] = tr(cm["origin"])
                cm["size"] = tr(cm["size"], inflateOnly = true)
                cm["pivot"]?.let { cm["pivot"] = tr(it) }
                (cm["inflate"] as? Number)?.let { cm["inflate"] = r(it.toDouble() * k) }
                cm
            }
            m
        }
        val desc = LinkedHashMap(geo["description"].asJsonObject() ?: emptyMap())
        desc["identifier"] = identifier
        desc["visible_bounds_width"] = 3L; desc["visible_bounds_height"] = 3L; desc["visible_bounds_offset"] = listOf(0L, 1L, 0L)
        return linkedMapOf("format_version" to "1.12.0", "minecraft:geometry" to listOf(linkedMapOf("description" to desc, "bones" to out)))
    }

    private fun blockDoc(b: TaczBlock, shaped: Boolean, textureKey: String): Map<String, Any?> {
        val c = linkedMapOf<String, Any?>(
            "minecraft:display_name" to b.nameKey,
            "minecraft:destructible_by_mining" to linkedMapOf("seconds_to_destroy" to 2.5),
            "minecraft:destructible_by_explosion" to linkedMapOf("explosion_resistance" to 6.0),
            "minecraft:light_dampening" to 0L,
            "mmi:tacz_table" to emptyMap<String, Any?>()
        )
        if (shaped) {
            c["minecraft:geometry"] = "geometry.mmi.tacz.block." + TaczReader.clean(b.id.substringAfter(':'))
            c["minecraft:material_instances"] = linkedMapOf("*" to linkedMapOf("texture" to textureKey, "render_method" to "alpha_test"))
            c["minecraft:collision_box"] = linkedMapOf("origin" to listOf(-8L, 0L, -8L), "size" to listOf(16L, 16L, 16L))
            c["minecraft:selection_box"] = linkedMapOf("origin" to listOf(-8L, 0L, -8L), "size" to listOf(16L, 16L, 16L))
        }
        val perms = listOf("north" to 0L, "west" to 90L, "south" to 180L, "east" to -90L).map { (d, y) ->
            linkedMapOf("condition" to "q.block_state('minecraft:cardinal_direction') == '$d'", "components" to linkedMapOf("minecraft:transformation" to linkedMapOf("rotation" to listOf(0L, y, 0L))))
        }
        return linkedMapOf(
            "format_version" to Behaviors.FORMAT,
            "minecraft:block" to linkedMapOf(
                "description" to linkedMapOf(
                    "identifier" to b.id,
                    "menu_category" to linkedMapOf("category" to "items"),
                    "traits" to linkedMapOf("minecraft:placement_direction" to linkedMapOf("enabled_states" to listOf("minecraft:cardinal_direction"), "y_rotation_offset" to 180L))
                ),
                "components" to c,
                "permutations" to perms
            )
        )
    }
}

/** Matrici 3×3 e le regole di rotazione delle ossa di Bedrock (gradi; prima X, poi Y, poi Z; X e Z col segno invertito). */
internal object Mat {
    fun identity() = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
    fun rotX(a: Double) = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, Math.cos(a), -Math.sin(a)), doubleArrayOf(0.0, Math.sin(a), Math.cos(a)))
    fun rotY(a: Double) = arrayOf(doubleArrayOf(Math.cos(a), 0.0, Math.sin(a)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-Math.sin(a), 0.0, Math.cos(a)))
    fun rotZ(a: Double) = arrayOf(doubleArrayOf(Math.cos(a), -Math.sin(a), 0.0), doubleArrayOf(Math.sin(a), Math.cos(a), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
    fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> a[i][k] * b[k][j] } } }
    fun mul(a: Array<DoubleArray>, v: DoubleArray) = DoubleArray(3) { i -> (0..2).sumOf { k -> a[i][k] * v[k] } }
    fun transpose(a: Array<DoubleArray>) = Array(3) { i -> DoubleArray(3) { j -> a[j][i] } }
    fun scale(a: Array<DoubleArray>, s: Double) = Array(3) { i -> DoubleArray(3) { j -> a[i][j] * s } }
    fun scale(v: DoubleArray, s: Double) = DoubleArray(3) { v[it] * s }
    fun add(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] + b[it] }
    fun sub(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] - b[it] }

    /** La rotazione di un osso di Bedrock [x, y, z] in gradi. */
    fun bedrock(r: DoubleArray): Array<DoubleArray> {
        val d = Math.PI / 180.0
        return mul(rotZ(-r[2] * d), mul(rotY(r[1] * d), rotX(-r[0] * d)))
    }

    /** L'inverso di [bedrock]: gli angoli [x, y, z] in gradi di una matrice di rotazione. */
    fun euler(m: Array<DoubleArray>): List<Double> {
        val b = Math.asin((-m[2][0]).coerceIn(-1.0, 1.0))
        val a = Math.atan2(m[1][0], m[0][0])
        val c = Math.atan2(m[2][1], m[2][2])
        return listOf(-Math.toDegrees(c), Math.toDegrees(b), -Math.toDegrees(a))
    }

    class Bone(val pivot: DoubleArray, val rot: DoubleArray, val pos: DoubleArray, val parent: Bone?) {
        fun apply(v: DoubleArray): DoubleArray {
            val w = add(add(pivot, mul(bedrock(rot), sub(v, pivot))), pos)
            return parent?.apply(w) ?: w
        }
    }
}

/** Il rapporto della mod con le armi di TACZ: cosa è passato, cosa è ricostruito e cosa no (onesto, in italiano). */
internal object TaczReport {

    fun merge(r: ModReport, t: TaczContent, removed: Set<String>): ModReport {
        val en = t.lang["en_US"].orEmpty()
        val it = t.lang["it_IT"].orEmpty()
        fun label(key: String, id: String) = it[key] ?: en[key] ?: id
        val guns = t.guns.map { g ->
            val rt = g.runtime
            val modes = (rt["modes"] as List<*>).joinToString("/") { m -> when (m) { "auto" -> "automatica"; "burst" -> "raffica"; else -> "colpo singolo" } }
            val mag = if (rt["inv"] == true) "munizioni dall'inventario" else "${rt["mag"]} colpi"
            ConvertedEntry(g.id, g.id, label(g.nameKey, g.id), "arma da fuoco · $mag · ${g.rpm} colpi/min · $modes")
        }
        val ammo = t.ammo.map { a -> ConvertedEntry(a.javaId, a.id, label(a.nameKey, a.id), "munizione") }
        val tables = t.blocks.map { b -> ConvertedEntry(b.id, b.id, label(b.nameKey, b.id), "banco di TACZ (ricette in una finestra)") }
        val skips = LinkedHashMap(r.recipeSkips)
        // Le ricette dei banchi saltate prima (il banco generico di Java non esiste in Bedrock) ora ci sono.
        if (t.crafting.isNotEmpty()) skips.keys.removeAll { k -> k.contains("«tacz:gun_smith_table»") || k.contains("«tacz:workbench_") }
        for ((k, v) in t.recipeSkips) skips[k] = (skips[k] ?: 0) + v
        val scripted = ArrayList(r.scripted)
        scripted.add("Armi da fuoco di TACZ: ${t.guns.size} (sparo, caricatore, ricarica con i suoni della mod, raffica, colpi alla testa, danni per distanza, esplosioni)")
        if (t.recipes.isNotEmpty()) scripted.add("Banchi di TACZ: ${t.blocks.size} (le ${t.recipes.size} ricette usano i materiali dell'inventario, come in Java)")
        val simplified = ArrayList(r.simplified)
        simplified.add("Armi: colpi istantanei lungo la mira (senza tempo di volo e senza caduta del proiettile)")
        simplified.add("Armi: si mira accovacciandosi (colpi più precisi, senza ingrandimento); si ricarica accovacciandosi e saltando, o da sola a caricatore vuoto")
        simplified.add("Armi: il rinculo è una scossa della visuale e l'animazione dello sparo (la visuale non si alza come in Java)")
        simplified.add("Armi: i colpi non tengono conto dell'armatura (in Bedrock i colpi ravvicinati verrebbero ignorati)")
        simplified.add("Armi in mano: posa di prima e terza persona calcolata dal modello (non vista nel gioco); in ricarica l'arma resta ferma")
        val notConverted = ArrayList(r.notConverted)
        if (t.attachments > 0) notConverted.add("Accessori delle armi (mirini, silenziatori, calci, impugnature, caricatori estesi): ${t.attachments}")
        notConverted.add("Logica delle armi scritta in Lua nei pacchetti di TACZ: si usa il comportamento standard")
        simplified.addAll(t.craftingNotes)
        if (t.skippedGuns.isNotEmpty()) notConverted.add("Armi non convertite: " + t.skippedGuns.take(6).joinToString(", ") + if (t.skippedGuns.size > 6) "…" else "")
        val packFiles = t.mod.jar.allNames().count { n -> t.roots.any { root -> n.startsWith(root) } }
        val components = ArrayList<Component>()
        for (c in r.components) {
            if (c.bedrockId != null && c.bedrockId in removed) continue
            // I file dei pacchetti di armi non sono «altri file»: sono convertiti qui sotto.
            if (c.kind == "file" && c.originalId == "Altri file" && packFiles > 0) {
                val left = c.count - packFiles
                if (left > 0) components.add(Component(c.kind, c.originalId, "$left file", c.dependencies, c.conversion, c.bedrockId, c.bedrockFunction, c.tests, c.differences, c.status, left))
                continue
            }
            components.add(c)
        }
        if (packFiles > 0) components.add(
            Component(
                "file", "Pacchetti di armi di TACZ", "$packFiles file", emptyList(),
                "armi, munizioni, banchi, modelli, animazioni di sparo ed estrazione, suoni e testi; accessori e script Lua no", null,
                "armi e banchi in Bedrock", emptyList(), listOf("accessori e logica Lua non convertiti"), ComponentStatus.APPROXIMATED, packFiles
            )
        )
        for (g in t.guns) components.add(
            Component(
                "arma", g.id, "arma da fuoco di TACZ (pacchetto di armi: dati, aspetto, modello e animazioni)", listOfNotNull(g.runtime["ammo"] as? String),
                "oggetto con modello 3D in mano e comportamento con la Script API", g.id,
                "spara tenendo premuto «usa», caricatore e ricarica con le munizioni dell'inventario",
                listOf("prova nel server Bedrock ufficiale: sparo, danno, caricatore e ricarica (ci/bds_test.py)"),
                listOf("colpo istantaneo", "niente accessori", "niente ingrandimento del mirino", "rinculo approssimato"),
                ComponentStatus.APPROXIMATED
            )
        )
        for (a in t.ammo) components.add(Component("munizione", a.javaId, "munizione di TACZ", emptyList(), "oggetto", a.id, "si consuma ricaricando le armi", emptyList(), emptyList(), ComponentStatus.CONVERTED))
        for (b in t.blocks) components.add(
            Component(
                "banco", b.id, "banco di TACZ con le ricette delle armi", emptyList(), "blocco con modello e finestra delle ricette (Script API)", b.id,
                "toccandolo si apre l'elenco delle ricette; servono i materiali nell'inventario", emptyList(),
                listOf("modello ridotto a un blocco"), ComponentStatus.RECONSTRUCTED
            )
        )
        if (t.attachments > 0) components.add(
            Component("gruppo", "accessori", "accessori montabili sulle armi", emptyList(), "non convertiti", null, "—", emptyList(),
                listOf("Bedrock non ha la schermata di modifica dell'arma"), ComponentStatus.DATA_KEPT, t.attachments)
        )
        return ModReport(
            r.label, r.title, r.loader, r.version,
            r.items.filter { e -> e.bedrockId !in removed } + guns + ammo, r.blocks.filter { e -> e.bedrockId !in removed } + tables,
            r.recipesConverted + t.recipes.size + t.crafting.size, r.recipesTotal + t.recipes.size + t.recipeSkips.values.sum(), skips,
            r.lootTables, r.ores, r.armorModels, r.entities, r.estimates, simplified, notConverted, r.error, r.codeSummary, r.fromCode,
            components, scripted
        )
    }
}
