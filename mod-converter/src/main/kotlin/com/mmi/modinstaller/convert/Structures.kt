package com.mmi.modinstaller.convert

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.zip.GZIPInputStream

/** NBT di Java (big-endian, di solito compresso con gzip): i file delle strutture `.nbt`. Non esegue nulla. */
internal object JavaNbt {
    class TooLarge : Exception()

    /** La radice come mappa; liste → List, compound → Map, array → IntArray/LongArray/ByteArray. */
    fun read(bytes: ByteArray, maxBytes: Int = 64 * 1024 * 1024): Map<String, Any?>? {
        val raw = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw TooLarge()
                }
                out.toByteArray()
            }
        } else bytes
        val d = DataInputStream(ByteArrayInputStream(raw))
        val type = d.readByte().toInt()
        if (type != 10) return null
        d.readUTF()
        @Suppress("UNCHECKED_CAST")
        return payload(d, 10, 0) as Map<String, Any?>
    }

    private fun payload(d: DataInputStream, t: Int, depth: Int): Any? {
        if (depth > 64) throw IllegalStateException("NBT troppo annidato")
        return when (t) {
            1 -> d.readByte()
            2 -> d.readShort()
            3 -> d.readInt()
            4 -> d.readLong()
            5 -> d.readFloat()
            6 -> d.readDouble()
            7 -> ByteArray(d.readInt().also { require(it in 0..64_000_000) }).also { d.readFully(it) }
            8 -> d.readUTF()
            9 -> {
                val et = d.readByte().toInt()
                val n = d.readInt()
                require(n in 0..10_000_000)
                ArrayList<Any?>(minOf(n, 4096)).also { l -> repeat(n) { l.add(payload(d, et, depth + 1)) } }
            }
            10 -> {
                val m = LinkedHashMap<String, Any?>()
                while (true) {
                    val tt = d.readByte().toInt()
                    if (tt == 0) break
                    val k = d.readUTF()
                    m[k] = payload(d, tt, depth + 1)
                }
                m
            }
            11 -> IntArray(d.readInt().also { require(it in 0..64_000_000) }) { d.readInt() }
            12 -> LongArray(d.readInt().also { require(it in 0..16_000_000) }) { d.readLong() }
            else -> throw IllegalStateException("tag NBT $t")
        }
    }
}

/** Un valore NBT di Bedrock con il suo tipo (little-endian, per i file `.mcstructure`). */
internal sealed class BTag {
    class B(val v: Int) : BTag()
    class S(val v: Int) : BTag()
    class I(val v: Int) : BTag()
    class L(val v: Long) : BTag()
    class F(val v: Float) : BTag()
    class Str(val v: String) : BTag()
    class Lst(val type: Int, val items: List<BTag>) : BTag()
    class C(val map: LinkedHashMap<String, BTag> = LinkedHashMap()) : BTag() {
        operator fun set(k: String, v: BTag) { map[k] = v }
    }
    /** Una lista di interi (senza un oggetto per ogni numero: i pezzi grandi hanno milioni di blocchi). */
    class Ints(val v: IntArray) : BTag()

    val id: Int
        get() = when (this) {
            is B -> 1; is S -> 2; is I -> 3; is L -> 4; is F -> 5; is Str -> 8; is Lst -> 9; is Ints -> 9; is C -> 10
        }

    companion object {
        fun write(root: C): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(10); str(out, ""); body(out, root)
            return out.toByteArray()
        }

        private fun le(out: ByteArrayOutputStream, v: Long, n: Int) { for (i in 0 until n) out.write(((v shr (8 * i)) and 0xFF).toInt()) }

        private fun str(out: ByteArrayOutputStream, s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            le(out, b.size.toLong(), 2); out.write(b)
        }

        private fun body(out: ByteArrayOutputStream, t: BTag) {
            when (t) {
                is B -> out.write(t.v and 0xFF)
                is S -> le(out, t.v.toLong(), 2)
                is I -> le(out, t.v.toLong(), 4)
                is L -> le(out, t.v, 8)
                is F -> le(out, java.lang.Float.floatToIntBits(t.v).toLong(), 4)
                is Str -> str(out, t.v)
                is Lst -> {
                    out.write(if (t.items.isEmpty()) t.type else t.items[0].id)
                    le(out, t.items.size.toLong(), 4)
                    for (x in t.items) body(out, x)
                }
                is Ints -> {
                    out.write(3)
                    le(out, t.v.size.toLong(), 4)
                    for (x in t.v) le(out, x.toLong(), 4)
                }
                is C -> {
                    for ((k, v) in t.map) { out.write(v.id); str(out, k); body(out, v) }
                    out.write(0)
                }
            }
        }
    }
}

