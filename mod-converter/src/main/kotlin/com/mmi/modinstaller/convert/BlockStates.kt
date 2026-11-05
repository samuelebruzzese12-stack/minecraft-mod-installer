package com.mmi.modinstaller.convert

import com.mmi.modinstaller.core.asJsonList
import com.mmi.modinstaller.core.asJsonObject

/** Una variante del file «blockstates» di Java: il modello e la sua rotazione (multipli di 90°). */
data class JVariant(val model: Rl, val x: Int, val y: Int, val uvlock: Boolean)

/** La condizione «when» di una parte di un blocco multipart: proprietà → valori ammessi, oppure OR/AND di altre condizioni. */
class JWhen(val props: Map<String, Set<String>>, val or: List<JWhen>? = null, val and: List<JWhen>? = null) {
    fun matches(s: Map<String, String>): Boolean = when {
        or != null -> or.any { it.matches(s) }
        and != null -> and.all { it.matches(s) }
        else -> props.all { (k, v) -> s[k] in v }
    }
}

/** Il file «blockstates» di Java letto per intero (tutte le varianti o tutte le parti). */
class JBlockStates(
    /** Varianti: proprietà indicate nella chiave → variante (la prima se sono a caso). */
    val variants: List<Pair<Map<String, String>, JVariant>>,
    /** Multipart: condizione (null = sempre) → variante. */
    val parts: List<Pair<JWhen?, JVariant>>,
    /** Tutte le proprietà che il file nomina, con i valori visti. */
    val properties: Map<String, Set<String>>
) {
    companion object {
        fun parse(o: Map<String, Any?>): JBlockStates? {
            val props = LinkedHashMap<String, LinkedHashSet<String>>()
            fun variant(v: Any?): JVariant? {
                val m = when (v) { is Map<*, *> -> v; is List<*> -> v.firstOrNull() as? Map<*, *>; else -> null } ?: return null
                val model = Rl.parse(m["model"] as? String) ?: return null
                return JVariant(model, ((m["x"] as? Number)?.toInt() ?: 0).mod(360), ((m["y"] as? Number)?.toInt() ?: 0).mod(360), m["uvlock"] == true)
            }
            val variants = ArrayList<Pair<Map<String, String>, JVariant>>()
            o["variants"].asJsonObject()?.forEach { (key, v) ->
                val map = LinkedHashMap<String, String>()
                for (kv in key.split(',')) {
                    val i = kv.indexOf('=')
                    if (i <= 0) continue
                    val k = kv.substring(0, i).trim(); val value = kv.substring(i + 1).trim()
                    map[k] = value
                    props.getOrPut(k) { LinkedHashSet() }.add(value)
                }
                variant(v)?.let { variants.add(map to it) }
            }
            fun cond(w: Any?, depth: Int = 0): JWhen? {
                val m = w.asJsonObject() ?: return null
                if (depth > 8) return null
                m["OR"].asJsonList()?.let { l -> return JWhen(emptyMap(), or = l.mapNotNull { cond(it, depth + 1) }) }
                m["AND"].asJsonList()?.let { l -> return JWhen(emptyMap(), and = l.mapNotNull { cond(it, depth + 1) }) }
                val p = LinkedHashMap<String, Set<String>>()
                for ((k, v) in m) {
                    val values = v.toString().split('|').map { it.trim() }.toSet()
                    p[k] = values
                    props.getOrPut(k) { LinkedHashSet() }.addAll(values)
                }
                return JWhen(p)
            }
            val parts = ArrayList<Pair<JWhen?, JVariant>>()
            o["multipart"].asJsonList()?.forEach { p ->
                val pm = p.asJsonObject() ?: return@forEach
                val w = pm["when"]?.let { cond(it) }
                val apply = pm["apply"]
                if (apply is List<*>) apply.firstOrNull()?.let { a -> variant(a)?.let { parts.add(w to it) } }
                else variant(apply)?.let { parts.add(w to it) }
            }
            if (variants.isEmpty() && parts.isEmpty()) return null
            return JBlockStates(variants, parts, props)
        }
    }
}

/** Uno stato di un blocco Bedrock (dei tratti di Minecraft o nostro, «mmi:…») con i valori che può avere. */
class BState(val name: String, val values: List<Any>, val custom: Boolean)

/** Un pezzo della geometria del blocco: si vede solo in alcuni stati (condizione Molang). */
class StateBone(val name: String, val elements: List<JElement>, val visible: String?)

