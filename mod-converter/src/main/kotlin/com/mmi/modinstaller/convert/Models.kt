package com.mmi.modinstaller.convert

import com.mmi.modinstaller.core.asJsonList
import com.mmi.modinstaller.core.asJsonObject

/** Una faccia di un elemento di un modello Java: coordinate della texture (0–16), variabile della texture, rotazione. */
class JFace(val uv: DoubleArray?, val texture: String, val rotation: Int, val tinted: Boolean)

/** Un elemento (parallelepipedo) di un modello Java, in pixel da 0 a 16. */
class JElement(
    val from: DoubleArray,
    val to: DoubleArray,
    val rotOrigin: DoubleArray?,
    val rotAxis: Char?,
    val rotAngle: Double,
    val faces: Map<String, JFace>
) {
    val isFullCube: Boolean
        get() = rotAngle == 0.0 && from.all { it == 0.0 } && to.all { it == 16.0 } && faces.size == 6

    companion object {
        fun parse(o: Map<String, Any?>): JElement? {
            val from = vec(o["from"]) ?: return null
            val to = vec(o["to"]) ?: return null
            val rot = o["rotation"].asJsonObject()
            val faces = LinkedHashMap<String, JFace>()
            o["faces"].asJsonObject()?.forEach { (dir, v) ->
                val f = v.asJsonObject() ?: return@forEach
                val tex = f["texture"] as? String ?: return@forEach
                val uv = f["uv"].asJsonList()?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.size == 4 }?.toDoubleArray()
                if (dir in DIRECTIONS) faces[dir] = JFace(uv, tex, ((f["rotation"] as? Number)?.toInt() ?: 0), f["tintindex"] != null)
            }
            return JElement(
                from, to, vec(rot?.get("origin")), (rot?.get("axis") as? String)?.firstOrNull(),
                (rot?.get("angle") as? Number)?.toDouble() ?: 0.0, faces
            )
        }

        private fun vec(v: Any?): DoubleArray? = v.asJsonList()?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.size == 3 }?.toDoubleArray()

        val DIRECTIONS = listOf("down", "up", "north", "south", "west", "east")

        /** Un elemento con le stesse texture su tutte le facce che hanno una voce in [byFace] (o [all]). */
        fun box(from: List<Double>, to: List<Double>, all: String? = null, byFace: Map<String, String> = emptyMap()): JElement {
            val faces = LinkedHashMap<String, JFace>()
            for (d in DIRECTIONS) {
                val t = byFace[d] ?: byFace[if (d == "up" || d == "down") d else "side"] ?: all ?: continue
                faces[d] = JFace(null, t, 0, false)
            }
            return JElement(from.toDoubleArray(), to.toDoubleArray(), null, null, 0.0, faces)
        }
    }
}

/** Come appare un blocco in Bedrock. */
sealed class BlockLook {
    /** Cubo intero: una texture per faccia (`up`, `down`, `north`, `south`, `east`, `west`). */
    class Cube(val faces: Map<String, Rl>, val tinted: Boolean) : BlockLook()

    /** Pianta a «X» (fiori, germogli, colture). */
    class Cross(val texture: Rl, val tinted: Boolean) : BlockLook()

    /** Forma propria, fatta di elementi: diventa una geometria Bedrock. */
    class Shape(val elements: List<JElement>, val textures: Map<String, Rl>) : BlockLook()

    /** Blocco con più stati (porte, lastre, scale, staccionate…): geometria a pezzi e stati di Bedrock 1.26.40. */
    class Stateful(val block: StatefulBlock) : BlockLook()
}

/** Il risultato della lettura del modello di un blocco, con le eventuali semplificazioni (per il rapporto). */
class LookResult(
    val look: BlockLook?,
    val simplified: String? = null,
    val drawnByCode: Boolean = false,
    /** Il modello di Minecraft da cui deriva la forma, quando l'app non la sa riprodurre (es. «block/door_bottom_left»). */
    val unknownShape: String? = null
)