/** Un blocco Bedrock con gli stati tipizzati. */
internal class BedrockState(val name: String, val states: List<Pair<String, BTag>>) {
    val key: String by lazy { name + states.joinToString(",", "[", "]") { (k, v) -> k + "=" + text(v) } }

    private fun text(v: BTag): String = when (v) { is BTag.B -> v.v.toString(); is BTag.I -> v.v.toString(); is BTag.Str -> v.v; else -> "?" }

    companion object {
        val AIR = BedrockState("minecraft:air", emptyList())
        val VOID = BedrockState("minecraft:structure_void", emptyList())
        val WATER = BedrockState("minecraft:water", listOf("liquid_depth" to BTag.I(0)))
    }
}

/**
 * Ogni stato di blocco di Java con il blocco Bedrock corrispondente (tabella `java_blocks.txt.gz`, da PrismarineJS
 * minecraft-data, licenza MIT; tutti gli 11.553 stati Bedrock verificati nel server 1.26.62).
 */
internal object JavaBlocks {
    class Entry(val props: Map<String, String>, val bedrock: BedrockState)

    private val table: Map<String, List<Entry>> by lazy {
        val out = HashMap<String, MutableList<Entry>>()
        val input = JavaBlocks::class.java.getResourceAsStream("/com/mmi/modinstaller/convert/java_blocks.txt.gz")
        input?.use { raw ->
            GZIPInputStream(raw).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("#") || line.isBlank()) continue
                    val f = line.split('\t')
                    if (f.size < 4) continue
                    val props = if (f[1].isEmpty()) emptyMap() else f[1].split(',').associate { kv -> kv.substringBefore('=') to kv.substringAfter('=') }
                    val states = if (f[3].isEmpty()) emptyList() else f[3].split(',').map { kv ->
                        val k = kv.substringBefore('=')
                        val tv = kv.substringAfter('=')
                        val v = tv.substring(2)
                        k to when (tv[0]) {
                            'b' -> BTag.B(if (v == "true") 1 else 0)
                            'i' -> BTag.I(v.toInt())
                            else -> BTag.Str(v)
                        }
                    }
                    out.getOrPut(f[0]) { ArrayList() }.add(Entry(props, BedrockState(f[2], states)))
                }
            }
        }
        out
    }

    /** Nomi cambiati nelle versioni di Java (le strutture vecchie usano quelli di allora). */
    private val RENAMED = mapOf(
        "minecraft:grass" to "minecraft:short_grass", "minecraft:grass_path" to "minecraft:dirt_path",
        "minecraft:sign" to "minecraft:oak_sign", "minecraft:wall_sign" to "minecraft:oak_wall_sign",
        "minecraft:chain" to "minecraft:iron_chain"
    )

    fun has(name: String): Boolean = table.containsKey(name)

    private fun entries(name: String): List<Entry>? =
        table[name] ?: RENAMED[name]?.let { table[it] } ?: table.entries.firstOrNull { RENAMED[it.key] == name }?.value

    /** Tutti i blocchi Bedrock in cui può diventare un blocco di Java (con ognuno dei suoi stati). */
    fun names(name: String): Set<String> = entries(name).orEmpty().mapTo(LinkedHashSet()) { it.bedrock.name }

    /** Il blocco Bedrock per un blocco di Java con le sue proprietà (le mancanti valgono come in Java, la prima voce). */
    fun lookup(name: String, props: Map<String, String>): BedrockState? {
        val list = entries(name) ?: return null
        var best: Entry? = null
        var score = -1
        for (e in list) {
            var s = 0
            var conflict = false
            for ((k, v) in props) {
                val have = e.props[k] ?: continue
                if (have == v) s++ else { conflict = true; break }
            }
            if (conflict) continue
            if (s > score) { score = s; best = e }
            if (s == props.size) break
        }
        return (best ?: list.first()).bedrock
    }
}