/** Il blocco con più stati pronto per Bedrock (formato 1.26.40): geometria a pezzi, stati, tratti, varianti, comportamenti. */
class StatefulBlock(
    val family: BlockFamily,
    val bones: List<StateBone>,
    val textures: Map<String, Rl>,
    val tinted: Boolean,
    /** Stati nostri da dichiarare in «description.states». */
    val customStates: Map<String, List<Any>>,
    /** «description.traits». */
    val traits: Map<String, Any?>,
    /** Componenti di base (collisione, selezione e quelli della famiglia). */
    val base: Map<String, Any?>,
    /** Varianti: condizione Molang → componenti. */
    val permutations: List<Pair<String, Map<String, Any?>>>,
    /** Cosa resta diverso da Java (per il rapporto), o null. */
    val note: String?,
    /** Stato Java «di partenza» (per i bottini). */
    val defaultState: Map<String, String>
)

enum class BlockFamily(val label: String) {
    SLAB("lastra"), STAIRS("scala"), DOOR("porta"), TRAPDOOR("botola"), FENCE_GATE("cancello"), FENCE("staccionata"),
    WALL("muretto"), PANE("pannello"), BUTTON("pulsante"), PRESSURE_PLATE("pedana"), CROP("coltura")
}

/**
 * BLOCKBRIDGE — blocchi con più stati. Legge il file «blockstates» di Java per intero e lo ricostruisce con gli strumenti
 * nativi di Bedrock 1.26.40 (provati nel server ufficiale): tratti di piazzamento (verso, metà alta/bassa, angoli delle
 * scale), blocchi a più parti (porte), collegamenti (staccionate, muretti, pannelli), segnale di pietrarossa (pulsanti,
 * pedane), più riquadri di collisione alti fino a 1,5 blocchi; e per quello che Bedrock non fa da sé (aprire e chiudere,
 * lastre doppie, colture che crescono) i comportamenti della Script API ([Behaviors]).
 *
 * L'aspetto di ogni stato è quello di Java: ogni variante (o parte) diventa un pezzo della geometria con la rotazione
 * di Java già applicata, visibile solo negli stati Bedrock che corrispondono a quella variante.
 */
object StateEngine {
    const val FORMAT = "1.26.40"
    val MIN_ENGINE = listOf(1L, 26L, 40L)

    private const val CARD = "minecraft:cardinal_direction"
    private const val HALF = "minecraft:vertical_half"
    private const val FACE = "minecraft:block_face"
    private const val CORNER = "minecraft:corner"
    private const val PART = "minecraft:multi_block_part"
    private val SIDES = listOf("north", "east", "south", "west")
    private val CARDINALS = listOf("north", "south", "east", "west")

    /** La famiglia del blocco dalle proprietà Java (e dal ruolo letto nel codice, se c'è). */
    fun family(s: JBlockStates, role: String?, id: Rl): BlockFamily? {
        val p = s.properties
        fun has(k: String, vararg v: String) = p[k]?.containsAll(v.toList()) == true
        return when {
            has("type", "bottom", "top", "double") -> BlockFamily.SLAB
            has("half", "lower", "upper") && p.containsKey("open") && p.containsKey("facing") -> BlockFamily.DOOR
            has("half", "bottom", "top") && p.containsKey("shape") && p.containsKey("facing") -> BlockFamily.STAIRS
            has("half", "bottom", "top") && p.containsKey("open") && p.containsKey("facing") -> BlockFamily.TRAPDOOR
            p.containsKey("in_wall") && p.containsKey("open") && p.containsKey("facing") -> BlockFamily.FENCE_GATE
            p.containsKey("up") && (p["north"]?.any { it == "low" || it == "tall" } == true) -> BlockFamily.WALL
            s.parts.isNotEmpty() && SIDES.all { p[it]?.containsAll(listOf("true", "false")) == true || p[it]?.contains("true") == true } ->
                if (role == "pane" || id.path.contains("pane") || id.path.contains("bars")) BlockFamily.PANE else BlockFamily.FENCE
            p.containsKey("face") && p.containsKey("facing") && p.containsKey("powered") -> BlockFamily.BUTTON
            (p.keys == setOf("powered") || p.keys == setOf("power")) && (role == "pressure_plate" || id.path.contains("pressure_plate") || id.path.contains("plate")) ->
                BlockFamily.PRESSURE_PLATE
            // Colture vere (in Java derivano da CropBlock): crescono sulla terra arata.
            p["age"]?.let { a -> a.size >= 2 && a.all { it.toIntOrNull() != null } } == true && p.keys.all { it == "age" } &&
                (role == "crop" || (role == null && Regex("crop|wheat|carrot|potato|beetroot|seeds?$").containsMatchIn(id.path))) -> BlockFamily.CROP
            else -> null
        }
    }