/**
 * Risolve i modelli Java (`assets/<ns>/models/…`) seguendo la catena dei «parent» e le variabili delle texture (`#all`).
 * I modelli di Minecraft non stanno nel JAR della mod: per quelli più usati (cubi, colonne, piante, lastre, scale…) la forma è
 * scritta qui.
 */
class ModelResolver(private val jars: List<JavaJar>, private val limits: ConvertLimits) {

    private val cache = HashMap<Rl, Map<String, Any?>?>()

    fun model(rl: Rl): Map<String, Any?>? = cache.getOrPut(rl) {
        val name = "assets/${rl.ns}/models/${rl.path}.json"
        jars.firstNotNullOfOrNull { it.json(name, limits.maxJsonBytes)?.asJsonObject() }
    }

    class Resolved(val textures: Map<String, String>, val elements: List<JElement>?, val vanillaParent: String?)

    /** Segue i «parent» fino a un modello di Minecraft non presente nella mod (che diventa [Resolved.vanillaParent]). */
    fun resolve(start: Rl): Resolved? {
        val textures = LinkedHashMap<String, String>()
        var elements: List<JElement>? = null
        var current: Rl? = start
        var vanilla: String? = null
        var depth = 0
        var found = false
        while (current != null && depth++ < 24) {
            val m = model(current)
            if (m == null) {
                if (current.ns == "minecraft" || current.path.startsWith("builtin/")) vanilla = current.path
                break
            }
            found = true
            m["textures"].asJsonObject()?.forEach { (k, v) -> if (v is String && k !in textures) textures[k] = v }
            if (elements == null) {
                m["elements"].asJsonList()?.let { list -> elements = list.mapNotNull { e -> e.asJsonObject()?.let { JElement.parse(it) } } }
            }
            val parent = m["parent"] as? String ?: break
            current = Rl.parse(parent)
            if (current != null && current.ns == "minecraft" && model(current) == null) {
                vanilla = current.path
                break
            }
        }
        if (!found && vanilla == null) return null
        return Resolved(textures, elements, vanilla)
    }

    /** Il valore finale di una variabile («#side» → «mod:block/x_side»), o null. */
    fun texture(textures: Map<String, String>, ref: String): Rl? {
        var v: String? = ref
        var n = 0
        while (v != null && v.startsWith("#") && n++ < 16) v = textures[v.substring(1)]
        return v?.let { Rl.parse(it) }
    }

