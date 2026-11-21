package com.mmi.modinstaller.convert

import com.mmi.modinstaller.core.asJsonList
import com.mmi.modinstaller.core.asJsonObject

/** Quello che si è fatto dei progressi di una mod (per il rapporto). */
internal class AdvancementModReport {
    /** Progressi con un nome (quelli senza sono solo per sbloccare le ricette nel libro di Java). */
    var total = 0
    /** Ottenibili in Bedrock con tutte le condizioni di Java. */
    var full = 0
    /** Ottenibili, ma con qualche condizione fatta solo in parte o con meno strade possibili. */
    var partial = 0
    /** Progressi che in Bedrock non si possono ottenere (li dà il codice della mod). */
    val impossible = LinkedHashSet<String>()
    /** Eventi di Java che Bedrock non segnala agli add-on. */
    val triggers = LinkedHashSet<String>()
    /** Dettagli delle condizioni non controllati (incantesimi, dati degli oggetti…). */
    val ignored = LinkedHashSet<String>()
    var xp = 0
    var loot = 0
    var functions = 0
}

internal class AdvancementOut(
    /** Dati per lo script (null se nessun progresso). */
    val data: Map<String, Any?>?,
    /** Testi: lingua Bedrock → chiave → testo. */
    val lang: Map<String, Map<String, String>>,
    /** Bottini delle ricompense (tabelle Bedrock). */
    val loot: Map<String, Map<String, Any?>>,
    val reports: Map<JavaModContent, AdvancementModReport>
)

/**
 * I progressi (advancement) delle mod, rifatti con la Script API: le condizioni di Java (oggetti nell'inventario, creature
 * uccise, blocchi piazzati, cibi mangiati, biomi e dimensioni, effetti, cavalcature…) si controllano con gli eventi di
 * Bedrock; quando un progresso si completa compaiono l'avviso e il messaggio come in Java, e si danno esperienza e bottini.
 * L'elenco si apre con il comando /mmi:progressi.
 */