    /** Il blocco Bedrock con stati, o null se la famiglia non si sa ricostruire o i modelli non si leggono. */
    fun build(
        s: JBlockStates, family: BlockFamily, res: ModelResolver, id: Rl,
        lootFor: ((Map<String, String>) -> String?)? = null, exists: (Rl) -> Boolean = { true }
    ): StatefulBlock? {
        // Ferro e acciaio: in Java porte e botole si aprono solo con la pietrarossa; i pulsanti di pietra durano meno.
        val iron = Regex("iron|steel").containsMatchIn(id.path)
        val stone = Regex("stone|iron|steel|metal|blackstone|brick").containsMatchIn(id.path)
        val spec = Spec.of(family, s, if (family == BlockFamily.BUTTON) stone else iron) ?: return null
        // ---- pezzi: una variante (o parte) = un pezzo, con la rotazione di Java già applicata
        class Piece(val v: JVariant, val shows: (Map<String, String>) -> Boolean)
        val pieces = ArrayList<Piece>()
        if (s.parts.isNotEmpty()) for ((w, v) in s.parts) pieces.add(Piece(v) { st -> w?.matches(st) ?: true })
        else {
            // Varianti: vale la prima la cui chiave è compresa nello stato (come in Java).
            val list = s.variants
            list.forEachIndexed { i, (key, v) ->
                pieces.add(Piece(v) { st -> list.indexOfFirst { (k, _) -> k.all { (kk, vv) -> st[kk] == vv } } == i })
            }
        }
        val combos = combos(spec.states)
        val javaOf = combos.map { spec.java(it) }
        val textures = LinkedHashMap<String, Rl>()
        var tinted = false
        val bones = ArrayList<StateBone>()
        val visibleByCombo = Array(combos.size) { ArrayList<List<JElement>>() }
        val boneCache = HashMap<JVariant, Pair<String, List<JElement>>?>()
        val boneCombos = LinkedHashMap<String, MutableSet<Int>>()
        val boneElements = LinkedHashMap<String, List<JElement>>()
        for (pc in pieces) {
            val shown = javaOf.indices.filter { pc.shows(javaOf[it]) }
            if (shown.isEmpty()) continue
            val bone = boneCache.getOrPut(pc.v) {
                val g = res.geometry(pc.v.model) ?: return@getOrPut null
                if (g.tinted) tinted = true
                // Variabili delle texture con lo stesso nome ma texture diverse (modelli diversi): si rinominano.
                val rename = HashMap<String, String>()
                val fallback = g.textures.values.firstOrNull { exists(it) } ?: return@getOrPut null
                for ((k, t0) in g.textures) {
                    val t = if (exists(t0)) t0 else fallback
                    var name = k
                    var n = 2
                    while (textures[name] != null && textures[name] != t) name = k + "_" + n++
                    textures[name] = t
                    if (name != k) rename[k] = name
                }
                val els = g.elements.map { e -> if (rename.isEmpty()) e else retexture(e, rename) }
                ("b" + boneElements.size) to rotate(els, pc.v.x, pc.v.y, pc.v.uvlock).also { boneElements["b" + boneElements.size] = it }
            } ?: return null
            boneCombos.getOrPut(bone.first) { LinkedHashSet() }.addAll(shown)
            for (i in shown) visibleByCombo[i].add(bone.second)
        }
        if (boneElements.isEmpty() || textures.isEmpty()) return null
        // Un file «blockstates» incompleto (stati senza nessuna variante): meglio il blocco con un solo aspetto che un blocco invisibile.
        if (visibleByCombo.any { it.isEmpty() }) return null
        for ((name, els) in boneElements) {
            val shown = boneCombos[name].orEmpty()
            val cond = express(spec.states, combos, shown)
            bones.add(StateBone(name, els, if (cond == "true") null else cond))
        }
        if (bones.count { it.visible != null } > 64) return null   // Bedrock: al massimo 64 pezzi con visibilità
        if (!Geometry.fitsBedrock(bones.flatMap { it.elements })) return null

        // ---- collisione, selezione e componenti per stato; si raggruppano gli stati con gli stessi componenti
        val groups = LinkedHashMap<String, Pair<Map<String, Any?>, MutableSet<Int>>>()
        for (i in combos.indices) {
            val els = visibleByCombo[i].flatten()
            val comps = LinkedHashMap<String, Any?>()
            comps["minecraft:collision_box"] = spec.collision(javaOf[i], els)
            comps["minecraft:selection_box"] = selection(els)
            comps.putAll(spec.extra(combos[i], javaOf[i]))
            lootFor?.invoke(javaOf[i])?.let { comps["minecraft:loot"] = it }
            val key = com.mmi.modinstaller.core.Json.stringify(comps, pretty = false)
            groups.getOrPut(key) { comps to LinkedHashSet() }.second.add(i)
        }
        val ordered = groups.values.sortedByDescending { it.second.size }
        val base = LinkedHashMap<String, Any?>(spec.components)
        base.putAll(ordered.first().first)
        // Bedrock: i componenti delle varianti devono esistere anche nella base (es. la pietrarossa).
        val perms = ArrayList<Pair<String, Map<String, Any?>>>()
        for ((comps, set) in ordered.drop(1)) {
            val diff = comps.filter { (k, v) -> base[k] != v }
            if (diff.isEmpty()) continue
            perms.add(express(spec.states, combos, set) to diff)
        }
        val custom = LinkedHashMap<String, List<Any>>()
        for (st in spec.states) if (st.custom) custom[st.name] = st.values
        return StatefulBlock(family, bones, textures, tinted, custom, spec.traits, base, perms, spec.note, spec.java(combos.first()))
    }