    fun blockLook(modelRl: Rl): LookResult {
        val r = resolve(modelRl) ?: return LookResult(null)
        val tex = r.textures
        fun t(name: String) = texture(tex, "#$name")
        val own = r.elements
        if (own != null && own.isNotEmpty()) return fromElements(own, tex)
        val parent = r.vanillaParent?.removePrefix("minecraft:")
        val family = parent?.substringAfterLast('/')
        fun cube(up: Rl?, down: Rl?, north: Rl?, south: Rl?, east: Rl?, west: Rl?, tinted: Boolean = false): LookResult? {
            val faces = mapOf("up" to up, "down" to down, "north" to north, "south" to south, "east" to east, "west" to west)
            if (faces.values.any { it == null }) return null
            @Suppress("UNCHECKED_CAST")
            return LookResult(BlockLook.Cube(faces as Map<String, Rl>, tinted))
        }
        val result: LookResult? = when (family) {
            "cube_all", "cube_mirrored_all", "cube_north_west_mirrored_all", "cube_all_inner_faces" -> t("all").let { a -> cube(a, a, a, a, a, a) }
            "leaves" -> t("all").let { a -> cube(a, a, a, a, a, a, tinted = true) }
            "cube" -> cube(t("up"), t("down"), t("north"), t("south"), t("east"), t("west"))
            "cube_column", "cube_column_horizontal", "cube_column_mirrored", "cube_column_uv_locked_x", "cube_column_uv_locked_y",
            "cube_column_uv_locked_z" -> { val e = t("end"); val s = t("side"); cube(e, e, s, s, s, s) }
            "cube_bottom_top", "cube_bottom_top_inner_faces" -> { val s = t("side"); cube(t("top"), t("bottom"), s, s, s, s) }
            "cube_top" -> { val s = t("side"); cube(t("top"), s, s, s, s, s) }
            "orientable", "orientable_with_bottom" -> {
                val s = t("side"); val top = t("top")
                cube(top, t("bottom") ?: top, t("front"), s, s, s)
            }
            "orientable_vertical" -> { val s = t("side"); cube(t("front"), s, s, s, s, s) }
            "template_glazed_terracotta" -> t("pattern").let { a -> cube(a, a, a, a, a, a) }
            "cross", "tinted_cross", "crop", "stem_growth0", "stem_growth7", "cross_emissive" ->
                (t("cross") ?: t("crop") ?: t("stem"))?.let { LookResult(BlockLook.Cross(it, family == "tinted_cross" || family.startsWith("stem"))) }
            "flower_pot_cross" -> t("plant")?.let { LookResult(BlockLook.Cross(it, false), "solo la pianta, senza il vaso") }
            "slab" -> shape(listOf(JElement.box(listOf(0.0, 0.0, 0.0), listOf(16.0, 8.0, 16.0), byFace = slabFaces())), tex)
            "slab_top" -> shape(listOf(JElement.box(listOf(0.0, 8.0, 0.0), listOf(16.0, 16.0, 16.0), byFace = slabFaces())), tex)
            "stairs" -> shape(
                listOf(
                    JElement.box(listOf(0.0, 0.0, 0.0), listOf(16.0, 8.0, 16.0), byFace = slabFaces()),
                    JElement.box(listOf(8.0, 8.0, 0.0), listOf(16.0, 16.0, 16.0), byFace = slabFaces())
                ), tex
            )
            "inner_stairs" -> shape(
                listOf(
                    JElement.box(listOf(0.0, 0.0, 0.0), listOf(16.0, 8.0, 16.0), byFace = slabFaces()),
                    JElement.box(listOf(8.0, 8.0, 0.0), listOf(16.0, 16.0, 16.0), byFace = slabFaces()),
                    JElement.box(listOf(0.0, 8.0, 8.0), listOf(8.0, 16.0, 16.0), byFace = slabFaces())
                ), tex
            )
            "outer_stairs" -> shape(
                listOf(
                    JElement.box(listOf(0.0, 0.0, 0.0), listOf(16.0, 8.0, 16.0), byFace = slabFaces()),
                    JElement.box(listOf(8.0, 8.0, 8.0), listOf(16.0, 16.0, 16.0), byFace = slabFaces())
                ), tex
            )
            // Porte (dalla 1.19.3): un pannello spesso 3 pixel sul lato ovest; aperta o a destra = texture specchiata.
            "door_bottom_left", "door_bottom_right", "door_bottom_left_open", "door_bottom_right_open",
            "door_top_left", "door_top_right", "door_top_left_open", "door_top_right_open" -> {
                val v = if (family.startsWith("door_top")) "#top" else "#bottom"
                val mirrored = family.endsWith("right") || family.endsWith("left_open")
                val west = JFace(if (mirrored) doubleArrayOf(16.0, 0.0, 0.0, 16.0) else doubleArrayOf(0.0, 0.0, 16.0, 16.0), v, 0, false)
                val east = JFace(if (mirrored) doubleArrayOf(0.0, 0.0, 16.0, 16.0) else doubleArrayOf(16.0, 0.0, 0.0, 16.0), v, 0, false)
                val faces = linkedMapOf(
                    "down" to JFace(doubleArrayOf(13.0, 0.0, 16.0, 16.0), v, 0, false), "up" to JFace(doubleArrayOf(13.0, 0.0, 16.0, 16.0), v, 0, false),
                    "north" to JFace(doubleArrayOf(3.0, 0.0, 0.0, 16.0), v, 0, false), "south" to JFace(doubleArrayOf(0.0, 0.0, 3.0, 16.0), v, 0, false),
                    "west" to west, "east" to east
                )
                shape(listOf(JElement(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(3.0, 16.0, 16.0), null, null, 0.0, faces)), tex)
            }
            "template_fence_gate", "template_custom_fence_gate", "template_fence_gate_wall", "template_custom_fence_gate_wall" ->
                shape(gate(open = false, wall = family.endsWith("wall")), tex)
            "template_fence_gate_open", "template_custom_fence_gate_open", "template_fence_gate_wall_open", "template_custom_fence_gate_wall_open" ->
                shape(gate(open = true, wall = family.contains("wall")), tex)
            "fence_side", "custom_fence_side_north" -> shape(
                listOf(
                    JElement.box(listOf(7.0, 12.0, 0.0), listOf(9.0, 15.0, 9.0), all = "#texture"),
                    JElement.box(listOf(7.0, 6.0, 0.0), listOf(9.0, 9.0, 9.0), all = "#texture")
                ), tex
            )
            "template_wall_side" -> shape(listOf(JElement.box(listOf(5.0, 0.0, 0.0), listOf(11.0, 14.0, 8.0), all = "#wall")), tex)
            "template_wall_side_tall" -> shape(listOf(JElement.box(listOf(5.0, 0.0, 0.0), listOf(11.0, 16.0, 8.0), all = "#wall")), tex)
            "template_glass_pane_side" -> shape(listOf(JElement.box(listOf(7.0, 0.0, 0.0), listOf(9.0, 16.0, 7.0), all = "#pane", byFace = mapOf("up" to "#edge", "down" to "#edge"))), tex)
            "template_glass_pane_side_alt" -> shape(listOf(JElement.box(listOf(7.0, 0.0, 9.0), listOf(9.0, 16.0, 16.0), all = "#pane", byFace = mapOf("up" to "#edge", "down" to "#edge"))), tex)
            "carpet" -> shape(listOf(JElement.box(listOf(0.0, 0.0, 0.0), listOf(16.0, 1.0, 16.0), all = "#wool")), tex)
            "pressure_plate_up" -> shape(listOf(JElement.box(listOf(1.0, 0.0, 1.0), listOf(15.0, 1.0, 15.0), all = "#texture")), tex)
            "pressure_plate_down" -> shape(listOf(JElement.box(listOf(1.0, 0.0, 1.0), listOf(15.0, 0.5, 15.0), all = "#texture")), tex)
            "button", "button_inventory" -> shape(listOf(JElement.box(listOf(5.0, 0.0, 6.0), listOf(11.0, 2.0, 10.0), all = "#texture")), tex)
            "button_pressed" -> shape(listOf(JElement.box(listOf(5.0, 0.0, 6.0), listOf(11.0, 1.0, 10.0), all = "#texture")), tex)
            "fence_post", "fence_inventory", "custom_fence_post" ->
                shape(listOf(JElement.box(listOf(6.0, 0.0, 6.0), listOf(10.0, 16.0, 10.0), all = "#texture")), tex, "solo il palo (non si collega)")
            "template_wall_post", "wall_inventory" ->
                shape(listOf(JElement.box(listOf(4.0, 0.0, 4.0), listOf(12.0, 16.0, 12.0), all = "#wall")), tex, "solo il pilastro (non si collega)")
            "template_torch", "template_torch_unlit", "template_torch_wall", "template_torch_wall_unlit" ->
                shape(listOf(JElement.box(listOf(7.0, 0.0, 7.0), listOf(9.0, 10.0, 9.0), all = "#torch")), tex)
            "template_trapdoor_bottom", "template_orientable_trapdoor_bottom" ->
                shape(listOf(JElement.box(listOf(0.0, 0.0, 0.0), listOf(16.0, 3.0, 16.0), all = "#texture")), tex, "botola sempre chiusa")
            "template_trapdoor_top", "template_orientable_trapdoor_top" ->
                shape(listOf(JElement.box(listOf(0.0, 13.0, 0.0), listOf(16.0, 16.0, 16.0), all = "#texture")), tex, "botola sempre chiusa")
            "template_trapdoor_open", "template_orientable_trapdoor_open" ->
                shape(listOf(JElement.box(listOf(0.0, 0.0, 13.0), listOf(16.0, 16.0, 16.0), all = "#texture")), tex, "botola sempre aperta")
            "rail_flat", "rail_curved", "template_rail_raised_ne", "template_rail_raised_sw" -> {
                val rail = JElement(doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(16.0, 1.0, 16.0), null, null, 0.0,
                    linkedMapOf("up" to JFace(null, "#rail", 0, false), "down" to JFace(null, "#rail", 0, false)))
                shape(listOf(rail), tex, "binario piatto (non curva e non sale)")
            }
            "template_glass_pane_post", "template_glass_pane_noside", "template_glass_pane_noside_alt" ->
                shape(listOf(JElement.box(listOf(7.0, 0.0, 7.0), listOf(9.0, 16.0, 9.0), all = "#pane")), tex, "solo il montante (non si collega)")
            "template_lantern", "template_hanging_lantern" ->
                shape(
                    listOf(
                        JElement.box(listOf(5.0, 0.0, 5.0), listOf(11.0, 7.0, 11.0), all = "#lantern"),
                        JElement.box(listOf(6.0, 7.0, 6.0), listOf(10.0, 9.0, 10.0), all = "#lantern")
                    ), tex
                )
            "template_farmland" -> {
                val s = t("dirt"); shape(listOf(JElement.box(listOf(0.0, 0.0, 0.0), listOf(16.0, 15.0, 16.0), byFace = mapOf("up" to "#top", "down" to "#dirt", "side" to "#dirt"))), tex)
                    ?: s?.let { cube(it, it, it, it, it, it) }
            }
            "thin_block" -> null
            else -> null
        }
        if (result != null) return result
        // Modelli che hanno solo la texture delle particelle: il blocco lo disegna il codice (cartelli, casse, letti, stendardi).
        if (tex.keys.all { it == "particle" } || parent?.startsWith("builtin/") == true) return LookResult(null, drawnByCode = true)
        // Il modello «block/block» (o nessun genitore) con le texture ma senza elementi: si mostra come un cubo.
        if (parent == null || family == "block") {
            val main = t("all") ?: t("side") ?: t("texture") ?: t("particle") ?: tex.keys.firstNotNullOfOrNull { t(it) } ?: return LookResult(null)
            return LookResult(BlockLook.Cube(JElement.DIRECTIONS.associateWith { main }, false), "forma semplificata a cubo")
        }
        // Una forma di Minecraft che l'app non sa ancora riprodurre (porte, cancelli, ancore…): meglio non inventarla.
        return LookResult(null, unknownShape = parent)
    }