/** Un connettore jigsaw del pezzo (coordinate nel pezzo non ruotato; direzioni come in Java: 0 giù, 1 su, 2 nord, 3 sud, 4 ovest, 5 est). */
internal class Jigsaw(
    val x: Int, val y: Int, val z: Int, val front: Int, val top: Int, val pool: String, val name: String, val target: String,
    val rollable: Boolean, val selectionPriority: Int, val placementPriority: Int
)

/** Un'entità salvata nella struttura di Java (posizione dentro il pezzo e id Java). */
internal class TemplateEntity(val x: Double, val y: Double, val z: Double, val id: String)

/** Una struttura di Java convertita: il file `.mcstructure` e quello che serve allo script per montarla. */
internal class ConvertedTemplate(
    val bytes: ByteArray,
    val size: IntArray,
    val jigsaws: List<Jigsaw>,
    /** Blocchi che in Bedrock non ci sono (es. di altre mod): al loro posto aria. */
    val unknown: Set<String>,
    /** Tabelle dei bottini delle casse (Java) usate nella struttura. */
    val loot: Set<String>,
    val entities: List<TemplateEntity>,
    val blocks: Int,
    /** I blocchi uno per uno (x, y, z, indice nella tavolozza), per i pezzi che seguono il terreno. */
    val cells: IntArray,
    val palette: List<BedrockState>,
    val containers: Int,
    val multiPalette: Boolean
)

/**
 * Converte una struttura di Java (`data/<mod>/structure/…nbt`) nel formato `.mcstructure` di Bedrock: ogni blocco con i suoi
 * stati (blocchi di Minecraft dalla tabella, blocchi della mod da quelli convertiti), l'acqua dei blocchi «waterlogged»,
 * casse, barili, distributori e tramogge con il loro bottino e gli oggetti dentro, cartelli con il testo, generatori di
 * creature, letti e stendardi con il colore, vasi con la pianta, i connettori jigsaw sostituiti dal loro blocco finale.
 */
