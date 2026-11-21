package com.mmi.modinstaller.convert

import com.mmi.modinstaller.core.Json
import com.mmi.modinstaller.core.asJsonList
import com.mmi.modinstaller.core.asJsonObject
import java.util.zip.GZIPInputStream

/** Gruppi (tag) di Minecraft Java già espansi: biomi e blocchi; e i biomi di Bedrock che corrispondono a quelli di Java. */
internal object JavaTags {
    private fun load(path: String, gz: Boolean): Map<String, Any?> {
        val input = JavaTags::class.java.getResourceAsStream(path) ?: return emptyMap()
        val text = input.use { raw -> (if (gz) GZIPInputStream(raw) else raw).readBytes().toString(Charsets.UTF_8) }
        return Json.parse(text).asJsonObject().orEmpty()
    }

    private fun strings(o: Any?): Map<String, List<String>> =
        o.asJsonObject().orEmpty().mapValues { (_, v) -> v.asJsonList().orEmpty().filterIsInstance<String>() }

    private val biomes by lazy { load("/com/mmi/modinstaller/convert/biomes.json", false) }
    val biomeTags: Map<String, List<String>> by lazy { strings(biomes["tags"]) }
    /** Bioma Java → biomi Bedrock (il principale e le sue varianti dei mondi vecchi). */
    val bedrockBiomes: Map<String, List<String>> by lazy { strings(biomes["bedrock"]) }
    val blockTags: Map<String, List<String>> by lazy { strings(load("/com/mmi/modinstaller/convert/java_block_tags.json.gz", true)["tags"]) }
}

/**
 * I biomi di Java (singoli, elenchi o gruppi «#tag» delle mod e di Minecraft, anche i gruppi comuni «c:» dei caricatori)
 * come biomi di Bedrock. I biomi delle mod in Bedrock non ci sono.
 */
internal class BiomeResolver(mods: List<JavaModContent>) {
    class Result(val bedrock: Set<String>, val modOnly: Set<String>, val missingTags: Set<String>)

    private val tags = HashMap<Rl, MutableList<Any?>>()

    init {
        for (mod in mods) for (n in mod.jar.allNames()) {
            val m = BIOME_TAG.matchEntire(n) ?: continue
            val o = mod.jar.json(n, 4 * 1024 * 1024).asJsonObject() ?: continue
            val list = tags.getOrPut(Rl(m.groupValues[1], m.groupValues[2])) { ArrayList() }
            if (o["replace"] == true) list.clear()
            list.addAll(o["values"].asJsonList().orEmpty())
        }
    }

    fun resolve(v: Any?): Result {
        val java = LinkedHashSet<String>()
        val missing = LinkedHashSet<String>()
        collect(v, java, HashSet(), missing, 0)
        val out = LinkedHashSet<String>()
        val modOnly = LinkedHashSet<String>()
        for (b in java) {
            val bed = JavaTags.bedrockBiomes[b]
            if (bed != null) out.addAll(bed) else if (!b.startsWith("minecraft:")) modOnly.add(b)
        }
        return Result(out, modOnly, missing)
    }

    private fun collect(v: Any?, out: MutableSet<String>, seen: MutableSet<String>, missing: MutableSet<String>, depth: Int) {
        if (depth > 16) return
        when (v) {
            is String -> if (v.startsWith("#")) tag(v.substring(1), out, seen, missing, depth) else Rl.parse(v)?.let { out.add(it.toString()) }
            is List<*> -> for (x in v) collect(x, out, seen, missing, depth + 1)
            is Map<*, *> -> collect(v["id"], out, seen, missing, depth + 1)
        }
    }

    private fun tag(t: String, out: MutableSet<String>, seen: MutableSet<String>, missing: MutableSet<String>, depth: Int) {
        val rl = Rl.parse(t) ?: return
        if (!seen.add(rl.toString())) return
        var found = false
        JavaTags.biomeTags[rl.toString()]?.let { out.addAll(it); found = true }
        tags[rl]?.let { for (x in it) collect(x, out, seen, missing, depth + 1); found = true }
        // Gruppi comuni dei caricatori (c:, forge:, neoforge:): quelli con lo stesso nome di Minecraft.
        if (!found && rl.ns in CONVENTION) {
            val name = CONVENTION_NAMES[rl.path] ?: rl.path
            JavaTags.biomeTags["minecraft:$name"]?.let { out.addAll(it); found = true }
            CONVENTION_BIOMES[rl.path]?.let { list -> out.addAll(list.map { "minecraft:$it" }); found = true }
        }
        if (!found) missing.add(rl.toString())
    }

    companion object {
        private val BIOME_TAG = Regex("""data/([a-z0-9_.-]+)/tags/worldgen/biome/([a-z0-9_./-]+)\.json""")
        private val CONVENTION = setOf("c", "forge", "neoforge", "fabric")
        private val CONVENTION_NAMES = mapOf("is_underground" to "is_cave", "is_caves" to "is_cave", "is_mountains" to "is_mountain", "is_hills" to "is_hill")
        /** Gruppi comuni che Minecraft non ha con lo stesso nome (i biomi di Minecraft che contengono). */
        private val CONVENTION_BIOMES: Map<String, List<String>> = StructureBuilder.CONVENTION_BIOMES

        /** Dimensione di Java → Bedrock (quelle delle mod non ci sono). */
        fun dimension(id: String?): String? = when (Rl.parse(id)?.toString()) {
            "minecraft:overworld" -> "minecraft:overworld"
            "minecraft:the_nether" -> "minecraft:nether"
            "minecraft:the_end" -> "minecraft:the_end"
            else -> null
        }
    }
}

/** Quello che si è fatto delle strutture di una mod (per il rapporto). */
internal class StructureModReport {
    /** File `.mcstructure` scritti (pezzi). */
    var files = 0
    /** Strutture del mondo nei dati della mod (worldgen/structure). */
    var worldgen = 0
    /** Strutture del mondo che lo script genera. */
    val generated = LinkedHashSet<String>()
    /** Pezzi usati dalla generazione. */
    val pieces = LinkedHashSet<String>()
    val codeTypes = LinkedHashSet<String>()
    val unknownBlocks = LinkedHashSet<String>()
    val modProcessors = LinkedHashSet<String>()
    val modElements = LinkedHashSet<String>()
    val features = LinkedHashSet<String>()
    val modBiomes = LinkedHashSet<String>()
    val missing = LinkedHashSet<String>()
    val placement = LinkedHashSet<String>()
    val noSet = LinkedHashSet<String>()
    val entities = LinkedHashSet<String>()
    var spawned = 0
    var containers = 0
    var multiPalette = 0
    var adapted = 0
    var terrainFollowing = 0
    val unreadable = LinkedHashSet<String>()
    val approximated = LinkedHashSet<String>()
}

