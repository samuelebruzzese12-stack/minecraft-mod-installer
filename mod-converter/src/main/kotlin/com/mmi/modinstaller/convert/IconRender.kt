package com.mmi.modinstaller.convert

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** La posa «gui» di un modello Java (display.gui): rotazione in gradi (X, Y, Z), spostamento in 1/16 di blocco, scala. */
class GuiTransform(val rotation: DoubleArray, val translation: DoubleArray, val scale: DoubleArray) {
    companion object {
        /** Quella dei blocchi di Minecraft (block/block): la vista di tre quarti dell'inventario. */
        val BLOCK = GuiTransform(doubleArrayOf(30.0, 225.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.625, 0.625, 0.625))
        val NONE = GuiTransform(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 1.0, 1.0))

        fun parse(o: Map<*, *>?): GuiTransform? {
            if (o == null) return null
            fun v(k: String, d: Double): DoubleArray =
                (o[k] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.size == 3 }?.toDoubleArray() ?: doubleArrayOf(d, d, d)
            return GuiTransform(v("rotation", 0.0), v("translation", 0.0), v("scale", 1.0))
        }
    }
}

/** Un'icona da disegnare dal modello 3D di un oggetto. */
internal class IconJob(val geometry: ModelResolver.ModelGeometry, val gui: GuiTransform)

/**
 * Disegna l'icona di un oggetto con modello 3D come la disegna Minecraft Java nell'inventario: gli elementi del modello con
 * le loro rotazioni, la posa «gui» del modello (rotazione X·Y·Z, spostamento, scala attorno al centro), vista dall'alto
 * in proiezione ortogonale, facce nascoste tolte con lo z-buffer, ombreggiatura delle facce come quella dei blocchi.
 */
internal object IconRenderer {
    private val SHADE = mapOf("up" to 1.0, "down" to 0.5, "north" to 0.8, "south" to 0.8, "west" to 0.6, "east" to 0.6)

    fun render(job: IconJob, texture: (Rl) -> Image?, size: Int = 32): Image? {
        val g = job.geometry
        val images = HashMap<String, Image?>()
        fun tex(ref: String): Image? = images.getOrPut(ref) {
            val rl = g.textures[ref.removePrefix("#")] ?: return@getOrPut null
            texture(rl)?.let { if (it.height > it.width) it.crop(0, it.width) else it }
        }
        val out = ByteArray(size * size * 4)
        val depth = DoubleArray(size * size) { Double.NEGATIVE_INFINITY }
        val t = job.gui
        val rx = Math.toRadians(t.rotation[0]); val ry = Math.toRadians(t.rotation[1]); val rz = Math.toRadians(t.rotation[2])
        // v' = T · Rx · Ry · Rz · S · (v - 0,5): come ItemTransform.apply e la traslazione di -0,5 di ItemRenderer.
        fun place(p: DoubleArray): DoubleArray {
            var x = (p[0] / 16.0 - 0.5) * t.scale[0]
            var y = (p[1] / 16.0 - 0.5) * t.scale[1]
            var z = (p[2] / 16.0 - 0.5) * t.scale[2]
            var c = cos(rz); var s = sin(rz)
            var nx = x * c - y * s; var ny = x * s + y * c; x = nx; y = ny
            c = cos(ry); s = sin(ry)
            nx = x * c + z * s; var nz = -x * s + z * c; x = nx; z = nz
            c = cos(rx); s = sin(rx)
            ny = y * c - z * s; nz = y * s + z * c; y = ny; z = nz
            return doubleArrayOf(x + t.translation[0] / 16.0, y + t.translation[1] / 16.0, z + t.translation[2] / 16.0)
        }
        var drawn = 0
        for (e in g.elements) {
            val rot = elementRotation(e)
            for ((dir, face) in e.faces) {
                val img = tex(face.texture) ?: continue
                val corners = faceCorners(e, dir).map { place(rot(it)) }
                val uv = face.uv ?: Geometry.defaultUv(e, dir)
                val shade = SHADE[dir] ?: 1.0
                // Pixel dello schermo: x da sinistra, y dall'alto; il modello sta in un quadrato di lato 1 attorno al centro.
                val sx = corners.map { (it[0] + 0.5) * size }; val sy = corners.map { (0.5 - it[1]) * size }
                val x0 = max(0, sx.minOrNull()!!.toInt() - 1); val x1 = min(size - 1, sx.maxOrNull()!!.toInt() + 1)
                val y0 = max(0, sy.minOrNull()!!.toInt() - 1); val y1 = min(size - 1, sy.maxOrNull()!!.toInt() + 1)
                // Coordinate (s, t) della faccia dal punto dello schermo: inversa della trasformazione affine dei due lati.
                val ex = sx[1] - sx[0]; val ey = sy[1] - sy[0]; val fx = sx[3] - sx[0]; val fy = sy[3] - sy[0]
                // Il segno dice se la faccia guarda lo schermo: vista da dietro si salta (come il «culling» di Minecraft).
                val det = ex * fy - ey * fx
                if (det < 1e-9) continue
                for (py in y0..y1) for (px in x0..x1) {
                    val qx = px + 0.5 - sx[0]; val qy = py + 0.5 - sy[0]
                    val s = (qx * fy - qy * fx) / det
                    val tt = (ex * qy - ey * qx) / det
                    if (s < 0 || s > 1 || tt < 0 || tt > 1) continue
                    val z = corners[0][2] + s * (corners[1][2] - corners[0][2]) + tt * (corners[3][2] - corners[0][2])
                    val i = py * size + px
                    if (z <= depth[i]) continue
                    val (fs, ft) = when (((face.rotation % 360) + 360) % 360) {
                        90 -> tt to 1 - s
                        180 -> 1 - s to 1 - tt
                        270 -> 1 - tt to s
                        else -> s to tt
                    }
                    val u = (uv[0] + fs * (uv[2] - uv[0])) / 16.0
                    val v = (uv[1] + ft * (uv[3] - uv[1])) / 16.0
                    val tx = (u * img.width).toInt().coerceIn(0, img.width - 1)
                    val ty = (v * img.height).toInt().coerceIn(0, img.height - 1)
                    val k = (ty * img.width + tx) * 4
                    val a = img.rgba[k + 3].toInt() and 0xFF
                    if (a < 128) continue
                    depth[i] = z
                    for (ch in 0..2) out[i * 4 + ch] = ((img.rgba[k + ch].toInt() and 0xFF) * shade).toInt().coerceIn(0, 255).toByte()
                    out[i * 4 + 3] = 255.toByte()
                    drawn++
                }
            }
        }
        return if (drawn == 0) null else Image(size, size, out)
    }