    private fun slabFaces() = mapOf("up" to "#top", "down" to "#bottom", "side" to "#side")

    /** Il cancello di Minecraft (chiuso lungo l'asse X, aperto con le ante verso sud); «wall» = abbassato di 3 pixel. */
    private fun gate(open: Boolean, wall: Boolean): List<JElement> {
        val dy = if (wall) -3.0 else 0.0
        fun b(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) =
            JElement.box(listOf(x0, y0 + dy, z0), listOf(x1, y1 + dy, z1), all = "#texture")
        val posts = listOf(b(0.0, 5.0, 7.0, 2.0, 16.0, 9.0), b(14.0, 5.0, 7.0, 16.0, 16.0, 9.0))
        val bars = if (!open) listOf(
            b(6.0, 6.0, 7.0, 8.0, 15.0, 9.0), b(8.0, 6.0, 7.0, 10.0, 15.0, 9.0), b(2.0, 6.0, 7.0, 6.0, 9.0, 9.0),
            b(2.0, 12.0, 7.0, 6.0, 15.0, 9.0), b(10.0, 6.0, 7.0, 14.0, 9.0, 9.0), b(10.0, 12.0, 7.0, 14.0, 15.0, 9.0)
        ) else listOf(
            b(0.0, 6.0, 13.0, 2.0, 15.0, 15.0), b(14.0, 6.0, 13.0, 16.0, 15.0, 15.0), b(0.0, 6.0, 9.0, 2.0, 9.0, 13.0),
            b(0.0, 12.0, 9.0, 2.0, 15.0, 13.0), b(14.0, 6.0, 9.0, 16.0, 9.0, 13.0), b(14.0, 12.0, 9.0, 16.0, 15.0, 13.0)
        )
        return posts + bars
    }