/** Le strutture convertite di tutte le mod. */
internal class StructureOut(
    /** Percorso nel pacchetto di comportamento → file `.mcstructure`. */
    val files: Map<String, ByteArray>,
    /** Bottini delle casse (tabelle Bedrock). */
    val loot: Map<String, Map<String, Any?>>,
    /** Dati per lo script che genera le strutture (null se nessuna si genera da sola). */
    val data: Map<String, Any?>?,
    val reports: Map<JavaModContent, StructureModReport>
) {
    val isEmpty: Boolean get() = files.isEmpty()
}

/**
 * Legge le strutture delle mod come le legge Minecraft Java: i pezzi (`structure/<nome>.nbt`), le strutture del mondo
 * (`worldgen/structure`), dove compaiono (`worldgen/structure_set`), i gruppi di pezzi (`worldgen/template_pool`), le modifiche
 * ai blocchi (`worldgen/processor_list`) e i gruppi di biomi. Ogni pezzo diventa un file `.mcstructure`; il resto diventa i dati
 * dello script `mmi_structures.js`, che monta le strutture con lo stesso algoritmo di Java.
 */
internal class StructureBuilder(
    private val mods: List<JavaModContent>,
    /** Blocchi delle mod convertiti: (id Java, proprietà) → stato Bedrock. */
    private val modBlock: (Rl, Map<String, String>) -> BedrockState?,
    /** Il blocco Bedrock (nome) di un blocco della mod. */
    private val modBlockName: (Rl) -> String?,
    private val item: (Rl) -> String?,
    /** Creature delle mod convertite: id Java → id Bedrock. */
    private val modEntity: (Rl) -> String?,
    private val lootConv: LootConverter,
    /** Si può usare la Script API (Minecraft 1.21.90 o più nuovo)? Senza, i pezzi restano piazzabili a mano. */
    private val generate: Boolean,
    private val cancel: () -> Unit
) {
    private class Src(val mod: JavaModContent, val entry: String)

    private val templateSrc = LinkedHashMap<Rl, Src>()
    private val structureSrc = LinkedHashMap<Rl, Pair<JavaModContent, Map<String, Any?>>>()
    private val setSrc = LinkedHashMap<Rl, Pair<JavaModContent, Map<String, Any?>>>()
    private val poolSrc = LinkedHashMap<Rl, Map<String, Any?>>()
    private val procSrc = LinkedHashMap<Rl, Any?>()
    private val blockTagSrc = HashMap<Rl, MutableList<Any?>>()
    private val lootSrc = HashMap<Rl, Src>()
    private val reports = LinkedHashMap<JavaModContent, StructureModReport>()

    private val files = LinkedHashMap<String, ByteArray>()
    private val lootOut = LinkedHashMap<String, Map<String, Any?>>()
    private val converter = StructureConverter(modBlock, item, ::entity, ::lootPath)
    private val biomeResolver = BiomeResolver(mods)

    private val tplIndex = HashMap<String, Int>()
    private val tplData = ArrayList<LinkedHashMap<String, Any?>>()
    private val tplConv = ArrayList<ConvertedTemplate>()
    private val tplRl = ArrayList<Rl>()
    private val converted = HashSet<Rl>()
    private val poolsOut = LinkedHashMap<String, Map<String, Any?>>()
    private val poolQueue = ArrayDeque<Pair<String, StructureModReport>>()
    private val procIndex = HashMap<String, Int>()
    private val procOut = ArrayList<Any?>()
    private val structIndex = HashMap<Rl, Int>()
    private val structOut = ArrayList<Map<String, Any?>>()
    private val setOut = ArrayList<LinkedHashMap<String, Any?>>()

    fun build(): StructureOut {
        for (mod in mods) {
            reports[mod] = StructureModReport()
            for (n in mod.jar.allNames()) {
                TEMPLATE.matchEntire(n)?.let { m -> templateSrc.putIfAbsent(Rl(m.groupValues[1], m.groupValues[2]), Src(mod, n)) }
                LOOT.matchEntire(n)?.let { m -> lootSrc.putIfAbsent(Rl(m.groupValues[1], m.groupValues[2]), Src(mod, n)) }
                val wg = WORLDGEN.matchEntire(n)
                if (wg != null) {
                    val rl = Rl(wg.groupValues[1], wg.groupValues[3])
                    val o = mod.jar.json(n, MAX_JSON) ?: continue
                    when (wg.groupValues[2]) {
                        "structure" -> o.asJsonObject()?.let { structureSrc.putIfAbsent(rl, mod to it) }
                        "structure_set" -> o.asJsonObject()?.let { setSrc.putIfAbsent(rl, mod to it) }
                        "template_pool" -> o.asJsonObject()?.let { poolSrc.putIfAbsent(rl, it) }
                        "processor_list" -> procSrc.putIfAbsent(rl, o)
                    }
                    continue
                }
                BLOCK_TAG.matchEntire(n)?.let { m ->
                    val o = mod.jar.json(n, MAX_JSON).asJsonObject() ?: return@let
                    blockTagSrc.getOrPut(Rl(m.groupValues[1], m.groupValues[2])) { ArrayList() }.addAll(o["values"].asJsonList().orEmpty())
                }
            }
        }
        for ((_, p) in structureSrc) reports.getValue(p.first).worldgen++
        if (generate) {
            for ((id, p) in structureSrc) { cancel(); structure(id, p.first, p.second) }
            while (poolQueue.isNotEmpty()) { cancel(); val (name, rep) = poolQueue.removeFirst(); pool(name, rep) }
            for ((id, p) in setSrc) set(id, p.first, p.second)
            resolveExclusions()
            // Strutture che nessun «structure_set» fa comparire: in Java le piazza il codice della mod (o un comando).
            val inSet = setOut.flatMap { s -> (s["s"] as List<*>).map { ((it as List<*>)[0] as Number).toInt() } }.toSet()
            for ((rl, i) in structIndex) if (i !in inSet) structureSrc[rl]?.let { reports.getValue(it.first).noSet.add(rl.path) }
        }
        // Tutti gli altri pezzi: piazzabili con /structure load (in Java li usa il codice della mod o un comando).
        for ((rl, src) in templateSrc) {
            if (rl in converted) continue
            cancel()
            template(rl, emptySet(), reports.getValue(src.mod), false)
        }
        val data = if (generate && structOut.isNotEmpty()) linkedMapOf<String, Any?>(
            "t" to tplData, "p" to poolsOut, "r" to procOut, "s" to structOut, "g" to setOut
        ) else null
        return StructureOut(files, lootOut, data, reports)
    }

    // ---- strutture del mondo
    private fun structure(id: Rl, mod: JavaModContent, o: Map<String, Any?>) {
        val rep = reports.getValue(mod)
        val type = o["type"] as? String ?: "?"
        val startPool = (o["start_pool"] as? String)?.let { StructureConverter.norm(it) }
        if (startPool == null) { rep.codeTypes.add(id.path); return }
        if (type != "minecraft:jigsaw") rep.approximated.add(id.path + ": tipo «" + type + "» della mod letto come jigsaw")
        missingTags.clear()
        var (bedrock, modOnly) = biomes(o["biomes"])
        if (bedrock.isEmpty() && modOnly.isEmpty() && missingTags.isNotEmpty()) {
            // Il gruppo di biomi non è nel JAR: la mod lo crea dal codice (di solito dalla configurazione). Si usa l'Overworld.
            bedrock = biomes("#minecraft:is_overworld").first
            rep.approximated.add(id.path + ": biomi decisi dal codice della mod (#" + missingTags.first() + "), si usa tutto l'Overworld")
        }
        if (bedrock.isEmpty()) rep.modBiomes.add(id.path + if (modOnly.isNotEmpty()) " (" + modOnly.take(3).joinToString(", ") + ")" else "")
        enqueuePool(startPool, rep)
        val dist = o["max_distance_from_center"]
        val (h, v) = when (dist) {
            is Number -> dist.toLong() to dist.toLong()
            is Map<*, *> -> ((dist["horizontal"] as? Number)?.toLong() ?: 80L).let { hz -> hz to ((dist["vertical"] as? Number)?.toLong() ?: hz) }
            else -> 80L to 80L
        }
        val adapt = when (o["terrain_adaptation"] as? String) { "beard_thin" -> 1L; "beard_box" -> 2L; "bury" -> 3L; "encapsulate" -> 4L; else -> 0L }
        if (adapt == 1L || adapt == 2L) rep.adapted++
        if (o["pool_aliases"].asJsonList()?.isNotEmpty() == true) rep.approximated.add(id.path + ": alias dei gruppi di pezzi ignorati")
        val entry = linkedMapOf<String, Any?>(
            "id" to id.toString(), "p" to startPool, "n" to (o["start_jigsaw_name"] as? String)?.let { StructureConverter.norm(it) },
            "d" to ((o["size"] as? Number)?.toLong() ?: 0L).coerceIn(0L, 20L),
            "h" to height(o["start_height"]), "m" to heightmap(o["project_start_to_heightmap"] as? String),
            "x" to h, "xv" to v, "b" to bedrock.toList(), "a" to adapt,
            "w" to (o["liquid_settings"] != "ignore_waterlogging"), "e" to (o["use_expansion_hack"] == true)
        )
        structIndex[id] = structOut.size
        structOut.add(entry)
        rep.generated.add(id.path)
    }

    /** I biomi Bedrock in cui la struttura può nascere, e quelli delle mod (che in Bedrock non ci sono). */
    private fun biomes(v: Any?): Pair<Set<String>, Set<String>> {
        val r = biomeResolver.resolve(v)
        missingTags.addAll(r.missingTags)
        return r.bedrock to r.modOnly
    }

    private val missingTags = LinkedHashSet<String>()

    // ---- gruppi di pezzi
    private fun enqueuePool(name: String, rep: StructureModReport) {
        if (name == EMPTY || name in poolsOut || poolQueue.any { it.first == name }) return
        poolQueue.add(name to rep)
    }

    private fun pool(name: String, rep: StructureModReport) {
        if (name in poolsOut) return
        val rl = Rl.parse(name)
        val o = rl?.let { poolSrc[it] }
        if (o == null) {
            rep.missing.add(name)
            poolsOut[name] = linkedMapOf("f" to EMPTY, "e" to emptyList<Any?>())
            return
        }
        poolsOut[name] = linkedMapOf("f" to EMPTY, "e" to emptyList<Any?>()) // contro i giri infiniti
        val fallback = StructureConverter.norm(o["fallback"] as? String)
        val els = ArrayList<List<Any?>>()
        for (e in o["elements"].asJsonList().orEmpty()) {
            val eo = e.asJsonObject() ?: continue
            val w = ((eo["weight"] as? Number)?.toLong() ?: 1L).coerceIn(1L, 150L)
            val el = element(eo["element"].asJsonObject() ?: continue, rep) ?: continue
            els.add(listOf(el[0], el[1], w, el[2], el[3]))
        }
        poolsOut[name] = linkedMapOf("f" to fallback, "e" to els)
        enqueuePool(fallback, rep)
    }

    /** [tipo (0 pezzo, 1 elenco, 2 decorazione, 3 vuoto), riferimento, processori, proiezione (0 rigida, 1 segue il terreno)]. */
    private fun element(o: Map<String, Any?>, rep: StructureModReport): List<Any?>? {
        val type = (o["element_type"] as? String) ?: "minecraft:single_pool_element"
        val proj = if (o["projection"] == "terrain_matching") 1L else 0L
        when (type) {
            "minecraft:empty_pool_element" -> return listOf(3L, 0L, -1L, 0L)
            "minecraft:feature_pool_element" -> {
                val f = (o["feature"] as? String) ?: "decorazione scritta dentro il gruppo di pezzi"
                rep.features.add(f)
                return listOf(2L, f, -1L, proj)
            }
            "minecraft:list_pool_element" -> {
                val subs = ArrayList<Long>()
                var proc = -1L
                for (x in o["elements"].asJsonList().orEmpty()) {
                    val sub = x.asJsonObject() ?: continue
                    val r = element(sub, rep) ?: continue
                    if (r[0] == 0L) { subs.add(r[1] as Long); if (proc < 0) proc = r[2] as Long }
                }
                if (subs.isEmpty()) return null
                if (proj == 1L) rep.terrainFollowing++
                return listOf(1L, subs, proc, proj)
            }
        }
        val location = o["location"] as? String
        if (location == null) { rep.modElements.add(type); return null }
        if (type != "minecraft:single_pool_element" && type != "minecraft:legacy_single_pool_element") rep.modElements.add(type)
        val rl = Rl.parse(location) ?: return null
        val pr = processors(o["processors"], rep)
        val ignore = LinkedHashSet(pr.ignore)
        if (type == "minecraft:legacy_single_pool_element") ignore.add("minecraft:air")
        val cells = proj == 1L || pr.cells
        if (cells) rep.terrainFollowing++
        val idx = template(rl, ignore, rep, cells) ?: return null
        rep.pieces.add(rl.toString())
        return listOf(0L, idx.toLong(), pr.index, proj)
    }

    // ---- pezzi (.nbt → .mcstructure)
    private fun template(rl: Rl, ignore: Set<String>, rep: StructureModReport, cells: Boolean): Int? {
        val key = rl.toString() + "|" + ignore.sorted().joinToString(",")
        tplIndex[key]?.let { i -> if (cells) addCells(i); return i }
        val src = templateSrc[rl]
        if (src == null) { rep.missing.add(rl.toString()); return null }
        val srep = reports.getValue(src.mod)
        val bytes = src.mod.jar.bytes(src.entry, MAX_NBT)
        val nbt = bytes?.let { try { JavaNbt.read(it) } catch (e: Exception) { null } }
        val conv = nbt?.let { try { converter.convert(it, ignore) } catch (e: Exception) { null } }
        if (conv == null) { srep.unreadable.add(rl.path); return null }
        srep.unknownBlocks.addAll(conv.unknown)
        srep.containers += conv.containers
        if (conv.multiPalette) srep.multiPalette++
        val variants = tplIndex.keys.count { it.startsWith(rl.toString() + "|") }
        val ns = if (rl.ns == "minecraft") "mmi_minecraft" else rl.ns
        val path = rl.path + if (variants == 0) "" else "__$variants"
        files["structures/$ns/$path.mcstructure"] = conv.bytes
        srep.files++
        converted.add(rl)
        val ents = ArrayList<List<Any?>>()
        for (e in conv.entities) {
            val id = Rl.parse(e.id)?.let { entity(it) }
            if (id == null) { srep.entities.add(e.id); continue }
            ents.add(listOf(e.x, e.y, e.z, id))
            srep.spawned++
        }
        val entry = linkedMapOf<String, Any?>(
            "i" to "$ns:$path", "s" to conv.size.map { it.toLong() },
            "j" to conv.jigsaws.map { j ->
                listOf(j.x.toLong(), j.y.toLong(), j.z.toLong(), j.front.toLong(), j.top.toLong(), j.pool, j.name, j.target,
                    if (j.rollable) 1L else 0L, j.selectionPriority.toLong(), j.placementPriority.toLong())
            },
            "e" to ents
        )
        val i = tplData.size
        tplIndex[key] = i
        tplData.add(entry)
        tplConv.add(conv)
        tplRl.add(rl)
        if (cells) addCells(i)
        if (generate) for (j in conv.jigsaws) enqueuePool(j.pool, rep)
        return i
    }

    private fun addCells(i: Int) {
        val entry = tplData[i]
        if (entry.containsKey("b")) return
        val conv = tplConv[i]
        entry["b"] = linkedMapOf(
            "p" to conv.palette.map { listOf(it.name, statesJson(it)) },
            "l" to conv.cells.map { it.toLong() }
        )
    }

    private fun statesJson(s: BedrockState): Map<String, Any?> = s.states.associate { (k, v) ->
        k to when (v) { is BTag.B -> v.v != 0; is BTag.I -> v.v.toLong(); is BTag.Str -> v.v; else -> null }
    }

    // ---- processori
    private class Procs(val index: Long, val ignore: Set<String>, val cells: Boolean)

    private fun processors(v: Any?, rep: StructureModReport): Procs {
        val list: List<Any?> = when (v) {
            is String -> {
                val rl = Rl.parse(v)
                val o = rl?.let { procSrc[it] }
                if (o == null && rl != null && rl.toString() != EMPTY) rep.missing.add(rl.toString())
                procList(o)
            }
            else -> procList(v)
        }
        val ignore = LinkedHashSet<String>()
        var cells = false
        val spec = ArrayList<Any?>()
        for (p in list) {
            val m = p.asJsonObject() ?: continue
            when (val t = m["processor_type"] as? String) {
                "minecraft:block_ignore" -> for (b in m["blocks"].asJsonList().orEmpty()) (b.asJsonObject()?.get("Name") as? String)?.let { ignore.add(it) }
                "minecraft:gravity" -> { cells = true; spec.add(gravity(m)) }
                "minecraft:jigsaw_replacement", "minecraft:nop", "minecraft:lava_submerged_block", null -> {}
                else -> processor(m, rep)?.let { spec.add(it) } ?: rep.modProcessors.add(t)
            }
        }
        if (spec.isEmpty()) return Procs(-1, ignore, cells)
        val key = Json.stringify(spec, pretty = false)
        val idx = procIndex.getOrPut(key) { procOut.add(spec); procOut.size - 1 }
        return Procs(idx.toLong(), ignore, cells)
    }

    private fun procList(o: Any?): List<Any?> = when (o) {
        is Map<*, *> -> (o["processors"] as? List<*>).orEmpty()
        is List<*> -> o
        else -> emptyList()
    }

    private fun gravity(m: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "k" to "g", "h" to (heightmap(m["heightmap"] as? String) ?: 0L), "o" to ((m["offset"] as? Number)?.toLong() ?: 0L)
    )

    private fun processor(m: Map<String, Any?>, rep: StructureModReport): Map<String, Any?>? = when (m["processor_type"] as? String) {
        "minecraft:rule" -> {
            val rules = m["rules"].asJsonList().orEmpty().mapNotNull { r -> r.asJsonObject()?.let { rule(it, rep) } }
            if (rules.isEmpty()) null else linkedMapOf("k" to "r", "r" to rules)
        }
        "minecraft:block_rot" -> linkedMapOf<String, Any?>("k" to "rot", "v" to ((m["integrity"] as? Number)?.toDouble() ?: 1.0)).also { out ->
            m["rottable_blocks"]?.let { b -> out["b"] = blockNames(b).toList() }
        }
        "minecraft:capped" -> {
            val d = m["delegate"].asJsonObject()?.let { processor(it, rep) }
            if (d == null) null else linkedMapOf("k" to "cap", "n" to intProvider(m["limit"]), "d" to d)
        }
        "minecraft:protected_blocks" -> linkedMapOf("k" to "pr", "b" to blockNames(m["value"]).toList())
        else -> null
    }

    private fun rule(r: Map<String, Any?>, rep: StructureModReport): Map<String, Any?>? {
        val input = predicate(r["input_predicate"]) ?: return null.also { rep.modProcessors.add("regola: " + predicateType(r["input_predicate"])) }
        val loc = predicate(r["location_predicate"] ?: mapOf("predicate_type" to "minecraft:always_true"))
            ?: return null.also { rep.modProcessors.add("regola: " + predicateType(r["location_predicate"])) }
        val out = r["output_state"].asJsonObject() ?: return null
        val name = out["Name"] as? String ?: return null
        @Suppress("UNCHECKED_CAST")
        val props = (out["Properties"] as? Map<String, Any?>).orEmpty().mapValues { it.value.toString() }
        val st = converter.resolve(name, props, HashSet())
        val bem = r["block_entity_modifier"].asJsonObject()?.get("type") as? String
        if (bem != null && bem != "minecraft:passthrough") rep.approximated.add("dati dei blocchi cambiati dalle regole («$bem») non applicati")
        return linkedMapOf(
            "i" to input.takeIf { it["t"] != "*" }, "l" to loc.takeIf { it["t"] != "*" },
            "q" to posPredicate(r["position_predicate"]), "o" to listOf(st.name, statesJson(st))
        )
    }

    private fun predicateType(o: Any?): String = (o.asJsonObject()?.get("predicate_type") as? String) ?: "?"

    private fun predicate(o: Any?): Map<String, Any?>? {
        val m = o.asJsonObject() ?: return null
        val p = (m["probability"] as? Number)?.toDouble()
        val out: LinkedHashMap<String, Any?> = when (m["predicate_type"] as? String) {
            "minecraft:always_true" -> return mapOf("t" to "*")
            "minecraft:block_match", "minecraft:random_block_match" -> linkedMapOf("t" to "b", "b" to blockNames(m["block"]).toList())
            "minecraft:tag_match" -> linkedMapOf("t" to "b", "b" to blockNames("#" + (m["tag"] as? String ?: "")).toList())
            "minecraft:blockstate_match", "minecraft:random_blockstate_match" -> {
                val bs = m["block_state"].asJsonObject() ?: return null
                @Suppress("UNCHECKED_CAST")
                val props = (bs["Properties"] as? Map<String, Any?>).orEmpty().mapValues { it.value.toString() }
                val st = converter.resolve(bs["Name"] as? String ?: return null, props, HashSet())
                linkedMapOf("t" to "s", "n" to st.name, "s" to statesJson(st))
            }
            else -> return null
        }
        if (p != null) out["p"] = p
        return out
    }

    private fun posPredicate(o: Any?): Map<String, Any?>? {
        val m = o.asJsonObject() ?: return null
        val t = m["predicate_type"] as? String
        if (t != "minecraft:axis_aligned_linear_pos" && t != "minecraft:linear_pos") return null
        val out = linkedMapOf<String, Any?>(
            "t" to if (t == "minecraft:linear_pos") "lin" else "ax",
            "c0" to ((m["min_chance"] as? Number)?.toDouble() ?: 0.0), "c1" to ((m["max_chance"] as? Number)?.toDouble() ?: 0.0),
            "d0" to ((m["min_dist"] as? Number)?.toLong() ?: 0L), "d1" to ((m["max_dist"] as? Number)?.toLong() ?: 0L)
        )
        if (t != "minecraft:linear_pos") out["a"] = when (m["axis"]) { "x" -> 0L; "z" -> 2L; else -> 1L }
        return out
    }

    /** I nomi Bedrock di un blocco, di un elenco o di un gruppo («#tag») di blocchi di Java. */
    private fun blockNames(v: Any?, depth: Int = 0): Set<String> {
        val out = LinkedHashSet<String>()
        if (depth > 12) return out
        when (v) {
            is String -> if (v.startsWith("#")) {
                val rl = Rl.parse(v.substring(1)) ?: return out
                JavaTags.blockTags[rl.toString()]?.forEach { out.addAll(blockNames(it, depth + 1)) }
                blockTagSrc[rl]?.forEach { x -> out.addAll(blockNames((x as? Map<*, *>)?.get("id") ?: x, depth + 1)) }
            } else {
                val rl = Rl.parse(v) ?: return out
                if (rl.ns == "minecraft") out.addAll(JavaBlocks.names(rl.toString())) else modBlockName(rl)?.let { out.add(it) }
            }
            is List<*> -> for (x in v) out.addAll(blockNames(x, depth + 1))
            is Map<*, *> -> out.addAll(blockNames(v["Name"] ?: v["id"], depth + 1))
        }
        return out
    }

    // ---- altezze e numeri di Java
    private fun anchor(o: Any?): List<Any?> = when {
        o is Number -> listOf("a", o.toLong())
        o is Map<*, *> && o["absolute"] is Number -> listOf("a", (o["absolute"] as Number).toLong())
        o is Map<*, *> && o["above_bottom"] is Number -> listOf("b", (o["above_bottom"] as Number).toLong())
        o is Map<*, *> && o["below_top"] is Number -> listOf("t", (o["below_top"] as Number).toLong())
        else -> listOf("a", 0L)
    }

    private fun height(o: Any?): List<Any?> {
        val m = o as? Map<*, *> ?: return listOf("c", anchor(o))
        return when ((m["type"] as? String)?.removePrefix("minecraft:")) {
            null -> listOf("c", anchor(m))
            "constant" -> listOf("c", anchor(m["value"]))
            "uniform" -> listOf("u", anchor(m["min_inclusive"]), anchor(m["max_inclusive"]))
            "biased_to_bottom" -> listOf("bb", anchor(m["min_inclusive"]), anchor(m["max_inclusive"]), ((m["inner"] as? Number)?.toLong() ?: 1L))
            "very_biased_to_bottom" -> listOf("vb", anchor(m["min_inclusive"]), anchor(m["max_inclusive"]), ((m["inner"] as? Number)?.toLong() ?: 1L))
            "trapezoid" -> listOf("tz", anchor(m["min_inclusive"]), anchor(m["max_inclusive"]), ((m["plateau"] as? Number)?.toLong() ?: 0L))
            "weighted_list" -> listOf("w", m["distribution"].asJsonList().orEmpty().mapNotNull { e ->
                val eo = e.asJsonObject() ?: return@mapNotNull null
                listOf(height(eo["data"]), ((eo["weight"] as? Number)?.toLong() ?: 1L))
            })
            else -> listOf("c", anchor(m["min_inclusive"] ?: m["value"]))
        }
    }

    private fun intProvider(o: Any?): Any? {
        if (o is Number) return o.toLong()
        val m = o as? Map<*, *> ?: return 1L
        val v = (m["value"] as? Map<*, *>) ?: m
        val lo = (v["min_inclusive"] as? Number)?.toLong()
        val hi = (v["max_inclusive"] as? Number)?.toLong()
        return if (lo != null && hi != null) listOf("u", lo, hi) else ((m["value"] as? Number)?.toLong() ?: lo ?: 1L)
    }

    private fun heightmap(t: String?): Long? = when (t) {
        null -> null
        "OCEAN_FLOOR", "OCEAN_FLOOR_WG" -> 1L
        else -> 0L
    }

    // ---- dove compaiono
    private fun set(id: Rl, mod: JavaModContent, o: Map<String, Any?>) {
        val rep = reports.getValue(mod)
        val entries = ArrayList<List<Long>>()
        for (e in o["structures"].asJsonList().orEmpty()) {
            val m = e.asJsonObject() ?: continue
            val sid = Rl.parse(m["structure"] as? String) ?: continue
            val idx = structIndex[sid] ?: continue
            entries.add(listOf(idx.toLong(), ((m["weight"] as? Number)?.toLong() ?: 1L).coerceAtLeast(1L)))
        }
        if (entries.isEmpty()) return
        val pl = o["placement"].asJsonObject() ?: return
        val type = pl["type"] as? String
        val out = linkedMapOf<String, Any?>("id" to id.toString(), "s" to entries)
        when {
            type == "minecraft:concentric_rings" -> {
                out["t"] = 1L
                out["rd"] = ((pl["distance"] as? Number)?.toLong() ?: 32L)
                out["rs"] = ((pl["spread"] as? Number)?.toLong() ?: 3L).coerceAtLeast(1L)
                out["rc"] = ((pl["count"] as? Number)?.toLong() ?: 128L).coerceIn(1L, 4096L)
                rep.approximated.add(id.path + ": anelli attorno all'origine, senza cercare il bioma vicino")
            }
            pl["spacing"] is Number -> {
                if (type != "minecraft:random_spread") rep.approximated.add(id.path + ": posizionamento «" + type + "» della mod letto come random_spread")
                val sp = (pl["spacing"] as Number).toLong().coerceIn(1L, 4096L)
                out["t"] = 0L
                out["sp"] = sp
                out["se"] = ((pl["separation"] as? Number)?.toLong() ?: 0L).coerceIn(0L, sp - 1)
                out["tr"] = pl["spread_type"] == "triangular"
            }
            else -> { rep.placement.add(id.path + " (" + type + ")"); return }
        }
        out["sa"] = (pl["salt"] as? Number)?.toLong() ?: 0L
        out["f"] = (pl["frequency"] as? Number)?.toDouble() ?: 1.0
        out["fm"] = when (pl["frequency_reduction_method"]) { "legacy_type_1" -> 1L; "legacy_type_2" -> 2L; "legacy_type_3" -> 3L; else -> 0L }
        val ex = pl["exclusion_zone"].asJsonObject()
        if (ex != null) out["exs"] = listOf(ex["other_set"] as? String, ((ex["chunk_count"] as? Number)?.toLong() ?: 0L))
        out["ex"] = null
        setOut.add(out)
    }

    private fun resolveExclusions() {
        val byId = setOut.mapIndexed { i, s -> s["id"] as String to i }.toMap()
        for (s in setOut) {
            val ex = s.remove("exs") as? List<*> ?: continue
            val other = Rl.parse(ex[0] as? String)?.toString() ?: continue
            val idx = byId[other]
            if (idx != null) { s["ex"] = listOf(idx.toLong(), ex[1]); continue }
            val mod = Rl.parse(s["id"] as String)?.let { setSrc[it]?.first } ?: continue
            reports.getValue(mod).approximated.add((s["id"] as String).substringAfter(':') + ": la distanza da «$other» (di Minecraft o di un'altra mod) non si controlla")
        }
    }

    // ---- creature e bottini
    private fun lootPath(t: String): String? {
        val rl = Rl.parse(t) ?: return null
        if (rl.ns == "minecraft") return "loot_tables/${rl.path}.json"
        val path = "loot_tables/${rl.ns}/${rl.path}.json"
        if (path in lootOut) return path
        val src = lootSrc[rl] ?: return null
        val o = src.mod.jar.json(src.entry, MAX_JSON).asJsonObject() ?: return null
        lootOut[path] = lootConv.convert(o).table
        return path
    }

    private fun entity(rl: Rl): String? = JavaEntities.bedrock(rl, modEntity)
    companion object {
        const val EMPTY = "minecraft:empty"
        private const val MAX_JSON = 4 * 1024 * 1024
        private const val MAX_NBT = 64 * 1024 * 1024
        private val TEMPLATE = Regex("""data/([a-z0-9_.-]+)/structures?/([a-z0-9_./-]+)\.nbt""")
        private val WORLDGEN = Regex("""data/([a-z0-9_.-]+)/worldgen/(structure|structure_set|template_pool|processor_list)/([a-z0-9_./-]+)\.json""")
        private val BLOCK_TAG = Regex("""data/([a-z0-9_.-]+)/tags/blocks?/([a-z0-9_./-]+)\.json""")
        private val LOOT = Regex("""data/([a-z0-9_.-]+)/loot_tables?/([a-z0-9_./-]+)\.json""")
        /** Gruppi comuni che Minecraft non ha con lo stesso nome (i biomi di Minecraft che contengono). */
        internal val CONVENTION_BIOMES = mapOf(
            "is_plains" to listOf("plains", "sunflower_plains"),
            "is_desert" to listOf("desert"),
            "is_swamp" to listOf("swamp", "mangrove_swamp"),
            "is_mushroom" to listOf("mushroom_fields"),
            "is_snowy" to listOf("snowy_plains", "ice_spikes", "snowy_taiga", "snowy_beach", "grove", "snowy_slopes", "jagged_peaks", "frozen_peaks", "frozen_river", "frozen_ocean", "deep_frozen_ocean"),
            "is_icy" to listOf("ice_spikes", "frozen_peaks"),
            "is_cold" to listOf("snowy_plains", "ice_spikes", "snowy_taiga", "snowy_beach", "grove", "snowy_slopes", "jagged_peaks", "frozen_peaks", "frozen_river", "frozen_ocean", "deep_frozen_ocean", "taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga", "windswept_hills", "windswept_gravelly_hills", "windswept_forest", "stony_shore", "cold_ocean", "deep_cold_ocean"),
            "is_hot" to listOf("desert", "badlands", "eroded_badlands", "wooded_badlands", "savanna", "savanna_plateau", "windswept_savanna", "jungle", "sparse_jungle", "bamboo_jungle", "mangrove_swamp", "warm_ocean", "nether_wastes", "crimson_forest", "warped_forest", "soul_sand_valley", "basalt_deltas"),
            "is_dry" to listOf("desert", "badlands", "eroded_badlands", "wooded_badlands", "savanna", "savanna_plateau", "windswept_savanna", "nether_wastes", "crimson_forest", "warped_forest", "soul_sand_valley", "basalt_deltas"),
            "is_wet" to listOf("swamp", "mangrove_swamp", "jungle", "sparse_jungle", "bamboo_jungle", "lush_caves", "beach", "river"),
            "is_temperate" to listOf("plains", "sunflower_plains", "forest", "flower_forest", "birch_forest", "old_growth_birch_forest", "dark_forest", "meadow", "cherry_grove", "swamp", "beach", "river", "ocean", "deep_ocean", "lukewarm_ocean", "deep_lukewarm_ocean"),
            "is_flower_forest" to listOf("flower_forest"),
            "is_birch_forest" to listOf("birch_forest", "old_growth_birch_forest"),
            "is_dark_forest" to listOf("dark_forest", "pale_garden"),
            "is_old_growth" to listOf("old_growth_birch_forest", "old_growth_pine_taiga", "old_growth_spruce_taiga"),
            "is_windswept" to listOf("windswept_hills", "windswept_gravelly_hills", "windswept_forest", "windswept_savanna"),
            "is_stony_shores" to listOf("stony_shore"),
            "is_aquatic" to listOf("ocean", "deep_ocean", "cold_ocean", "deep_cold_ocean", "frozen_ocean", "deep_frozen_ocean", "lukewarm_ocean", "deep_lukewarm_ocean", "warm_ocean", "river", "frozen_river"),
            "is_spooky" to listOf("dark_forest", "deep_dark", "pale_garden"),
            "is_lush" to listOf("lush_caves"),
            "is_magical" to listOf("cherry_grove"),
            "is_sparse_vegetation" to listOf("sparse_jungle", "savanna", "savanna_plateau", "windswept_savanna", "badlands", "eroded_badlands", "wooded_badlands", "desert"),
            "is_dense_vegetation" to listOf("jungle", "bamboo_jungle", "dark_forest", "pale_garden", "mangrove_swamp"),
            "is_coniferous_tree" to listOf("taiga", "snowy_taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga", "grove", "windswept_forest"),
            "is_deciduous_tree" to listOf("forest", "flower_forest", "birch_forest", "old_growth_birch_forest", "dark_forest", "windswept_forest"),
            "is_jungle_tree" to listOf("jungle", "sparse_jungle", "bamboo_jungle"),
            "is_savanna_tree" to listOf("savanna", "savanna_plateau", "windswept_savanna"),
            "is_mountain_peak" to listOf("jagged_peaks", "frozen_peaks", "stony_peaks"),
            "is_mountain_slope" to listOf("snowy_slopes", "meadow", "grove", "cherry_grove"),
            "is_void" to listOf("the_void"),
            "is_nether_forest" to listOf("crimson_forest", "warped_forest"),
            "is_outer_end_island" to listOf("end_highlands", "end_midlands", "end_barrens"),
            "is_end" to listOf("the_end", "end_highlands", "end_midlands", "small_end_islands", "end_barrens")
        )
    }
}

