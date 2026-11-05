package com.mmi.modinstaller.convert

import com.mmi.modinstaller.convert.code.AnimFacts
import com.mmi.modinstaller.convert.code.ChannelFacts
import com.mmi.modinstaller.convert.code.CubeFacts
import com.mmi.modinstaller.convert.code.KeyFacts
import com.mmi.modinstaller.convert.code.ModelFacts
import com.mmi.modinstaller.convert.code.PartFacts
import com.mmi.modinstaller.core.Json
import com.mmi.modinstaller.core.asJsonList
import com.mmi.modinstaller.core.asJsonObject
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Modelli Tabula (`.tbl`, uno zip con `model.json`): li usa Ice and Fire per draghi e serpenti marini, con un file per ogni
 * «posa» (walk1…4, flight1…6, swim1…5…). Le convenzioni sono quelle dei ModelRenderer di Java (punto di rotazione, scostamento
 * del cubo, rotazioni in gradi), quindi diventano le stesse PartFacts dei modelli scritti nel codice; le pose in sequenza
 * diventano animazioni a fotogrammi (differenza tra la posa e il modello a riposo, come fa la mod).
 */
internal object TabulaReader {

    private const val MAX = 8 * 1024 * 1024

    /** Il modello di una creatura: cartella `models/tabula/<nome senza trattini bassi>/`, file a riposo `_ground` o `_base`. */
    fun forEntity(jar: JavaJar, id: Rl, names: Set<String>): Pair<ModelFacts, String>? {
        val folder = id.path.substringAfterLast('/').replace("_", "")
        val prefix = "assets/${id.ns}/models/tabula/$folder/"
        val files = names.filter { it.startsWith(prefix) && it.endsWith(".tbl") }.sorted()
        if (files.isEmpty()) return null
        val base = listOf("_ground", "_base", "_base_male", "").firstNotNullOfOrNull { s -> files.firstOrNull { it == "$prefix$folder$s.tbl" } } ?: files.first()
        val rest = read(jar, base) ?: return null
        val anims = ArrayList<AnimFacts>()
        for ((role, stem, seconds) in listOf(Triple("walk", "walk", 1.2), Triple("fly", "flight", 1.5), Triple("swim", "swim", 1.2))) {
            val poses = files.filter { Regex(Regex.escape("$prefix$folder" + "_" + stem) + """\d+\.tbl""").matches(it) }
                .sortedBy { it.substringAfterLast(stem).removeSuffix(".tbl").toIntOrNull() ?: 0 }
            if (poses.size < 2) continue
            val models = poses.mapNotNull { read(jar, it) }
            if (models.size < 2) continue
            anims.add(cycle(role, rest, models, seconds))
        }
        return ModelFacts("tabula:$base", rest.texWidth, rest.texHeight, rest.root, anims, emptyList(), "tabula") to folder
    }

    /** La texture: nella cartella delle texture della creatura, la prima variante «adulta» (né armatura, né uovo, né occhi). */
    fun texture(id: Rl, folder: String, names: Set<String>): Rl? {
        val prefix = "assets/${id.ns}/textures/models/$folder/"
        val bad = Regex("armor|egg|eyes|sleeping|skeleton|breath|empty|horn|mouth|charge|blink|saddle|chest|overlay")
        val pngs = names.filter { it.startsWith(prefix) && it.endsWith(".png") && it.count { c -> c == '/' } == prefix.count { c -> c == '/' } }
            .filter { !bad.containsMatchIn(it.substringAfterLast('/')) }.sorted()
        val pick = pngs.firstOrNull { Regex("""_[45]\.png$""").containsMatchIn(it) } ?: pngs.firstOrNull() ?: return null
        return Rl(id.ns, pick.removePrefix("assets/${id.ns}/textures/").removeSuffix(".png"))
    }

    private class Model(val texWidth: Int, val texHeight: Int, val root: PartFacts)