    // ---------------------------------------------------------------- famiglie

    /** Come una famiglia di blocchi Java diventa stati Bedrock. */
    private abstract class Spec(val props: Map<String, Set<String>>) {
        abstract val states: List<BState>
        abstract val traits: Map<String, Any?>
        /** Le proprietà Java per una combinazione di stati Bedrock. */
        abstract fun java(c: Map<String, Any>): Map<String, String>
        open val components: Map<String, Any?> = emptyMap()
        open val note: String? = null
        open fun extra(c: Map<String, Any>, j: Map<String, String>): Map<String, Any?> = emptyMap()
        open fun collision(j: Map<String, String>, els: List<JElement>): Any? = boxes(els, 16.0)

        /** Le proprietà che Bedrock non gestisce restano al valore più comune in Java (es. «waterlogged=false»). */
        fun fixed(out: MutableMap<String, String>): MutableMap<String, String> {
            for ((k, values) in props) if (k !in out) out[k] = defaultValue(k, values)
            return out
        }

        companion object {
            fun of(f: BlockFamily, s: JBlockStates, iron: Boolean): Spec? = when (f) {
                BlockFamily.SLAB -> SlabSpec(s.properties)
                BlockFamily.STAIRS -> StairsSpec(s.properties)
                BlockFamily.DOOR -> DoorSpec(s.properties, iron)
                BlockFamily.TRAPDOOR -> TrapdoorSpec(s.properties, iron)
                BlockFamily.FENCE_GATE -> GateSpec(s.properties)
                BlockFamily.FENCE, BlockFamily.PANE -> ConnectSpec(s.properties, wall = false, height = if (f == BlockFamily.FENCE) 24.0 else 16.0, fence = f == BlockFamily.FENCE)
                BlockFamily.WALL -> ConnectSpec(s.properties, wall = true, height = 24.0, fence = false)
                BlockFamily.BUTTON -> ButtonSpec(s.properties, iron)
                BlockFamily.PRESSURE_PLATE -> PlateSpec(s.properties)
                BlockFamily.CROP -> s.properties["age"]?.mapNotNull { it.toIntOrNull() }?.maxOrNull()?.let { CropSpec(s.properties, it) }
            }
        }
    }

    fun defaultValue(k: String, values: Set<String>): String {
        val prefer = mapOf(
            "waterlogged" to "false", "powered" to "false", "open" to "false", "in_wall" to "false", "hinge" to "left",
            "shape" to "straight", "half" to "bottom", "type" to "bottom", "facing" to "north", "lit" to "false", "up" to "true",
            "face" to "floor", "axis" to "y", "snowy" to "false", "attached" to "false", "occupied" to "false", "part" to "foot"
        )
        prefer[k]?.let { if (it in values) return it }
        return values.firstOrNull { it == "false" || it == "none" || it == "0" } ?: values.first()
    }

    private class SlabSpec(p: Map<String, Set<String>>) : Spec(p) {
        override val states = listOf(BState(HALF, listOf("bottom", "top"), false), BState("mmi:double", listOf(false, true), true))
        override val traits = mapOf("minecraft:placement_position" to mapOf("enabled_states" to listOf(HALF)))
        override fun java(c: Map<String, Any>) = fixed(linkedMapOf("type" to if (c["mmi:double"] == true) "double" else c[HALF] as String))
        override val components = mapOf<String, Any?>(Behaviors.SLAB to emptyMap<String, Any?>())
    }