internal class StructureConverter(
    /** Blocchi delle mod convertite: (id Java, proprietà) → stato Bedrock, o null se non c'è. */
    private val modBlock: (Rl, Map<String, String>) -> BedrockState?,
    /** Oggetti: id Java → id Bedrock (per gli oggetti dentro le casse). */
    private val item: (Rl) -> String?,
    /** Creature: id Java → id Bedrock (generatori di creature). */
    private val entity: (Rl) -> String?,
    /** Percorso Bedrock di una tabella di bottini di Java (es. «ns:chests/x» → «loot_tables/ns/chests/x.json»). */
    private val lootPath: (String) -> String?
) {
    /** [ignore]: blocchi di Java che il pezzo non piazza (processore «block_ignore», elementi «legacy»): restano quelli del mondo. */
    fun convert(nbt: Map<String, Any?>, ignore: Set<String> = emptySet()): ConvertedTemplate? {
        val size = (nbt["size"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }?.takeIf { it.size == 3 } ?: return null
        val (sx, sy, sz) = size
        if (sx <= 0 || sy <= 0 || sz <= 0 || sx.toLong() * sy * sz > 4_000_000) return null
        val palettes = nbt["palettes"] as? List<*>
        @Suppress("UNCHECKED_CAST")
        val palette = (nbt["palette"] as? List<Map<String, Any?>>) ?: (palettes?.firstOrNull() as? List<Map<String, Any?>>) ?: return null
        val blocks = (nbt["blocks"] as? List<*>).orEmpty()
        val unknown = LinkedHashSet<String>()
        val loot = LinkedHashSet<String>()
        val jigsaws = ArrayList<Jigsaw>()

        val bedrockPalette = ArrayList<BedrockState>()
        val paletteIndex = HashMap<String, Int>()
        fun idx(s: BedrockState): Int = paletteIndex.getOrPut(s.key) { bedrockPalette.add(s); bedrockPalette.size - 1 }
        val voidIdx = idx(BedrockState.VOID)
        class P(val idx: Int, val water: Boolean, val name: String, val props: Map<String, String>, val state: BedrockState)
        val javaPal = palette.map { p ->
            val name = (p["Name"] as? String)?.let { Rl.parse(it.trim())?.toString() } ?: "minecraft:air"
            @Suppress("UNCHECKED_CAST")
            val props = (p["Properties"] as? Map<String, Any?>).orEmpty().mapValues { it.value.toString() }
            val st = if (name in ignore) BedrockState.VOID else resolve(name, props, unknown)
            P(idx(st), props["waterlogged"] == "true" && name !in ignore, name, props, st)
        }
        val layer0 = IntArray(sx * sy * sz) { voidIdx }
        val layer1 = IntArray(sx * sy * sz) { -1 }
        val positionData = BTag.C()
        val cells = ArrayList<Int>()
        var count = 0
        var containers = 0
        for (b in blocks) {
            val m = b as? Map<*, *> ?: continue
            val pos = (m["pos"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: continue
            if (pos.size != 3) continue
            val (x, y, z) = pos
            if (x !in 0 until sx || y !in 0 until sy || z !in 0 until sz) continue
            val p = javaPal.getOrNull((m["state"] as? Number)?.toInt() ?: -1) ?: continue
            val i = (x * sy + y) * sz + z
            @Suppress("UNCHECKED_CAST")
            val be = m["nbt"] as? Map<String, Any?>
            if (p.name == "minecraft:jigsaw") {
                // Connettore: lo script lo usa per montare i pezzi; nel mondo diventa il suo blocco finale (di solito aria).
                val parts = (p.props["orientation"] ?: "north_up").split('_')
                val front = DIRS.indexOf(parts.getOrElse(0) { "north" }).takeIf { it >= 0 } ?: 2
                val top = DIRS.indexOf(parts.getOrElse(1) { "up" }).takeIf { it >= 0 } ?: 1
                val joint = be?.get("joint") as? String
                jigsaws.add(
                    Jigsaw(
                        x, y, z, front, top,
                        norm((be?.get("pool") ?: be?.get("target_pool")) as? String),
                        norm(be?.get("name") as? String),
                        norm((be?.get("target") ?: be?.get("attachement_type")) as? String),
                        if (joint != null) joint == "rollable" else front < 2,
                        (be?.get("selection_priority") as? Number)?.toInt() ?: 0,
                        (be?.get("placement_priority") as? Number)?.toInt() ?: 0
                    )
                )
                val fin = (be?.get("final_state") as? String) ?: "minecraft:air"
                val fn = Rl.parse(fin.substringBefore('[').trim())?.toString() ?: "minecraft:air"
                val fp = fin.substringAfter('[', "").removeSuffix("]").split(',').filter { it.contains('=') }
                    .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
                val st = if (fn in ignore) BedrockState.VOID else resolve(fn, fp, unknown)
                layer0[i] = idx(st)
                if (st !== BedrockState.VOID) { cells.add(x); cells.add(y); cells.add(z); cells.add(layer0[i]) }
                count++
                continue
            }
            layer0[i] = p.idx
            if (p.water) layer1[i] = idx(BedrockState.WATER)
            if (p.state !== BedrockState.VOID) { cells.add(x); cells.add(y); cells.add(z); cells.add(p.idx) }
            val data = blockEntity(p.name, p.props, p.state, be, x, y, z, loot)
            if (data != null) {
                positionData[i.toString()] = BTag.C(linkedMapOf("block_entity_data" to data))
                if ((data.map["id"] as? BTag.Str)?.v in CONTAINERS) containers++
            }
            count++
        }
        val entities = ArrayList<TemplateEntity>()
        for (e in (nbt["entities"] as? List<*>).orEmpty()) {
            val m = e as? Map<*, *> ?: continue
            val pos = (m["pos"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.size == 3 } ?: continue
            val id = ((m["nbt"] as? Map<*, *>)?.get("id") as? String) ?: continue
            entities.add(TemplateEntity(pos[0], pos[1], pos[2], id))
        }
        val root = BTag.C()
        root["format_version"] = BTag.I(1)
        root["size"] = BTag.Lst(3, listOf(BTag.I(sx), BTag.I(sy), BTag.I(sz)))
        val structure = BTag.C()
        structure["block_indices"] = BTag.Lst(9, listOf(BTag.Ints(layer0), BTag.Ints(layer1)))
        structure["entities"] = BTag.Lst(10, emptyList())
        val pal = BTag.C()
        pal["block_palette"] = BTag.Lst(10, bedrockPalette.map { s ->
            BTag.C(linkedMapOf("name" to BTag.Str(s.name), "states" to BTag.C(LinkedHashMap(s.states.toMap())), "version" to BTag.I(VERSION)))
        })
        pal["block_position_data"] = positionData
        structure["palette"] = BTag.C(linkedMapOf("default" to pal))
        root["structure"] = structure
        root["structure_world_origin"] = BTag.Lst(3, listOf(BTag.I(0), BTag.I(0), BTag.I(0)))
        return ConvertedTemplate(
            BTag.write(root), intArrayOf(sx, sy, sz), jigsaws, unknown, loot, entities, count, cells.toIntArray(), bedrockPalette,
            containers, (palettes?.size ?: 0) > 1
        )
    }

    fun resolve(name: String, props: Map<String, String>, unknown: MutableSet<String>): BedrockState {
        val rl = Rl.parse(name.trim()) ?: return BedrockState.AIR
        val full = rl.toString()
        if (full == "minecraft:structure_void") return BedrockState.VOID
        if (full == "minecraft:structure_block") return BedrockState.AIR
        if (rl.ns == "minecraft") JavaBlocks.lookup(full, props)?.let { return it }
        else modBlock(rl, props)?.let { return it }
        unknown.add(full)
        return BedrockState.AIR
    }

    /** I dati dei blocchi speciali (contenitori, cartelli, generatori, letti, vasi, stendardi). */
    private fun blockEntity(
        name: String, props: Map<String, String>, state: BedrockState, be: Map<String, Any?>?, x: Int, y: Int, z: Int, loot: MutableSet<String>
    ): BTag.C? {
        val short = name.substringAfter(':')
        val vanilla = name.startsWith("minecraft:")
        val c = BTag.C()
        fun base(id: String) { c["id"] = BTag.Str(id); c["x"] = BTag.I(x); c["y"] = BTag.I(y); c["z"] = BTag.I(z); c["isMovable"] = BTag.B(1) }
        val container = when {
            vanilla && (short == "chest" || short == "trapped_chest") -> "Chest"
            vanilla && short == "barrel" -> "Barrel"
            vanilla && short.endsWith("shulker_box") -> "ShulkerBox"
            vanilla && short == "dispenser" -> "Dispenser"
            vanilla && short == "dropper" -> "Dropper"
            vanilla && short == "hopper" -> "Hopper"
            else -> null
        }
        if (container != null) {
            base(container)
            if (container == "Chest") c["Findable"] = BTag.B(0)
            if (container == "ShulkerBox") c["facing"] = BTag.B(DIRS.indexOf(props["facing"] ?: "up").coerceAtLeast(0))
            (be?.get("LootTable") as? String)?.let { t ->
                lootPath(t)?.let { path ->
                    loot.add(t)
                    c["LootTable"] = BTag.Str(path)
                    c["LootTableSeed"] = BTag.I(((be["LootTableSeed"] as? Number)?.toLong() ?: 0L).toInt())
                }
            }
            c["Items"] = BTag.Lst(10, items(be?.get("Items")))
            return c
        }
        if (be == null && !(vanilla && (short.endsWith("_bed") || short.startsWith("potted_") || short.endsWith("banner")))) return null
        when {
            vanilla && short.endsWith("_bed") -> {
                base("Bed"); c["color"] = BTag.B(COLORS.indexOf(short.removeSuffix("_bed")).coerceAtLeast(0)); return c
            }
            vanilla && short.startsWith("potted_") -> {
                val plant = JavaBlocks.lookup("minecraft:" + short.removePrefix("potted_").let { POTTED[it] ?: it }, emptyMap()) ?: return null
                base("FlowerPot")
                c["PlantBlock"] = BTag.C(linkedMapOf("name" to BTag.Str(plant.name), "states" to BTag.C(LinkedHashMap(plant.states.toMap())), "version" to BTag.I(VERSION)))
                return c
            }
            vanilla && short.endsWith("banner") -> {
                base("Banner")
                val color = COLORS.indexOf(short.removeSuffix("_wall_banner").removeSuffix("_banner")).coerceAtLeast(0)
                c["Base"] = BTag.I(15 - color)
                return c
            }
            vanilla && short == "spawner" -> {
                val sd = be?.get("SpawnData") as? Map<*, *>
                val id = ((sd?.get("entity") as? Map<*, *>)?.get("id") ?: sd?.get("id")) as? String ?: return null
                val bid = Rl.parse(id)?.let { entity(it) } ?: return null
                base("MobSpawner")
                c["EntityIdentifier"] = BTag.Str(bid)
                c["Delay"] = BTag.S(((be?.get("Delay") as? Number)?.toInt() ?: 20))
                c["MinSpawnDelay"] = BTag.S(((be?.get("MinSpawnDelay") as? Number)?.toInt() ?: 200))
                c["MaxSpawnDelay"] = BTag.S(((be?.get("MaxSpawnDelay") as? Number)?.toInt() ?: 800))
                c["SpawnCount"] = BTag.S(((be?.get("SpawnCount") as? Number)?.toInt() ?: 4))
                c["MaxNearbyEntities"] = BTag.S(((be?.get("MaxNearbyEntities") as? Number)?.toInt() ?: 6))
                c["RequiredPlayerRange"] = BTag.S(((be?.get("RequiredPlayerRange") as? Number)?.toInt() ?: 16))
                c["SpawnRange"] = BTag.S(((be?.get("SpawnRange") as? Number)?.toInt() ?: 4))
                return c
            }
            vanilla && short.endsWith("sign") -> {
                base(if (short.endsWith("hanging_sign")) "HangingSign" else "Sign")
                c["FrontText"] = BTag.C(linkedMapOf("Text" to BTag.Str(signText(be, "front_text")), "SignTextColor" to BTag.I(-16777216)))
                c["BackText"] = BTag.C(linkedMapOf("Text" to BTag.Str(signText(be, "back_text")), "SignTextColor" to BTag.I(-16777216)))
                c["IsWaxed"] = BTag.B(if (be?.get("is_waxed") == 1.toByte()) 1 else 0)
                return c
            }
        }
        return null
    }

    private fun items(list: Any?): List<BTag> {
        val out = ArrayList<BTag>()
        for (it in (list as? List<*>).orEmpty()) {
            val m = it as? Map<*, *> ?: continue
            val bid = Rl.parse(m["id"] as? String)?.let { item(it) } ?: continue
            val n = ((m["count"] ?: m["Count"]) as? Number)?.toInt() ?: 1
            out.add(BTag.C(linkedMapOf(
                "Name" to BTag.Str(bid), "Count" to BTag.B(n.coerceIn(1, 64)), "Damage" to BTag.S(0),
                "Slot" to BTag.B(((m["Slot"] as? Number)?.toInt() ?: 0)), "WasPickedUp" to BTag.B(0)
            )))
        }
        return out
    }

    /** Il testo di un cartello di Java (righe in formato JSON) come testo semplice di Bedrock. */
    private fun signText(be: Map<String, Any?>?, side: String): String {
        val lines: List<Any?> = ((be?.get(side) as? Map<*, *>)?.get("messages") as? List<*>)
            ?: if (side == "front_text") (1..4).map { be?.get("Text$it") } else emptyList()
        return lines.map { plain(it) }.dropLastWhile { it.isEmpty() }.joinToString("\n")
    }

    private fun plain(v: Any?): String {
        val parsed: Any? = when (v) {
            is String -> if (v.startsWith("{") || v.startsWith("\"") || v.startsWith("[")) try { com.mmi.modinstaller.core.Json.parse(v) } catch (e: Exception) { v } else v
            else -> v
        }
        return when (parsed) {
            is String -> parsed
            is Map<*, *> -> (parsed["text"] as? String ?: "") + (parsed["extra"] as? List<*>).orEmpty().joinToString("") { plain(it) }
            is List<*> -> parsed.joinToString("") { plain(it) }
            else -> ""
        }
    }

    companion object {
        /** Un nome di Java con lo spazio dei nomi («stone» → «minecraft:stone»); vuoto → «minecraft:empty». */
        fun norm(v: String?): String = v?.trim()?.takeIf { it.isNotEmpty() }?.let { Rl.parse(it)?.toString() ?: it } ?: "minecraft:empty"

        /** Versione dei blocchi scritta nelle strutture (accettata dal server Bedrock 1.26.62). */
        const val VERSION = 18168865
        val DIRS = listOf("down", "up", "north", "south", "west", "east")
        val COLORS = listOf(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue",
            "brown", "green", "red", "black"
        )
        val CONTAINERS = setOf("Chest", "Barrel", "ShulkerBox", "Dispenser", "Dropper", "Hopper")
        private val POTTED = mapOf("azalea_bush" to "azalea", "flowering_azalea_bush" to "flowering_azalea")
    }
}