/** Le creature di Minecraft Java con il nome di Bedrock (quelle delle mod si chiedono a [modEntity]). */
internal object JavaEntities {
    fun bedrock(rl: Rl, modEntity: (Rl) -> String?): String? {
        if (rl.ns != "minecraft") return modEntity(rl)
        return when (rl.path) {
            "villager" -> "minecraft:villager_v2"
            "zombie_villager" -> "minecraft:zombie_villager_v2"
            "evoker" -> "minecraft:evocation_illager"
            "end_crystal" -> "minecraft:ender_crystal"
            "snow_golem" -> "minecraft:snow_golem"
            "item_frame", "glow_item_frame", "painting", "leash_knot", "marker", "item", "experience_orb", "falling_block",
            "area_effect_cloud", "interaction", "block_display", "item_display", "text_display", "illusioner" -> null
            else -> rl.toString()
        }
    }

}

/** Le righe del rapporto sulle strutture di una mod. */
internal object StructureReport {
    private fun short(it: String): String = if (it.contains(' ')) it else it.substringAfter(':').substringAfterLast('/')

    private fun list(items: Collection<String>, max: Int = 8): String =
        items.take(max).joinToString(", ") { short(it) } + if (items.size > max) " e altre ${items.size - max}" else ""

    /** [generating]: si può usare la Script API (la generazione nel mondo è possibile). */
    fun merge(r: ModReport, s: StructureModReport, generating: Boolean): ModReport {
        if (s.files == 0 && s.worldgen == 0) return r
        val scripted = ArrayList(r.scripted)
        val simplified = ArrayList(r.simplified)
        val notConverted = ArrayList(r.notConverted.filterNot { it.startsWith("Strutture:") })
        val live = generating && s.generated.isNotEmpty()
        if (live) {
            scripted.add(
                "Strutture che nascono nel mondo come in Java (stessi pezzi, gruppi, pesi, rotazioni, biomi, altezze e distanze): " +
                    "${s.generated.size} (" + list(s.generated) + "), con ${s.pieces.size} pezzi"
            )
            if (s.containers > 0) scripted.add("Casse e contenitori delle strutture con il loro bottino: ${s.containers}")
            simplified.add("Strutture: compaiono appena la zona si carica vicino a te (Bedrock non lascia agli add-on aggiungerle mentre crea il terreno)")
            simplified.add("Strutture in un mondo che esisteva già: non compaiono entro 512 blocchi da dove eri quando hai aggiunto il pacchetto (per non coprire le tue costruzioni); con il comando /scriptevent mmi:strutture_ovunque compaiono anche lì")
            if (s.adapted > 0) simplified.add("Terreno sotto le strutture: riempito colonna per colonna fino al suolo (in Java si raccorda con una pendenza)")
            if (s.terrainFollowing > 0) simplified.add("Pezzi che seguono il terreno (sentieri, strade): piazzati blocco per blocco sul suolo")
            if (s.spawned > 0) simplified.add("Creature dentro le strutture: compaiono senza i dati salvati nel pezzo (equipaggiamento, mestiere, nome)")
            if (s.approximated.isNotEmpty()) simplified.add("Strutture con regole speciali seguite in modo approssimato: " + list(s.approximated, 5))
            if (s.modElements.isNotEmpty()) simplified.add("Pezzi di tipo speciale della mod usati come pezzi normali: " + list(s.modElements, 5))
        }
        if (s.files > 0) simplified.add(
            "Pezzi delle strutture convertiti: ${s.files} (si piazzano anche a mano con /structure load <mod>:<nome>" +
                (if (live) ", e le strutture intere con /scriptevent mmi:struttura <mod>:<nome>)" else ")")
        )
        if (s.multiPalette > 0) simplified.add("Strutture con più varianti di blocchi: si usa sempre la prima (${s.multiPalette})")
        if (!generating && s.worldgen > 0) notConverted.add("Strutture che nascono da sole nel mondo: servono Minecraft 1.21.90 o più nuovo")
        if (s.codeTypes.isNotEmpty()) notConverted.add("Strutture fatte dal codice Java della mod, non dai dati: " + list(s.codeTypes))
        if (s.noSet.isNotEmpty() && live) simplified.add("Strutture che la mod piazza dal codice (non nascono da sole): si mettono con /scriptevent mmi:struttura: " + list(s.noSet, 5))
        if (s.modBiomes.isNotEmpty()) notConverted.add("Strutture che in Java nascono solo nei biomi delle mod (gli add-on Bedrock non possono aggiungere biomi): " + list(s.modBiomes))
        if (s.unknownBlocks.isNotEmpty()) notConverted.add("Blocchi delle strutture che non ci sono nel pacchetto (al loro posto aria): " + list(s.unknownBlocks))
        if (s.modProcessors.isNotEmpty()) notConverted.add("Modifiche ai blocchi delle strutture scritte nel codice della mod: " + list(s.modProcessors, 5))
        if (s.features.isNotEmpty()) notConverted.add("Decorazioni generate dentro le strutture (alberi, lampioni…): " + list(s.features, 5))
        if (s.missing.isNotEmpty()) notConverted.add("Pezzi o gruppi di pezzi che stanno in altre mod o in Minecraft Java: " + list(s.missing, 5))
        if (s.entities.isNotEmpty()) notConverted.add("Creature delle strutture che non ci sono in Bedrock: " + list(s.entities, 5))
        if (s.placement.isNotEmpty()) notConverted.add("Posizioni delle strutture decise dal codice della mod: " + list(s.placement, 5))
        if (s.unreadable.isNotEmpty()) notConverted.add("Pezzi delle strutture illeggibili o troppo grandi: " + list(s.unreadable, 5))
        val components = r.components.map { c ->
            when {
                c.kind == "file" && c.originalId == "Strutture" -> Component(
                    c.kind, c.originalId, c.originalFunction, c.dependencies,
                    if (live) "pezzi in .mcstructure; lo script le genera nel mondo con l'algoritmo jigsaw di Java" else "pezzi in .mcstructure",
                    null, if (live) "nascono nel mondo come in Java" else "si piazzano con /structure load", c.tests,
                    listOfNotNull(
                        "compaiono quando la zona si carica".takeIf { live },
                        "strutture fatte dal codice: ${s.codeTypes.size}".takeIf { s.codeTypes.isNotEmpty() },
                        "blocchi mancanti: ${s.unknownBlocks.size}".takeIf { s.unknownBlocks.isNotEmpty() }
                    ),
                    if (live) ComponentStatus.RECONSTRUCTED else ComponentStatus.APPROXIMATED, c.count
                )
                c.kind == "file" && c.originalId.startsWith("Generazione del mondo") && live -> Component(
                    c.kind, c.originalId, c.originalFunction, c.dependencies, "minerali e strutture convertiti; biomi e dimensioni no", c.bedrockId,
                    c.bedrockFunction, c.tests, listOf("gli add-on Bedrock non possono aggiungere biomi e dimensioni"), ComponentStatus.APPROXIMATED, c.count
                )
                else -> c
            }
        }
        return ModReport(
            r.label, r.title, r.loader, r.version, r.items, r.blocks, r.recipesConverted, r.recipesTotal, r.recipeSkips,
            r.lootTables, r.ores, r.armorModels, r.entities, r.estimates, simplified, notConverted, r.error, r.codeSummary, r.fromCode,
            components, scripted
        )
    }
}