    private class StairsSpec(p: Map<String, Set<String>>) : Spec(p) {
        override val states = listOf(
            BState(CARD, CARDINALS, false), BState(HALF, listOf("bottom", "top"), false),
            BState(CORNER, listOf("none", "inner_left", "inner_right", "outer_left", "outer_right"), false)
        )
        override val traits = mapOf(
            "minecraft:placement_direction" to mapOf("enabled_states" to listOf("minecraft:corner_and_cardinal_direction")),
            "minecraft:placement_position" to mapOf("enabled_states" to listOf(HALF))
        )
        override fun java(c: Map<String, Any>) = fixed(
            linkedMapOf("facing" to c[CARD] as String, "half" to c[HALF] as String, "shape" to (c[CORNER] as String).let { if (it == "none") "straight" else it })
        )
        override val components = mapOf<String, Any?>("minecraft:support" to mapOf("shape" to "stair"))
    }

    private class DoorSpec(p: Map<String, Set<String>>, val iron: Boolean) : Spec(p) {
        override val states = listOf(BState(CARD, CARDINALS, false), BState(PART, listOf(0, 1), false), BState("mmi:open", listOf(false, true), true))
        override val traits = mapOf(
            "minecraft:multi_block" to mapOf("enabled_states" to listOf(PART), "parts" to 2L, "direction" to "up"),
            "minecraft:placement_direction" to mapOf("enabled_states" to listOf(CARD))
        )
        override fun java(c: Map<String, Any>) = fixed(
            linkedMapOf("facing" to c[CARD] as String, "half" to if (c[PART] == 1) "upper" else "lower", "open" to c["mmi:open"].toString())
        )
        override val components = linkedMapOf<String, Any?>(
            "minecraft:movable" to mapOf("movement_type" to "popped"),
            "minecraft:redstone_consumer" to mapOf("min_power" to 0L),
            Behaviors.OPENABLE to linkedMapOf("hand" to !iron, "pair" to true, "sound" to if (iron) "iron_door" else "door")
        )
        override val note = "Porte: la cerniera è sempre a sinistra"
    }

    private class TrapdoorSpec(p: Map<String, Set<String>>, val iron: Boolean) : Spec(p) {
        override val states = listOf(BState(CARD, CARDINALS, false), BState(HALF, listOf("bottom", "top"), false), BState("mmi:open", listOf(false, true), true))
        override val traits = mapOf(
            "minecraft:placement_direction" to mapOf("enabled_states" to listOf(CARD), "y_rotation_offset" to 180L),
            "minecraft:placement_position" to mapOf("enabled_states" to listOf(HALF))
        )
        override fun java(c: Map<String, Any>) = fixed(linkedMapOf("facing" to c[CARD] as String, "half" to c[HALF] as String, "open" to c["mmi:open"].toString()))
        override val components = linkedMapOf<String, Any?>(
            "minecraft:redstone_consumer" to mapOf("min_power" to 0L),
            Behaviors.OPENABLE to linkedMapOf("hand" to !iron, "pair" to false, "sound" to if (iron) "iron_trapdoor" else "trapdoor")
        )
    }

    private class GateSpec(p: Map<String, Set<String>>) : Spec(p) {
        override val states = listOf(BState(CARD, CARDINALS, false), BState("mmi:open", listOf(false, true), true))
        override val traits = mapOf("minecraft:placement_direction" to mapOf("enabled_states" to listOf(CARD)))
        override fun java(c: Map<String, Any>) = fixed(linkedMapOf("facing" to c[CARD] as String, "open" to c["mmi:open"].toString(), "in_wall" to "false"))
        override val components = linkedMapOf<String, Any?>(
            "minecraft:redstone_consumer" to mapOf("min_power" to 0L),
            Behaviors.OPENABLE to linkedMapOf("hand" to true, "pair" to false, "sound" to "fence_gate", "gate" to true)
        )
        // Java: il cancello chiuso blocca fino a 1,5 blocchi; aperto non blocca.
        override fun collision(j: Map<String, String>, els: List<JElement>): Any? {
            if (j["open"] == "true") return false
            val ns = j["facing"] == "north" || j["facing"] == "south"
            return if (ns) box(0.0, 0.0, 6.0, 16.0, 24.0, 10.0) else box(6.0, 0.0, 0.0, 10.0, 24.0, 16.0)
        }
        override val note = "Cancelli: non si abbassano accanto ai muretti"
    }