    /** Gli elementi di un modello (anche di quelli di Minecraft che l'app conosce), per comporre i blocchi con più stati. */
    class ModelGeometry(val elements: List<JElement>, val textures: Map<String, Rl>, val tinted: Boolean)

    fun geometry(modelRl: Rl): ModelGeometry? {
        val r = resolve(modelRl) ?: return null
        val family = r.vanillaParent?.removePrefix("minecraft:")?.substringAfterLast('/')
        // Colture: 4 piani (in Bedrock la forma a X è una sola e non va bene per i blocchi con più stati).
        if ((r.elements == null || r.elements.isEmpty()) && family == "crop") {
            val t = texture(r.textures, "#crop") ?: return null
            fun plane(f: List<Double>, to: List<Double>, faces: List<String>) =
                JElement(f.toDoubleArray(), to.toDoubleArray(), null, null, 0.0, faces.associateWith { JFace(null, "#crop", 0, false) })
            return ModelGeometry(
                listOf(
                    plane(listOf(4.0, -1.0, 0.0), listOf(4.0, 15.0, 16.0), listOf("west", "east")),
                    plane(listOf(12.0, -1.0, 0.0), listOf(12.0, 15.0, 16.0), listOf("west", "east")),
                    plane(listOf(0.0, -1.0, 4.0), listOf(16.0, 15.0, 4.0), listOf("north", "south")),
                    plane(listOf(0.0, -1.0, 12.0), listOf(16.0, 15.0, 12.0), listOf("north", "south"))
                ), mapOf("crop" to t), false
            )
        }
        return when (val look = blockLook(modelRl).look) {
            is BlockLook.Shape -> ModelGeometry(look.elements, look.textures, false)
            is BlockLook.Cube -> {
                val faces = LinkedHashMap<String, JFace>()
                val tex = LinkedHashMap<String, Rl>()
                for ((d, t) in look.faces) {
                    val v = "f_" + t.path.substringAfterLast('/').map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
                    tex[v] = t
                    faces[d] = JFace(null, "#$v", 0, look.tinted)
                }
                ModelGeometry(listOf(JElement(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(16.0, 16.0, 16.0), null, null, 0.0, faces)), tex, look.tinted)
            }
            is BlockLook.Cross -> {
                val rot = 45.0
                val o = doubleArrayOf(8.0, 8.0, 8.0)
                fun f(d: String) = JFace(null, "#cross", 0, look.tinted)
                ModelGeometry(
                    listOf(
                        JElement(doubleArrayOf(0.8, 0.0, 8.0), doubleArrayOf(15.2, 16.0, 8.0), o, 'y', rot, linkedMapOf("north" to f("north"), "south" to f("south"))),
                        JElement(doubleArrayOf(8.0, 0.0, 0.8), doubleArrayOf(8.0, 16.0, 15.2), o, 'y', rot, linkedMapOf("west" to f("west"), "east" to f("east")))
                    ), mapOf("cross" to look.texture), look.tinted
                )
            }
            else -> null
        }
    }