    /** La rotazione propria dell'elemento (asse, angolo, origine), come FaceBakery. */
    private fun elementRotation(e: JElement): (DoubleArray) -> DoubleArray {
        val axis = e.rotAxis ?: return { it }
        if (e.rotAngle == 0.0) return { it }
        val o = e.rotOrigin ?: doubleArrayOf(8.0, 8.0, 8.0)
        val a = Math.toRadians(e.rotAngle)
        val c = cos(a); val s = sin(a)
        return { p ->
            val x = p[0] - o[0]; val y = p[1] - o[1]; val z = p[2] - o[2]
            when (axis) {
                'x' -> doubleArrayOf(p[0], o[1] + y * c - z * s, o[2] + y * s + z * c)
                'y' -> doubleArrayOf(o[0] + x * c + z * s, p[1], o[2] - x * s + z * c)
                else -> doubleArrayOf(o[0] + x * c - y * s, o[1] + x * s + y * c, p[2])
            }
        }
    }

    /**
     * I quattro angoli di una faccia: [0] in alto a sinistra della texture, [1] lungo u, [2] opposto, [3] lungo v (guardando la
     * faccia da fuori, come le coordinate «uv» di Java).
     */
    private fun faceCorners(e: JElement, dir: String): List<DoubleArray> {
        val f = e.from; val t = e.to
        fun p(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
        return when (dir) {
            "north" -> listOf(p(t[0], t[1], f[2]), p(f[0], t[1], f[2]), p(f[0], f[1], f[2]), p(t[0], f[1], f[2]))
            "south" -> listOf(p(f[0], t[1], t[2]), p(t[0], t[1], t[2]), p(t[0], f[1], t[2]), p(f[0], f[1], t[2]))
            "west" -> listOf(p(f[0], t[1], f[2]), p(f[0], t[1], t[2]), p(f[0], f[1], t[2]), p(f[0], f[1], f[2]))
            "east" -> listOf(p(t[0], t[1], t[2]), p(t[0], t[1], f[2]), p(t[0], f[1], f[2]), p(t[0], f[1], t[2]))
            "up" -> listOf(p(f[0], t[1], f[2]), p(t[0], t[1], f[2]), p(t[0], t[1], t[2]), p(f[0], t[1], t[2]))
            else -> listOf(p(f[0], f[1], t[2]), p(t[0], f[1], t[2]), p(t[0], f[1], f[2]), p(f[0], f[1], f[2]))
        }
    }
}