    /** Staccionate, pannelli e muretti: i collegamenti li calcola Bedrock (tratto «minecraft:connection»). */
    private class ConnectSpec(p: Map<String, Set<String>>, val wall: Boolean, val height: Double, val fence: Boolean) : Spec(p) {
        override val states = SIDES.map { BState("minecraft:connection_$it", listOf(false, true), false) }
        override val traits = mapOf("minecraft:connection" to mapOf("enabled_states" to listOf("minecraft:cardinal_connections")))
        override fun java(c: Map<String, Any>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (side in SIDES) {
                val on = c["minecraft:connection_$side"] == true
                out[side] = if (wall) (if (on) "low" else "none") else on.toString()
            }
            if (wall) {
                // Java: il pilastro c'è se il muretto non è un tratto dritto.
                val ns = out["north"] != "none" && out["south"] != "none" && out["east"] == "none" && out["west"] == "none"
                val ew = out["east"] != "none" && out["west"] != "none" && out["north"] == "none" && out["south"] == "none"
                out["up"] = (!(ns || ew)).toString()
            }
            return fixed(out)
        }
        override val components = if (fence) mapOf<String, Any?>("minecraft:support" to mapOf("shape" to "fence")) else emptyMap()
        override fun collision(j: Map<String, String>, els: List<JElement>): Any? {
            // Come in Java: palo e bracci, alti 1,5 blocchi per staccionate e muretti.
            val (post, arm) = when { wall -> 4.0 to 3.0; fence -> 2.0 to 2.0; else -> 1.0 to 1.0 }
            val list = ArrayList<Any?>()
            list.add(box(8 - post, 0.0, 8 - post, 8 + post, height, 8 + post))
            if (j["north"] != "false" && j["north"] != "none") list.add(box(8 - arm, 0.0, 0.0, 8 + arm, height, 8 - post))
            if (j["south"] != "false" && j["south"] != "none") list.add(box(8 - arm, 0.0, 8 + post, 8 + arm, height, 16.0))
            if (j["west"] != "false" && j["west"] != "none") list.add(box(0.0, 0.0, 8 - arm, 8 - post, height, 8 + arm))
            if (j["east"] != "false" && j["east"] != "none") list.add(box(8 + post, 0.0, 8 - arm, 16.0, height, 8 + arm))
            return list
        }
        override val note = if (wall) "Muretti: i lati restano bassi anche sotto un blocco" else null
    }

    private class ButtonSpec(p: Map<String, Set<String>>, val stone: Boolean) : Spec(p) {
        override val states = listOf(
            BState(FACE, listOf("down", "up", "north", "south", "east", "west"), false), BState(CARD, CARDINALS, false),
            BState("mmi:powered", listOf(false, true), true)
        )
        override val traits = mapOf(
            "minecraft:placement_position" to mapOf("enabled_states" to listOf(FACE)),
            "minecraft:placement_direction" to mapOf("enabled_states" to listOf(CARD))
        )
        override fun java(c: Map<String, Any>): Map<String, String> {
            val face = c[FACE] as String
            return fixed(
                linkedMapOf(
                    "face" to when (face) { "up" -> "floor"; "down" -> "ceiling"; else -> "wall" },
                    "facing" to if (face == "up" || face == "down") c[CARD] as String else face,
                    "powered" to c["mmi:powered"].toString()
                )
            )
        }
        override val components = linkedMapOf<String, Any?>(
            "minecraft:collision_box" to false, "minecraft:redstone_producer" to mapOf("power" to 0L),
            Behaviors.BUTTON to linkedMapOf("ticks" to if (stone) 20L else 30L)
        )
        override fun collision(j: Map<String, String>, els: List<JElement>): Any? = false
        override fun extra(c: Map<String, Any>, j: Map<String, String>): Map<String, Any?> {
            if (c["mmi:powered"] != true) return emptyMap()
            val attached = OPPOSITE.getValue(c[FACE] as String)
            return mapOf("minecraft:redstone_producer" to linkedMapOf("power" to 15L, "strongly_powered_face" to attached, "transform_relative" to false))
        }
    }

    private class PlateSpec(p: Map<String, Set<String>>) : Spec(p) {
        private val weighted = p.containsKey("power")
        override val states = listOf(BState("mmi:powered", listOf(false, true), true))
        override val traits = emptyMap<String, Any?>()
        override fun java(c: Map<String, Any>): Map<String, String> =
            fixed(if (weighted) linkedMapOf("power" to if (c["mmi:powered"] == true) "15" else "0") else linkedMapOf("powered" to c["mmi:powered"].toString()))
        override val components = linkedMapOf<String, Any?>(
            "minecraft:collision_box" to false, "minecraft:redstone_producer" to mapOf("power" to 0L),
            "minecraft:tick" to mapOf("interval_range" to listOf(10L, 10L), "looping" to true),
            Behaviors.PLATE to emptyMap<String, Any?>()
        )
        override fun collision(j: Map<String, String>, els: List<JElement>): Any? = false
        override fun extra(c: Map<String, Any>, j: Map<String, String>): Map<String, Any?> =
            if (c["mmi:powered"] != true) emptyMap() else mapOf("minecraft:redstone_producer" to linkedMapOf("power" to 15L, "strongly_powered_face" to "down", "transform_relative" to false))
        override val note = if (weighted) "Pedane a peso: danno sempre il segnale pieno" else null
    }