    private fun read(jar: JavaJar, entry: String): Model? {
        val bytes = jar.bytes(entry, MAX) ?: return null
        val json = try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                var e = z.nextEntry
                var out: String? = null
                while (e != null) {
                    if (e.name == "model.json") { out = String(z.readBytes(), Charsets.UTF_8); break }
                    e = z.nextEntry
                }
                out
            }
        } catch (_: Exception) { null } ?: return null
        val o = (try { Json.parse(json) } catch (_: Exception) { null }).asJsonObject() ?: return null
        val used = HashSet<String>()
        fun part(c: Map<String, Any?>, depth: Int): PartFacts? {
            if (depth > 64) return null
            fun v(k: String) = c[k].asJsonList()?.map { (it as? Number)?.toDouble() ?: 0.0 } ?: listOf(0.0, 0.0, 0.0)
            val dim = v("dimensions"); val pos = v("position"); val off = v("offset"); val rot = v("rotation")
            val tx = c["txOffset"].asJsonList()?.map { (it as? Number)?.toInt() ?: 0 } ?: listOf(0, 0)
            var name = ((c["name"] as? String) ?: "bone").replace(Regex("[^A-Za-z0-9_]"), "_").ifEmpty { "bone" }
            var i = 2
            val base = name
            while (!used.add(name)) name = base + "_" + i++
            val cube = CubeFacts(
                tx.getOrElse(0) { 0 }, tx.getOrElse(1) { 0 }, off[0], off[1], off[2], dim[0], dim[1], dim[2],
                (c["mcScale"] as? Number)?.toDouble() ?: 0.0, c["txMirror"] == true
            )
            val children = c["children"].asJsonList().orEmpty().mapNotNull { it.asJsonObject()?.let { ch -> part(ch, depth + 1) } }
            return PartFacts(
                name, pos[0], pos[1], pos[2], Math.toRadians(rot[0]), Math.toRadians(rot[1]), Math.toRadians(rot[2]),
                if (c["hidden"] == true) emptyList() else listOf(cube), children
            )
        }
        val cubes = (o["cubes"].asJsonList().orEmpty() + o["cubeGroups"].asJsonList().orEmpty().flatMap { g -> g.asJsonObject()?.get("cubes").asJsonList().orEmpty() })
            .mapNotNull { it.asJsonObject()?.let { c -> part(c, 0) } }
        if (cubes.isEmpty()) return null
        val tw = (o["textureWidth"] as? Number)?.toInt() ?: 64
        val th = (o["textureHeight"] as? Number)?.toInt() ?: 32
        return Model(tw, th, PartFacts("root", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList(), cubes))
    }

    private fun flatten(p: PartFacts, out: MutableMap<String, PartFacts> = LinkedHashMap()): Map<String, PartFacts> {
        out[p.name] = p
        for (c in p.children) flatten(c, out)
        return out
    }

    /** Una camminata (o un volo, o una nuotata) fatta dalle pose in fila: posa − riposo per ogni osso, in ciclo. */
    private fun cycle(role: String, rest: Model, poses: List<Model>, seconds: Double): AnimFacts {
        val base = flatten(rest.root)
        val frames = poses.map { flatten(it.root) }
        val step = seconds / poses.size
        val channels = ArrayList<ChannelFacts>()
        for ((name, b) in base) {
            if (name == "root") continue
            val rot = ArrayList<KeyFacts>()
            val pos = ArrayList<KeyFacts>()
            var moves = false
            for ((i, f) in (frames + frames.first()).withIndex()) {
                val p = f[name] ?: b
                val dr = doubleArrayOf(Math.toDegrees(p.rx - b.rx), Math.toDegrees(p.ry - b.ry), Math.toDegrees(p.rz - b.rz))
                val dp = doubleArrayOf(p.x - b.x, -(p.y - b.y), p.z - b.z)
                if (dr.any { Math.abs(it) > 0.01 } || dp.any { Math.abs(it) > 0.01 }) moves = true
                rot.add(KeyFacts(i * step, dr[0], dr[1], dr[2], "linear"))
                pos.add(KeyFacts(i * step, dp[0], dp[1], dp[2], "linear"))
            }
            if (!moves) continue
            if (rot.any { k -> Math.abs(k.x) > 0.01 || Math.abs(k.y) > 0.01 || Math.abs(k.z) > 0.01 }) channels.add(ChannelFacts(name, "rotation", rot))
            if (pos.any { k -> Math.abs(k.x) > 0.01 || Math.abs(k.y) > 0.01 || Math.abs(k.z) > 0.01 }) channels.add(ChannelFacts(name, "position", pos))
        }
        return AnimFacts(role, seconds, true, channels, role)
    }
}