    private fun shape(elements: List<JElement>, tex: Map<String, String>, note: String? = null): LookResult? {
        val r = fromElements(elements, tex)
        return if (r.look == null) null else LookResult(r.look, note ?: r.simplified)
    }

    private fun fromElements(elements: List<JElement>, tex: Map<String, String>): LookResult {
        val vars = LinkedHashMap<String, Rl>()
        for (e in elements) for (f in e.faces.values) {
            val name = f.texture.removePrefix("#")
            if (name !in vars) texture(tex, f.texture)?.let { vars[name] = it }
        }
        if (vars.isEmpty()) return LookResult(null)
        // Bedrock accetta forme entro 30 pixel dal centro del blocco e non più grandi di 30×30×30: oltre, un cubo.
        if (!Geometry.fitsBedrock(elements)) {
            val main = vars.values.first()
            return LookResult(BlockLook.Cube(JElement.DIRECTIONS.associateWith { main }, false), "forma più grande di quella che Bedrock accetta")
        }
        // Un solo elemento a cubo intero: è un cubo normale (geometria di Minecraft, più leggera).
        val single = elements.singleOrNull()
        if (single != null && single.isFullCube && single.faces.values.all { it.uv == null || it.uv.contentEquals(defaultUv(single, it)) }) {
            val faces = HashMap<String, Rl>()
            for ((d, f) in single.faces) faces[d] = texture(tex, f.texture) ?: return LookResult(BlockLook.Shape(elements, vars))
            return LookResult(BlockLook.Cube(faces, single.faces.values.any { it.tinted }))
        }
        return LookResult(BlockLook.Shape(elements, vars))
    }