    private class CropSpec(p: Map<String, Set<String>>, val max: Int) : Spec(p) {
        override val states = listOf(BState("mmi:age", (0..max).toList(), true))
        override val traits = emptyMap<String, Any?>()
        override fun java(c: Map<String, Any>) = fixed(linkedMapOf("age" to c["mmi:age"].toString()))
        override val components = linkedMapOf<String, Any?>(
            "minecraft:collision_box" to false,
            "minecraft:placement_filter" to mapOf("conditions" to listOf(mapOf("allowed_faces" to listOf("up"), "block_filter" to listOf("minecraft:farmland")))),
            Behaviors.CROP to linkedMapOf("max" to max.toLong())
        )
        override fun collision(j: Map<String, String>, els: List<JElement>): Any? = false
    }

    private val OPPOSITE = mapOf("up" to "down", "down" to "up", "north" to "south", "south" to "north", "east" to "west", "west" to "east")

    // ---------------------------------------------------------------- combinazioni e condizioni Molang

    private fun combos(states: List<BState>): List<Map<String, Any>> {
        var out = listOf<Map<String, Any>>(emptyMap())
        for (s in states) out = out.flatMap { m -> s.values.map { v -> m + (s.name to v) } }
        return out
    }

    /** La condizione Molang (solo «q.block_state») vera esattamente nelle combinazioni [set]. */
    private fun express(states: List<BState>, combos: List<Map<String, Any>>, set: Set<Int>): String {
        if (set.size == combos.size) return "true"
        if (set.isEmpty()) return "false"
        return expr(states, set.map { combos[it] })
    }

    private fun expr(states: List<BState>, chosen: List<Map<String, Any>>): String {
        if (states.isEmpty()) return "true"
        // Se le combinazioni scelte sono un «prodotto» (ogni stato può avere certi valori, indipendentemente dagli altri) basta un AND.
        val values = states.map { s -> chosen.map { it[s.name]!! }.toSet() }
        val product = values.fold(1L) { acc, v -> acc * v.size }
        if (product == chosen.toSet().size.toLong()) {
            val parts = states.indices.filter { values[it].size < states[it].values.size }.map { lit(states[it], values[it]) }
            return if (parts.isEmpty()) "true" else parts.joinToString(" && ")
        }
        // Altrimenti si divide sul primo stato che non è uniforme, raggruppando i valori che hanno lo stesso resto.
        val i = states.indices.first { values[it].size > 1 }
        val s = states[i]
        val rest = states.filterIndexed { k, _ -> k != i }
        val byValue = LinkedHashMap<Any, Set<Map<String, Any>>>()
        for (v in s.values) {
            val sub = chosen.filter { it[s.name] == v }.map { it - s.name }.toSet()
            if (sub.isNotEmpty()) byValue[v] = sub
        }
        val groups = LinkedHashMap<Set<Map<String, Any>>, MutableSet<Any>>()
        for ((v, sub) in byValue) groups.getOrPut(sub) { LinkedHashSet() }.add(v)
        return groups.entries.joinToString(" || ") { (sub, vals) ->
            val head = if (vals.size == s.values.size) null else lit(s, vals)
            val tail = expr(rest, sub.toList())
            when {
                head == null -> "($tail)"
                tail == "true" -> "($head)"
                else -> "($head && ($tail))"
            }
        }
    }

    private fun lit(s: BState, vals: Set<Any>): String {
        val q = "q.block_state('${s.name}')"
        if (s.values.all { it is Boolean }) {
            return if (vals.size == 1) (if (vals.first() == true) q else "!$q") else "true"
        }
        val list = vals.map { v -> if (v is String) "$q == '$v'" else "$q == $v" }
        return if (list.size == 1) list[0] else "(" + list.joinToString(" || ") + ")"
    }

    // ---------------------------------------------------------------- geometria