internal class AdvancementBuilder(
    private val mods: List<JavaModContent>,
    private val item: (Rl) -> String?,
    private val itemTag: (Rl) -> List<String>,
    private val modEntity: (Rl) -> String?,
    private val modBlock: (Rl) -> String?,
    private val lootConv: LootConverter,
    private val languages: Map<String, String>,
    /** Strutture che lo script genera (id di Java): per «trova la struttura». */
    private val structures: Set<String> = emptySet()
) {
    private val reports = LinkedHashMap<JavaModContent, AdvancementModReport>()
    private val lang = LinkedHashMap<String, MutableMap<String, String>>()
    private val lootOut = LinkedHashMap<String, Map<String, Any?>>()
    private val biomes = BiomeResolver(mods)
    private val entityTags = HashMap<Rl, MutableList<Any?>>()
    private val structureTags = HashMap<Rl, MutableList<Any?>>()

    fun build(): AdvancementOut {
        val out = ArrayList<Map<String, Any?>>()
        for (mod in mods) {
            for (n in mod.jar.allNames()) {
                ENTITY_TAG.matchEntire(n)?.let { m ->
                    mod.jar.json(n, MAX_JSON).asJsonObject()?.let { o ->
                        entityTags.getOrPut(Rl(m.groupValues[1], m.groupValues[2])) { ArrayList() }.addAll(o["values"].asJsonList().orEmpty())
                    }
                }
                STRUCTURE_TAG.matchEntire(n)?.let { m ->
                    mod.jar.json(n, MAX_JSON).asJsonObject()?.let { o ->
                        structureTags.getOrPut(Rl(m.groupValues[1], m.groupValues[2])) { ArrayList() }.addAll(o["values"].asJsonList().orEmpty())
                    }
                }
            }
        }
        for (mod in mods) {
            val rep = AdvancementModReport()
            reports[mod] = rep
            for (n in mod.jar.allNames().sorted()) {
                val m = ADVANCEMENT.matchEntire(n) ?: continue
                val id = Rl(m.groupValues[1], m.groupValues[3])
                val o = mod.jar.json(n, MAX_JSON).asJsonObject() ?: continue
                advancement(mod, id, o, rep)?.let { out.add(it) }
            }
        }
        // I progressi figli di uno non convertito (o senza nome) si attaccano al primo antenato che c'è.
        val ids = out.map { it["id"] as String }.toSet()
        val parents = out.associate { (it["id"] as String) to (it["p"] as String?) }
        for (a in out) {
            @Suppress("UNCHECKED_CAST")
            val m = a as MutableMap<String, Any?>
            var p = m["p"] as String?
            var guard = 0
            while (p != null && p !in ids && guard++ < 64) p = parents[p] ?: rawParents[p]
            m["p"] = p
        }
        if (out.isEmpty()) return AdvancementOut(null, lang, lootOut, reports)
        for ((code, texts) in TOAST) for ((k, v) in texts) lang.getOrPut(code) { LinkedHashMap() }[k] = v
        return AdvancementOut(linkedMapOf("a" to out), lang, lootOut, reports)
    }

    private val rawParents = HashMap<String, String?>()

    private fun advancement(mod: JavaModContent, id: Rl, o: Map<String, Any?>, rep: AdvancementModReport): Map<String, Any?>? {
        rawParents[id.toString()] = (o["parent"] as? String)?.let { Rl.parse(it)?.toString() }
        val display = o["display"].asJsonObject() ?: return null // solo per il libro delle ricette di Java
        rep.total++
        val title = text(mod, id, display["title"], "t")
        val desc = text(mod, id, display["description"], "d")
        val critIn = o["criteria"].asJsonObject().orEmpty()
        val crit = LinkedHashMap<String, Any?>()
        val unsupported = HashSet<String>()
        for ((name, c) in critIn) {
            val spec = criterion(c.asJsonObject() ?: continue, rep)
            if (spec == null) unsupported.add(name) else crit[name] = spec
        }
        @Suppress("UNCHECKED_CAST")
        val req: List<List<String>> = (o["requirements"] as? List<List<String>>)?.map { g -> g.filter { it in critIn } }?.filter { it.isNotEmpty() }
            ?: critIn.keys.map { listOf(it) }
        // Ogni gruppo deve avere almeno una condizione controllabile, se no il progresso non si può ottenere.
        val reachable = req.isNotEmpty() && req.all { g -> g.any { it in crit } }
        val titleText = mod.lang["en_us"]?.get(title) ?: title
        when {
            !reachable -> rep.impossible.add(lang["it_IT"]?.get(title) ?: lang["en_US"]?.get(title) ?: titleText)
            unsupported.isEmpty() -> rep.full++
            else -> rep.partial++
        }
        val rewards = o["rewards"].asJsonObject().orEmpty()
        val xp = (rewards["experience"] as? Number)?.toLong() ?: 0L
        if (xp > 0) rep.xp++
        val loot = rewards["loot"].asJsonList().orEmpty().mapNotNull { (it as? String)?.let { t -> lootPath(t) } }
        if (loot.isNotEmpty()) rep.loot++
        if (rewards["function"] != null) rep.functions++
        val icon = display["icon"].asJsonObject()?.let { (it["id"] ?: it["item"]) as? String }?.let { Rl.parse(it) }?.let { item(it) }
        return linkedMapOf(
            "id" to id.toString(), "p" to rawParents[id.toString()], "t" to title, "d" to desc, "i" to icon,
            "f" to ((display["frame"] as? String) ?: "task"), "h" to (display["hidden"] == true),
            "a" to (display["announce_to_chat"] != false), "s" to (display["show_toast"] != false),
            "c" to crit, "r" to (if (reachable) req.map { g -> g.filter { it in crit } } else emptyList<List<String>>()),
            "ok" to reachable, "x" to xp, "l" to loot
        )
    }

    /** Il testo (titolo o descrizione) come chiave di traduzione: quelle di Java con le traduzioni della mod. */
    private fun text(mod: JavaModContent, id: Rl, v: Any?, kind: String): String {
        val key: String
        val fallback: String
        when (v) {
            is Map<*, *> -> {
                val tr = v["translate"] as? String
                if (tr != null) { key = tr; fallback = (v["fallback"] as? String) ?: tr }
                else { key = "mmi.adv.${id.ns}.${id.path.replace('/', '.')}.$kind"; fallback = (v["text"] as? String) ?: id.path.substringAfterLast('/') }
            }
            is String -> { key = "mmi.adv.${id.ns}.${id.path.replace('/', '.')}.$kind"; fallback = v }
            else -> { key = "mmi.adv.${id.ns}.${id.path.replace('/', '.')}.$kind"; fallback = id.path.substringAfterLast('/').replace('_', ' ') }
        }
        lang.getOrPut("en_US") { LinkedHashMap() }[key] = mod.lang["en_us"]?.get(key) ?: fallback
        for ((code, map) in mod.lang) {
            val bedrock = languages[code] ?: continue
            map[key]?.let { lang.getOrPut(bedrock) { LinkedHashMap() }[key] = it }
        }
        return key
    }

    // ---- condizioni
    private fun criterion(c: Map<String, Any?>, rep: AdvancementModReport): Map<String, Any?>? {
        val trigger = (c["trigger"] as? String)?.let { Rl.parse(it)?.toString() } ?: return null
        val cond = c["conditions"].asJsonObject().orEmpty()
        val out: LinkedHashMap<String, Any?> = when (trigger) {
            "minecraft:tick" -> linkedMapOf<String, Any?>("k" to "tick")
            "minecraft:inventory_changed" -> {
                val items = cond["items"].asJsonList().orEmpty().map { itemPred(it, rep) ?: return unsupported(trigger, rep) }
                linkedMapOf<String, Any?>("k" to "inv", "it" to items)
            }
            "minecraft:player_killed_entity" -> linkedMapOf<String, Any?>("k" to "kill", "e" to (entities(cond["entity"], rep) ?: return unsupported(trigger, rep)))
            "minecraft:player_hurt_entity" -> linkedMapOf<String, Any?>("k" to "hurt", "e" to (entities(cond["entity"], rep) ?: return unsupported(trigger, rep)))
            "minecraft:consume_item" -> linkedMapOf<String, Any?>("k" to "eat", "it" to (itemPred(cond["item"], rep) ?: return unsupported(trigger, rep)))
            "minecraft:using_item" -> linkedMapOf<String, Any?>("k" to "using", "it" to (itemPred(cond["item"], rep) ?: return unsupported(trigger, rep)))
            "minecraft:placed_block" -> linkedMapOf<String, Any?>("k" to "place", "b" to (placedBlock(cond, rep) ?: return unsupported(trigger, rep)),
                "it" to (itemPred(cond["item"], rep) ?: return unsupported(trigger, rep)))
            "minecraft:item_used_on_block" -> linkedMapOf<String, Any?>("k" to "useon", "b" to (placedBlock(cond, rep) ?: return unsupported(trigger, rep)),
                "it" to (itemPred(cond["item"], rep) ?: return unsupported(trigger, rep)))
            "minecraft:player_interacted_with_entity" -> linkedMapOf<String, Any?>("k" to "interact", "e" to (entities(cond["entity"], rep) ?: return unsupported(trigger, rep)),
                "it" to (itemPred(cond["item"], rep) ?: return unsupported(trigger, rep)))
            "minecraft:enter_block" -> {
                val b = (cond["block"] as? String)?.let { blockNames(it) }
                if (cond["block"] != null && b.isNullOrEmpty()) return unsupported(trigger, rep)
                linkedMapOf<String, Any?>("k" to "enter", "b" to b)
            }
            "minecraft:effects_changed" -> {
                val effs = cond["effects"].asJsonObject().orEmpty().keys.map { e ->
                    val rl = Rl.parse(e) ?: return unsupported(trigger, rep)
                    if (rl.ns != "minecraft") return unsupported("effetto della mod $e", rep)
                    "minecraft:" + (EFFECTS[rl.path] ?: rl.path)
                }
                linkedMapOf<String, Any?>("k" to "eff", "ef" to effs)
            }
            "minecraft:location", "minecraft:slept_in_bed", "minecraft:hero_of_the_village", "minecraft:voluntary_exile" -> {
                if (trigger != "minecraft:location") rep.ignored.add("«$trigger» controllato come posizione")
                linkedMapOf<String, Any?>("k" to "loc").also { if (!location(cond["player"] ?: cond["location"]?.let { mapOf("location" to it) }, it, rep)) return unsupported(trigger, rep) }
            }
            "minecraft:started_riding" -> {
                val v = findKey(cond["player"], "vehicle")
                linkedMapOf<String, Any?>("k" to "ride", "e" to (if (v == null) null else entities(v, rep) ?: return unsupported(trigger, rep)))
            }
            "minecraft:changed_dimension" -> {
                val to = cond["to"] as? String
                val from = cond["from"] as? String
                val t = to?.let { BiomeResolver.dimension(it) ?: return unsupported("dimensione della mod $it", rep) }
                val f = from?.let { BiomeResolver.dimension(it) ?: return unsupported("dimensione della mod $it", rep) }
                linkedMapOf<String, Any?>("k" to "dim", "to" to t, "from" to f)
            }
            else -> return unsupported(trigger, rep)
        }
        return out
    }

    private fun unsupported(what: String, rep: AdvancementModReport): Map<String, Any?>? {
        rep.triggers.add(what)
        return null
    }

    /** Un oggetto di Java: {i: id Bedrock (o null = qualunque), n: [min, max]}. */
    private fun itemPred(v: Any?, rep: AdvancementModReport): Map<String, Any?>? {
        val m = v.asJsonObject() ?: return if (v == null) linkedMapOf("i" to null) else null
        val ids = LinkedHashSet<String>()
        var any = true
        fun add(x: Any?) {
            when (x) {
                is String -> {
                    any = false
                    if (x.startsWith("#")) Rl.parse(x.substring(1))?.let { ids.addAll(itemTag(it)) }
                    else Rl.parse(x)?.let { rl -> item(rl)?.let { ids.add(it) } }
                }
                is List<*> -> x.forEach { add(it) }
            }
        }
        add(m["items"]); add(m["item"])
        (m["tag"] as? String)?.let { add("#$it") }
        if (!any && ids.isEmpty()) return null
        val count = m["count"]
        val n = when (count) {
            is Number -> listOf(count.toLong(), count.toLong())
            is Map<*, *> -> listOf((count["min"] as? Number)?.toLong() ?: 1L, (count["max"] as? Number)?.toLong() ?: 999L)
            else -> null
        }
        for (k in m.keys) if (k !in setOf("items", "item", "tag", "count")) rep.ignored.add("oggetti: «$k»")
        return linkedMapOf("i" to (if (any) null else ids.toList()), "n" to n)
    }

    /** Le creature di una condizione di Java (elenco di condizioni o predicato), come id Bedrock; null = non si sa controllare. */
    private fun entities(v: Any?, rep: AdvancementModReport): List<String?>? {
        if (v == null) return listOf(null)
        val type = findKey(v, "type")
        if (type == null) { rep.ignored.add("creature: condizioni diverse dal tipo"); return listOf(null) }
        val out = LinkedHashSet<String>()
        fun add(x: Any?, depth: Int) {
            if (depth > 8) return
            when (x) {
                is String -> if (x.startsWith("#")) {
                    val rl = Rl.parse(x.substring(1)) ?: return
                    entityTags[rl]?.forEach { add((it as? Map<*, *>)?.get("id") ?: it, depth + 1) } ?: VANILLA_ENTITY_TAGS[rl.toString()]?.forEach { add(it, depth + 1) }
                } else Rl.parse(x)?.let { rl -> JavaEntities.bedrock(rl, modEntity)?.let { out.add(it) } }
                is List<*> -> x.forEach { add(it, depth + 1) }
            }
        }
        add(type, 0)
        return if (out.isEmpty()) null else out.toList()
    }

    private fun placedBlock(cond: Map<String, Any?>, rep: AdvancementModReport): List<String>? {
        (cond["block"] as? String)?.let { return blockNames(it).toList().ifEmpty { null } }
        val loc = cond["location"]
        val b = findKey(loc, "block") ?: return emptyList()
        val names = when (b) {
            is String -> blockNames(b)
            is Map<*, *> -> (b["blocks"] ?: b["block"])?.let { x -> if (x is List<*>) x.flatMap { blockNames(it as? String ?: "") }.toSet() else blockNames(x as? String ?: "") }
                ?: run { rep.ignored.add("blocchi: condizioni diverse dal tipo"); emptySet() }
            else -> emptySet()
        }
        return if (names.isEmpty()) null else names.toList()
    }

    private fun blockNames(v: String): Set<String> {
        if (v.startsWith("#")) {
            val rl = Rl.parse(v.substring(1)) ?: return emptySet()
            return JavaTags.blockTags[rl.toString()].orEmpty().flatMap { blockNames(it) }.toSet()
        }
        val rl = Rl.parse(v) ?: return emptySet()
        return if (rl.ns == "minecraft") JavaBlocks.names(rl.toString()) else setOfNotNull(modBlock(rl))
    }

    /** La posizione (bioma, dimensione, altezza, blocco) di una condizione «location»; false se non si sa controllare. */
    private fun location(v: Any?, out: MutableMap<String, Any?>, rep: AdvancementModReport): Boolean {
        val loc = findKey(v, "location") as? Map<*, *> ?: return v == null
        val biome = loc["biomes"] ?: loc["biome"]
        if (biome != null) {
            val r = biomes.resolve(if (biome is String && !biome.startsWith("#")) biome else biome)
            if (r.bedrock.isEmpty()) { rep.triggers.add("biomi delle mod"); return false }
            out["bi"] = r.bedrock.toList()
        }
        (loc["dimension"] as? String)?.let { d -> out["dm"] = BiomeResolver.dimension(d) ?: run { rep.triggers.add("dimensioni delle mod"); return false } }
        val y = (loc["position"] as? Map<*, *>)?.get("y")
        when (y) {
            is Number -> out["y"] = listOf(y.toDouble(), y.toDouble())
            is Map<*, *> -> out["y"] = listOf((y["min"] as? Number)?.toDouble() ?: -1e9, (y["max"] as? Number)?.toDouble() ?: 1e9)
        }
        (loc["block"] as? Map<*, *>)?.let { b -> out["b"] = (b["blocks"] ?: b["block"])?.let { x -> if (x is List<*>) x.flatMap { blockNames(it as? String ?: "") } else blockNames(x as? String ?: "").toList() } }
        val st = loc["structures"] ?: loc["structure"]
        if (st != null) {
            val ids = LinkedHashSet<String>()
            fun add(x: Any?, depth: Int) {
                if (depth > 8) return
                when (x) {
                    is String -> if (x.startsWith("#")) Rl.parse(x.substring(1))?.let { rl -> structureTags[rl]?.forEach { add((it as? Map<*, *>)?.get("id") ?: it, depth + 1) } }
                        else Rl.parse(x)?.let { ids.add(it.toString()) }
                    is List<*> -> x.forEach { add(it, depth + 1) }
                }
            }
            add(st, 0)
            val known = ids.filter { it in structures }
            if (known.isEmpty()) { rep.triggers.add("dentro una struttura che non nasce in Bedrock"); return false }
            out["st"] = known
        }
        for (k in loc.keys) if (k !in setOf("biomes", "biome", "dimension", "position", "block", "structures", "structure")) rep.ignored.add("posizione: «$k»")
        return true
    }

    /** Il primo valore con la chiave [key] dentro una condizione (elenco di condizioni, predicato o «entity_properties»). */
    private fun findKey(v: Any?, key: String, depth: Int = 0): Any? {
        if (depth > 6) return null
        return when (v) {
            is Map<*, *> -> v[key] ?: (v["predicate"] ?: v["entity"])?.let { findKey(it, key, depth + 1) }
            is List<*> -> v.firstNotNullOfOrNull { findKey(it, key, depth + 1) }
            else -> null
        }
    }

    private fun lootPath(t: String): String? {
        val rl = Rl.parse(t) ?: return null
        if (rl.ns == "minecraft") return "loot_tables/${rl.path}.json"
        val path = "loot_tables/${rl.ns}/${rl.path}.json"
        if (path in lootOut) return path
        for (mod in mods) for (folder in listOf("loot_table", "loot_tables")) {
            val o = mod.jar.json("data/${rl.ns}/$folder/${rl.path}.json", MAX_JSON).asJsonObject() ?: continue
            lootOut[path] = lootConv.convert(o).table
            return path
        }
        return null
    }

    companion object {
        private const val MAX_JSON = 4 * 1024 * 1024
        private val ADVANCEMENT = Regex("""data/([a-z0-9_.-]+)/(advancements?)/([a-z0-9_./-]+)\.json""")
        private val ENTITY_TAG = Regex("""data/([a-z0-9_.-]+)/tags/entity_types?/([a-z0-9_./-]+)\.json""")
        private val STRUCTURE_TAG = Regex("""data/([a-z0-9_.-]+)/tags/worldgen/structure/([a-z0-9_./-]+)\.json""")
        /** Effetti con un nome diverso in Bedrock. */
        private val EFFECTS = mapOf("hero_of_the_village" to "village_hero")
        private val VANILLA_ENTITY_TAGS = mapOf(
            "minecraft:skeletons" to listOf("minecraft:skeleton", "minecraft:stray", "minecraft:wither_skeleton", "minecraft:skeleton_horse", "minecraft:bogged"),
            "minecraft:raiders" to listOf("minecraft:evoker", "minecraft:pillager", "minecraft:ravager", "minecraft:vindicator", "minecraft:illusioner", "minecraft:witch"),
            "minecraft:undead" to listOf("minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:zombie_villager", "minecraft:skeleton", "minecraft:stray", "minecraft:wither_skeleton", "minecraft:phantom", "minecraft:zombified_piglin", "minecraft:wither", "minecraft:zoglin", "minecraft:bogged"),
            "minecraft:zombies" to listOf("minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:zombie_villager", "minecraft:zombified_piglin", "minecraft:zoglin"),
            "minecraft:illager" to listOf("minecraft:evoker", "minecraft:pillager", "minecraft:vindicator", "minecraft:illusioner")
        )
        /** Avvisi come quelli di Java («Progresso fatto!», «Obiettivo raggiunto!», «Sfida completata!») e i messaggi in chat. */
        private val TOAST = mapOf(
            "en_US" to mapOf(
                "mmi.adv.toast.task" to "Advancement Made!", "mmi.adv.toast.goal" to "Goal Reached!", "mmi.adv.toast.challenge" to "Challenge Complete!",
                "mmi.adv.chat.task" to "%s has made the advancement %s", "mmi.adv.chat.goal" to "%s has reached the goal %s",
                "mmi.adv.chat.challenge" to "%s has completed the challenge %s", "mmi.adv.title" to "Advancements"
            ),
            "it_IT" to mapOf(
                "mmi.adv.toast.task" to "Progresso fatto!", "mmi.adv.toast.goal" to "Obiettivo raggiunto!", "mmi.adv.toast.challenge" to "Sfida completata!",
                "mmi.adv.chat.task" to "%s ha fatto il progresso %s", "mmi.adv.chat.goal" to "%s ha raggiunto l'obiettivo %s",
                "mmi.adv.chat.challenge" to "%s ha completato la sfida %s", "mmi.adv.title" to "Progressi"
            )
        )
    }
}