    private fun defaultUv(e: JElement, f: JFace): DoubleArray {
        val dir = e.faces.entries.first { it.value === f }.key
        return Geometry.defaultUv(e, dir)
    }

    /** L'icona di un oggetto: la texture «layer0» del suo modello (o, per i modelli 3D, quella principale). */
    fun itemIcon(modelRl: Rl): ItemModelInfo? {
        val r = resolve(modelRl) ?: return null
        val parent = r.vanillaParent?.removePrefix("minecraft:")
        val isSpawnEgg = parent == "item/template_spawn_egg"
        val layer0 = texture(r.textures, "#layer0")
        val layers = r.textures.keys.count { it.startsWith("layer") }
        val icon = layer0 ?: texture(r.textures, "#particle") ?: r.textures.keys.firstNotNullOfOrNull { texture(r.textures, "#$it") }
        val handheld = parent == "item/handheld" || parent == "item/handheld_rod" || parent == "item/handheld_mace"
        val is3d = layer0 == null && (r.elements != null || parent?.startsWith("block/") == true)
        return ItemModelInfo(icon, handheld, isSpawnEgg, layers > 1, is3d)
    }
}

class ItemModelInfo(val icon: Rl?, val handheld: Boolean, val spawnEgg: Boolean, val layered: Boolean, val model3d: Boolean)

/**
 * Converte gli elementi di un modello Java nella geometria di un blocco Bedrock. Le convenzioni (centro del blocco in 0, asse X
 * specchiato, facce sopra e sotto con la texture capovolta, rotazioni X e Y di segno opposto) sono quelle del formato Bedrock,
 * le stesse che usa Blockbench quando esporta un modello Java come blocco Bedrock.
 */
object Geometry {