    private fun box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double): Map<String, Any?> =
        linkedMapOf("origin" to listOf(n(x0 - 8), n(y0), n(z0 - 8)), "size" to listOf(n(x1 - x0), n(y1 - y0), n(z1 - z0)))

    private fun n(v: Double): Any = if (v == Math.floor(v)) v.toLong() else Math.round(v * 1000.0) / 1000.0

    /** I riquadri di collisione degli elementi visibili (fino a 16, poi uno solo che li contiene). */
    private fun boxes(els: List<JElement>, maxY: Double): Any? {
        val list = LinkedHashSet<Map<String, Any?>>()
        for (e in els) {
            val lo = DoubleArray(3) { minOf(e.from[it], e.to[it]).coerceIn(0.0, if (it == 1) maxY else 16.0) }
            val hi = DoubleArray(3) { maxOf(e.from[it], e.to[it]).coerceIn(0.0, if (it == 1) maxY else 16.0) }
            if ((0..2).any { hi[it] - lo[it] <= 0.0 }) continue
            list.add(box(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]))
        }
        if (list.isEmpty()) return false
        if (list.size > 16) return selection(els)
        return list.toList()
    }

    private fun selection(els: List<JElement>): Any? {
        val b = Geometry.bounds(els) ?: return linkedMapOf("origin" to listOf(-8L, 0L, -8L), "size" to listOf(16L, 16L, 16L))
        return linkedMapOf("origin" to b.first.map { n(it) }, "size" to b.second.map { n(it) })
    }

    private fun retexture(e: JElement, rename: Map<String, String>): JElement {
        val faces = LinkedHashMap<String, JFace>()
        for ((d, f) in e.faces) {
            val v = f.texture.removePrefix("#")
            faces[d] = rename[v]?.let { JFace(f.uv, "#$it", f.rotation, f.tinted) } ?: f
        }
        return JElement(e.from, e.to, e.rotOrigin, e.rotAxis, e.rotAngle, faces)
    }

    /**
     * Applica la rotazione di una variante Java (prima X, poi Y, in senso orario come in Java) agli elementi, così che
     * ogni pezzo sia già girato. Con «uvlock» le texture restano allineate al mondo (coordinate ricalcolate); senza, le
     * facce sopra e sotto girano con il blocco.
     */
    fun rotate(els: List<JElement>, x: Int, y: Int, uvlock: Boolean): List<JElement> {
        val qx = (x / 90).mod(4)
        val qy = (y / 90).mod(4)
        if (qx == 0 && qy == 0) return els
        return els.map { e ->
            var from = e.from.copyOf(); var to = e.to.copyOf()
            var origin = e.rotOrigin?.copyOf()
            var axis = e.rotAxis; var angle = e.rotAngle
            val faces = LinkedHashMap<String, JFace>(e.faces)
            var dirs = faces.keys.associateWith { it }.toMutableMap()   // direzione originale → direzione attuale
            repeat(qx) {
                fun p(v: DoubleArray) = doubleArrayOf(v[0], v[2], 16 - v[1])
                from = p(from); to = p(to); origin = origin?.let { p(it) }
                when (axis) { 'y' -> { axis = 'z'; angle = -angle }; 'z' -> axis = 'y' }
                dirs = dirs.mapValues { (_, d) -> X_STEP[d] ?: d }.toMutableMap()
            }
            repeat(qy) {
                fun p(v: DoubleArray) = doubleArrayOf(16 - v[2], v[1], v[0])
                from = p(from); to = p(to); origin = origin?.let { p(it) }
                when (axis) { 'x' -> axis = 'z'; 'z' -> { axis = 'x'; angle = -angle } }
                dirs = dirs.mapValues { (_, d) -> Y_STEP[d] ?: d }.toMutableMap()
            }
            val lo = DoubleArray(3) { minOf(from[it], to[it]) }
            val hi = DoubleArray(3) { maxOf(from[it], to[it]) }
            val out = LinkedHashMap<String, JFace>()
            for ((orig, f) in faces) {
                val d = dirs.getValue(orig)
                out[d] = when {
                    uvlock || qx != 0 -> JFace(null, f.texture, 0, f.tinted)
                    d == "up" -> JFace(f.uv, f.texture, (f.rotation + 90 * qy).mod(360), f.tinted)
                    d == "down" -> JFace(f.uv, f.texture, (f.rotation - 90 * qy).mod(360), f.tinted)
                    else -> f
                }
            }
            JElement(lo, hi, origin, axis, angle, out)
        }
    }

    /** Un passo di 90° attorno a X (come «x: 90» di Java: il nord va giù). */
    private val X_STEP = mapOf("north" to "down", "down" to "south", "south" to "up", "up" to "north")
    /** Un passo di 90° attorno a Y (come «y: 90» di Java: il nord va a est). */
    private val Y_STEP = mapOf("north" to "east", "east" to "south", "south" to "west", "west" to "north")
}
