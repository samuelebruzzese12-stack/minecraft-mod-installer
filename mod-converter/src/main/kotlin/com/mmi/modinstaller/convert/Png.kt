package com.mmi.modinstaller.convert

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Un'immagine in memoria: [rgba] ha 4 byte per pixel, riga dopo riga. */
class Image(val width: Int, val height: Int, val rgba: ByteArray) {

    fun alpha(x: Int, y: Int): Int = rgba[(y * width + x) * 4 + 3].toInt() and 0xFF

    /** La parte alta [h] pixel a partire dalla riga [top] (per prendere il primo fotogramma di una texture animata). */
    fun crop(top: Int, h: Int): Image {
        val rows = h.coerceAtMost(height - top).coerceAtLeast(0)
        val out = ByteArray(width * rows * 4)
        System.arraycopy(rgba, top * width * 4, out, 0, out.size)
        return Image(width, rows, out)
    }
}

/** Come sono fatti i bordi trasparenti di una texture: serve a scegliere il modo di disegno del blocco in Bedrock. */
enum class Transparency { OPAQUE, CUTOUT, TRANSLUCENT }

/**
 * Lettura e scrittura minime del formato PNG, senza librerie grafiche (su Android `javax.imageio` non c'è). Legge tutti i tipi
 * di colore a 1, 2, 4, 8 e 16 bit, con tavolozza e trasparenza (tRNS), anche interlacciati (Adam7); scrive RGBA a 8 bit.
 */
object Png {

    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    /** Oltre questo numero di pixel non si decodifica (una texture di Minecraft è molto più piccola). */
    const val MAX_PIXELS = 4096 * 4096

    fun isPng(bytes: ByteArray): Boolean = bytes.size >= 8 && (0 until 8).all { bytes[it] == SIGNATURE[it] }

    /** Larghezza e altezza lette dall'intestazione, senza decodificare. */
    fun size(bytes: ByteArray): Pair<Int, Int>? {
        if (!isPng(bytes) || bytes.size < 24) return null
        return Pair(int(bytes, 16), int(bytes, 20))
    }

    /** Null se il file non è un PNG valido o è troppo grande. */
    fun decode(bytes: ByteArray): Image? = try {
        decodeOrThrow(bytes)
    } catch (e: Exception) {
        null
    }

    fun transparency(image: Image): Transparency {
        var cutout = false
        for (i in 3 until image.rgba.size step 4) {
            val a = image.rgba[i].toInt() and 0xFF
            if (a in 1..254) return Transparency.TRANSLUCENT
            if (a == 0) cutout = true
        }
        return if (cutout) Transparency.CUTOUT else Transparency.OPAQUE
    }