/** Le righe del rapporto sui progressi di una mod. */
internal object AdvancementReport {
    fun merge(r: ModReport, a: AdvancementModReport): ModReport {
        if (a.total == 0) return r
        val scripted = ArrayList(r.scripted)
        val simplified = ArrayList(r.simplified)
        val notConverted = ArrayList(r.notConverted.filterNot { it.startsWith("Progressi:") })
        val ok = a.full + a.partial
        if (ok > 0) {
            scripted.add(
                "Progressi rifatti con lo script: $ok su ${a.total} (stesse condizioni, avviso e messaggio in chat come in Java" +
                    (if (a.xp + a.loot > 0) ", ricompense di esperienza e bottini" else "") + "; l'elenco si apre con il comando /mmi:progressi)"
            )
            simplified.add("Progressi: non stanno nel menu dei progressi di Bedrock (gli add-on non possono aggiungerne) ma nell'elenco di /mmi:progressi")
        }
        if (a.partial > 0) simplified.add("Progressi con qualche condizione controllata in parte: ${a.partial}")
        if (a.ignored.isNotEmpty()) simplified.add("Progressi: dettagli delle condizioni non controllati: " + a.ignored.take(5).joinToString(", "))
        if (a.functions > 0) notConverted.add("Ricompense dei progressi fatte da funzioni di Java: ${a.functions}")
        if (a.impossible.isNotEmpty()) notConverted.add(
            "Progressi che si ottengono solo con eventi del codice della mod o di Java che Bedrock non segnala: ${a.impossible.size} (" +
                a.impossible.take(5).joinToString(", ") + (if (a.impossible.size > 5) "…" else "") + ")" +
                (if (a.triggers.isNotEmpty()) "; eventi: " + a.triggers.take(6).joinToString(", ") { it.removePrefix("minecraft:") } + (if (a.triggers.size > 6) "…" else "") else "")
        )
        val components = r.components.map { c ->
            if (c.kind == "file" && c.originalId == "Progressi" && ok > 0) Component(
                c.kind, c.originalId, c.originalFunction, c.dependencies, "rifatti con la Script API: condizioni, avviso, ricompense, elenco con /mmi:progressi",
                null, "si ottengono come in Java", c.tests, listOf("non nel menu dei progressi di Bedrock", "non ottenibili: ${a.impossible.size}"),
                ComponentStatus.RECONSTRUCTED, c.count
            ) else c
        }
        return ModReport(
            r.label, r.title, r.loader, r.version, r.items, r.blocks, r.recipesConverted, r.recipesTotal, r.recipeSkips,
            r.lootTables, r.ores, r.armorModels, r.entities, r.estimates, simplified, notConverted, r.error, r.codeSummary, r.fromCode,
            components, scripted
        )
    }
}