    /** Le coordinate della texture che Minecraft Java usa quando una faccia non le indica. */
    fun defaultUv(e: JElement, dir: String): DoubleArray {
        val f = e.from; val t = e.to
        return when (dir) {
            "down" -> doubleArrayOf(f[0], 16 - t[2], t[0], 16 - f[2])
            "up" -> doubleArrayOf(f[0], f[2], t[0], t[2])
            "north" -> doubleArrayOf(16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1])
            "south" -> doubleArrayOf(f[0], 16 - t[1], t[0], 16 - f[1])
            "west" -> doubleArrayOf(f[2], 16 - t[1], t[2], 16 - f[1])
            else -> doubleArrayOf(16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1])
        }
    }

    private fun n(v: Double): Any = if (v == Math.floor(v) && Math.abs(v) < 1e9) v.toLong() else Math.round(v * 10000.0) / 10000.0

    /** Il documento `.geo.json` (formato 1.21.0) per il blocco; le facce usano come materiale il nome della variabile Java. */
    fun document(identifier: String, elements: List<JElement>): Map<String, Any?> = documentBones(identifier, listOf("root" to elements))

    /** Come [document], con più pezzi (BLOCKBRIDGE: uno per stato del blocco, mostrati o nascosti da «bone_visibility»). */
    fun documentBones(identifier: String, bones: List<Pair<String, List<JElement>>>): Map<String, Any?> {
        val boneList = bones.map { (name, els) -> linkedMapOf("name" to name, "pivot" to listOf(0L, 0L, 0L), "cubes" to cubes(els)) }
        val description = linkedMapOf<String, Any?>(
            "identifier" to identifier, "texture_width" to 16L, "texture_height" to 16L,
            "visible_bounds_width" to 3L, "visible_bounds_height" to 3L, "visible_bounds_offset" to listOf(0L, 0.5, 0L)
        )
        return linkedMapOf(
            "format_version" to "1.21.0",
            "minecraft:geometry" to listOf(linkedMapOf("description" to description, "bones" to boneList))
        )
    }

    private fun cubes(elements: List<JElement>): List<Any?> {
        val cubes = ArrayList<Any?>()
        for (e in elements) {
            val size = (0..2).map { e.to[it] - e.from[it] }
            val cube = LinkedHashMap<String, Any?>()
            cube["origin"] = listOf(n(8 - e.to[0]), n(e.from[1]), n(e.from[2] - 8))
            cube["size"] = size.map { n(it) }
            if (e.rotAngle != 0.0 && e.rotAxis != null) {
                val o = e.rotOrigin ?: doubleArrayOf(8.0, 8.0, 8.0)
                cube["pivot"] = listOf(n(8 - o[0]), n(o[1]), n(o[2] - 8))
                cube["rotation"] = when (e.rotAxis) {
                    'x' -> listOf(n(-e.rotAngle), 0L, 0L)
                    'y' -> listOf(0L, n(-e.rotAngle), 0L)
                    else -> listOf(0L, 0L, n(e.rotAngle))
                }
            }
            val uv = LinkedHashMap<String, Any?>()
            for ((dir, f) in e.faces) {
                val u = f.uv ?: defaultUv(e, dir)
                var start = listOf(u[0], u[1])
                var sz = listOf(u[2] - u[0], u[3] - u[1])
                if (dir == "up" || dir == "down") {
                    start = listOf(u[0] + sz[0], u[1] + sz[1])
                    sz = listOf(-sz[0], -sz[1])
                }
                val face = LinkedHashMap<String, Any?>()
                face["uv"] = start.map { n(it) }
                face["uv_size"] = sz.map { n(it) }
                if (f.rotation != 0) face["uv_rotation"] = f.rotation.toLong()
                face["material_instance"] = materialName(f.texture)
                uv[dir] = face
            }
            cube["uv"] = uv
            cubes.add(cube)
        }
        return cubes
    }

    /** Nome del materiale Bedrock per una variabile Java («#0» → «t_0»). */
    fun materialName(javaVar: String): String {
        val raw = javaVar.removePrefix("#")
        val clean = raw.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
        return "t_$clean"
    }

    /** Le regole di Bedrock sulla grandezza di una forma: entro 30 pixel dal centro su ogni asse e al massimo 30 pixel per lato. */
    fun fitsBedrock(elements: List<JElement>): Boolean {
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        var minZ = Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        for (e in elements) {
            minX = minOf(minX, e.from[0], e.to[0]); maxX = maxOf(maxX, e.from[0], e.to[0])
            minY = minOf(minY, e.from[1], e.to[1]); maxY = maxOf(maxY, e.from[1], e.to[1])
            minZ = minOf(minZ, e.from[2], e.to[2]); maxZ = maxOf(maxZ, e.from[2], e.to[2])
        }
        if (maxX - minX > 30 || maxY - minY > 30 || maxZ - minZ > 30) return false
        if (minX - 8 < -30 || maxX - 8 > 30 || minZ - 8 < -30 || maxZ - 8 > 30 || minY < -30 || maxY > 30) return false
        return true
    }

    /** Il riquadro di collisione che contiene tutti gli elementi, nel formato di Bedrock (origine al centro del blocco). */
    fun bounds(elements: List<JElement>): Pair<List<Double>, List<Double>>? {
        if (elements.isEmpty()) return null
        val min = doubleArrayOf(16.0, 16.0, 16.0); val max = doubleArrayOf(0.0, 0.0, 0.0)
        for (e in elements) for (i in 0..2) {
            min[i] = minOf(min[i], minOf(e.from[i], e.to[i]).coerceIn(0.0, 16.0))
            max[i] = maxOf(max[i], maxOf(e.from[i], e.to[i]).coerceIn(0.0, 16.0))
        }
        val size = (0..2).map { (max[it] - min[it]).coerceAtLeast(0.0) }
        if (size.any { it <= 0.0 }) return null
        return Pair(listOf(min[0] - 8, min[1], min[2] - 8), size)
    }
}