    private fun int(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun decodeOrThrow(bytes: ByteArray): Image? {
        if (!isPng(bytes)) return null
        var pos = 8
        var width = 0
        var height = 0
        var depth = 0
        var colorType = 0
        var interlace = 0
        var palette: ByteArray? = null
        var trns: ByteArray? = null
        val idat = ByteArrayOutputStream()
        while (pos + 8 <= bytes.size) {
            val len = int(bytes, pos)
            if (len < 0 || pos + 12 + len > bytes.size) return null
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            val data = pos + 8
            when (type) {
                "IHDR" -> {
                    width = int(bytes, data); height = int(bytes, data + 4)
                    depth = bytes[data + 8].toInt() and 0xFF; colorType = bytes[data + 9].toInt() and 0xFF
                    interlace = bytes[data + 12].toInt() and 0xFF
                }
                "PLTE" -> palette = bytes.copyOfRange(data, data + len)
                "tRNS" -> trns = bytes.copyOfRange(data, data + len)
                "IDAT" -> idat.write(bytes, data, len)
                "IEND" -> pos = bytes.size
            }
            pos += 12 + len
        }
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) return null
        val channels = when (colorType) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> return null }
        if (depth !in setOf(1, 2, 4, 8, 16) || (colorType == 3 && palette == null)) return null
        val bitsPerPixel = channels * depth
        val raw = inflate(idat.toByteArray(), expectedSize(width, height, bitsPerPixel, interlace)) ?: return null
        val out = ByteArray(width * height * 4)
        if (interlace == 0) {
            unfilterInto(raw, 0, width, height, bitsPerPixel) { x, y, line, bit -> putPixel(out, width, x, y, line, bit, colorType, depth, palette, trns) }
        } else {
            var offset = 0
            for (pass in 0 until 7) {
                val sx = ADAM_X[pass]; val sy = ADAM_Y[pass]; val dx = ADAM_DX[pass]; val dy = ADAM_DY[pass]
                val pw = (width - sx + dx - 1) / dx
                val ph = (height - sy + dy - 1) / dy
                if (pw <= 0 || ph <= 0) continue
                offset = unfilterInto(raw, offset, pw, ph, bitsPerPixel) { x, y, line, bit ->
                    putPixel(out, width, sx + x * dx, sy + y * dy, line, bit, colorType, depth, palette, trns)
                }
            }
        }
        return Image(width, height, out)
    }

    private val ADAM_X = intArrayOf(0, 4, 0, 2, 0, 1, 0)
    private val ADAM_Y = intArrayOf(0, 0, 4, 0, 2, 0, 1)
    private val ADAM_DX = intArrayOf(8, 8, 4, 4, 2, 2, 1)
    private val ADAM_DY = intArrayOf(8, 8, 8, 4, 4, 2, 2)

    private fun expectedSize(w: Int, h: Int, bpp: Int, interlace: Int): Int {
        if (interlace == 0) return h * (1 + (w * bpp + 7) / 8)
        var total = 0
        for (p in 0 until 7) {
            val pw = (w - ADAM_X[p] + ADAM_DX[p] - 1) / ADAM_DX[p]
            val ph = (h - ADAM_Y[p] + ADAM_DY[p] - 1) / ADAM_DY[p]
            if (pw > 0 && ph > 0) total += ph * (1 + (pw * bpp + 7) / 8)
        }
        return total
    }

    private fun inflate(data: ByteArray, expected: Int): ByteArray? {
        val inf = Inflater()
        return try {
            inf.setInput(data)
            val out = ByteArray(expected)
            var n = 0
            while (n < expected && !inf.finished()) {
                val r = inf.inflate(out, n, expected - n)
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break
                n += r
            }
            if (n < expected) null else out
        } catch (e: Exception) {
            null
        } finally {
            inf.end()
        }
    }

    /** Toglie i filtri PNG riga per riga e chiama [pixel] per ogni pixel; restituisce la posizione dopo l'immagine. */
    private inline fun unfilterInto(
        raw: ByteArray,
        start: Int,
        w: Int,
        h: Int,
        bpp: Int,
        pixel: (x: Int, y: Int, line: ByteArray, bit: Int) -> Unit
    ): Int {
        val stride = (w * bpp + 7) / 8
        val step = maxOf(1, bpp / 8)
        var prev = ByteArray(stride)
        var cur = ByteArray(stride)
        var p = start
        for (y in 0 until h) {
            val filter = raw[p].toInt() and 0xFF
            p++
            for (i in 0 until stride) {
                val x = raw[p + i].toInt() and 0xFF
                val a = if (i >= step) cur[i - step].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i >= step) prev[i - step].toInt() and 0xFF else 0
                val v = when (filter) {
                    0 -> x
                    1 -> x + a
                    2 -> x + b
                    3 -> x + ((a + b) ushr 1)
                    4 -> {
                        val pp = a + b - c
                        val pa = Math.abs(pp - a); val pb = Math.abs(pp - b); val pc = Math.abs(pp - c)
                        x + if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                    }
                    else -> throw IllegalArgumentException("filtro PNG $filter")
                }
                cur[i] = v.toByte()
            }
            p += stride
            for (x in 0 until w) pixel(x, y, cur, x * bpp)
            val t = prev; prev = cur; cur = t
        }
        return p
    }

    private fun sample(line: ByteArray, bit: Int, depth: Int): Int = when (depth) {
        8 -> line[bit / 8].toInt() and 0xFF
        16 -> line[bit / 8].toInt() and 0xFF // si tiene il byte alto
        else -> {
            val byte = line[bit / 8].toInt() and 0xFF
            val shift = 8 - depth - (bit % 8)
            (byte shr shift) and ((1 shl depth) - 1)
        }
    }

    private fun scale(v: Int, depth: Int): Int = when (depth) {
        1 -> v * 255; 2 -> v * 85; 4 -> v * 17; else -> v
    }

    private fun putPixel(
        out: ByteArray, width: Int, x: Int, y: Int, line: ByteArray, bit: Int,
        colorType: Int, depth: Int, palette: ByteArray?, trns: ByteArray?
    ) {
        val o = (y * width + x) * 4
        val bytesPer = if (depth == 16) 2 else 1
        var r: Int; var g: Int; var b: Int; var a = 255
        when (colorType) {
            0 -> {
                val raw = if (depth == 16) ((line[bit / 8].toInt() and 0xFF) shl 8) or (line[bit / 8 + 1].toInt() and 0xFF) else sample(line, bit, depth)
                val v = if (depth == 16) raw shr 8 else scale(raw, depth)
                r = v; g = v; b = v
                if (trns != null && trns.size >= 2) {
                    val t = ((trns[0].toInt() and 0xFF) shl 8) or (trns[1].toInt() and 0xFF)
                    if (raw == t) a = 0
                }
            }
            2 -> {
                r = sample(line, bit, depth); g = sample(line, bit + 8 * bytesPer, depth); b = sample(line, bit + 16 * bytesPer, depth)
                if (trns != null && trns.size >= 6 && depth == 8) {
                    if (r == (trns[1].toInt() and 0xFF) && g == (trns[3].toInt() and 0xFF) && b == (trns[5].toInt() and 0xFF)) a = 0
                }
            }
            3 -> {
                val i = sample(line, bit, depth)
                val pal = palette!!
                if (i * 3 + 2 < pal.size) {
                    r = pal[i * 3].toInt() and 0xFF; g = pal[i * 3 + 1].toInt() and 0xFF; b = pal[i * 3 + 2].toInt() and 0xFF
                } else {
                    r = 0; g = 0; b = 0
                }
                if (trns != null && i < trns.size) a = trns[i].toInt() and 0xFF
            }
            4 -> {
                val v = sample(line, bit, depth); r = v; g = v; b = v; a = sample(line, bit + 8 * bytesPer, depth)
            }
            else -> {
                r = sample(line, bit, depth); g = sample(line, bit + 8 * bytesPer, depth); b = sample(line, bit + 16 * bytesPer, depth)
                a = sample(line, bit + 24 * bytesPer, depth)
            }
        }
        out[o] = r.toByte(); out[o + 1] = g.toByte(); out[o + 2] = b.toByte(); out[o + 3] = a.toByte()
    }

    /** PNG RGBA a 8 bit, senza filtri. */
    fun encode(image: Image): ByteArray {
        val raw = ByteArray(image.height * (1 + image.width * 4))
        var p = 0
        for (y in 0 until image.height) {
            raw[p++] = 0
            System.arraycopy(image.rgba, y * image.width * 4, raw, p, image.width * 4)
            p += image.width * 4
        }
        val def = Deflater(Deflater.BEST_COMPRESSION)
        val z = ByteArrayOutputStream()
        try {
            def.setInput(raw); def.finish()
            val buf = ByteArray(64 * 1024)
            while (!def.finished()) z.write(buf, 0, def.deflate(buf))
        } finally {
            def.end()
        }
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        val ihdr = ByteArrayOutputStream()
        DataOutputStream(ihdr).apply { writeInt(image.width); writeInt(image.height); writeByte(8); writeByte(6); writeByte(0); writeByte(0); writeByte(0) }
        chunk(out, "IHDR", ihdr.toByteArray())
        chunk(out, "IDAT", z.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val d = DataOutputStream(out)
        d.writeInt(data.size)
        val t = type.toByteArray(Charsets.ISO_8859_1)
        d.write(t); d.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        d.writeInt(crc.value.toInt())
    }
}
