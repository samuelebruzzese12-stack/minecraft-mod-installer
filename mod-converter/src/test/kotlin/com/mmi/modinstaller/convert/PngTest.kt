package com.mmi.modinstaller.convert

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Il lettore PNG senza librerie, confrontato con i PNG che scrive Java (ImageIO) nei formati che usano le texture vere. */
class PngTest {

    private fun write(img: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()

    @Test fun roundTripRgba() {
        val px = ByteArray(5 * 3 * 4) { (it * 37 % 256).toByte() }
        val bytes = Png.encode(Image(5, 3, px))
        val back = Png.decode(bytes)!!
        assertEquals(5, back.width); assertEquals(3, back.height)
        assertArrayEquals(px, back.rgba)
        assertEquals(Pair(5, 3), Png.size(bytes))
    }

    @Test fun readsWhatJavaWrites() {
        val argb = BufferedImage(7, 5, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 5) for (x in 0 until 7) argb.setRGB(x, y, ((if (x == 0) 0 else if (x == 1) 100 else 255) shl 24) or (x * 30 shl 16) or (y * 40 shl 8) or 7)
        val a = Png.decode(write(argb))!!
        for (y in 0 until 5) for (x in 0 until 7) {
            val o = (y * 7 + x) * 4
            val expected = argb.getRGB(x, y)
            assertEquals((expected ushr 24) and 0xFF, a.rgba[o + 3].toInt() and 0xFF)
            if ((expected ushr 24) != 0) assertEquals((expected shr 16) and 0xFF, a.rgba[o].toInt() and 0xFF)
        }
        assertEquals(Transparency.TRANSLUCENT, Png.transparency(a))

        val rgb = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB).apply { setRGB(1, 2, 0x123456) }
        val b = Png.decode(write(rgb))!!
        assertEquals(0x12, b.rgba[(2 * 4 + 1) * 4].toInt() and 0xFF)
        assertEquals(Transparency.OPAQUE, Png.transparency(b))

        val gray = BufferedImage(3, 3, BufferedImage.TYPE_BYTE_GRAY).apply { raster.setSample(2, 1, 0, 200) }
        assertEquals(200, Png.decode(write(gray))!!.rgba[(1 * 3 + 2) * 4].toInt() and 0xFF)

        val gray16 = BufferedImage(3, 3, BufferedImage.TYPE_USHORT_GRAY).apply { raster.setSample(0, 0, 0, 0xABCD) }
        assertEquals(0xAB, Png.decode(write(gray16))!!.rgba[0].toInt() and 0xFF)

        // Tavolozza con un colore trasparente (come molte texture ottimizzate)
        val cm = IndexColorModel(2, 3, byteArrayOf(0, 255.toByte(), 0), byteArrayOf(0, 0, 255.toByte()), byteArrayOf(0, 0, 0), byteArrayOf(0, 255.toByte(), 255.toByte()))
        val pal = BufferedImage(6, 2, BufferedImage.TYPE_BYTE_BINARY, cm).apply { raster.setSample(1, 0, 0, 1); raster.setSample(2, 1, 0, 2) }
        val p = Png.decode(write(pal))!!
        assertEquals(255, p.rgba[(0 * 6 + 1) * 4].toInt() and 0xFF)
        assertEquals(255, p.rgba[(1 * 6 + 2) * 4 + 1].toInt() and 0xFF)
        assertEquals(0, p.rgba[3].toInt() and 0xFF)
        assertEquals(Transparency.CUTOUT, Png.transparency(p))
    }

    @Test fun cropKeepsTheFirstFrame() {
        val px = ByteArray(2 * 4 * 4) { if (it < 2 * 2 * 4) 1 else 2 }
        val c = Image(2, 4, px).crop(0, 2)
        assertEquals(2, c.height)
        assertEquals(setOf<Byte>(1), c.rgba.toSet())
    }

    @Test fun rejectsGarbage() {
        assertNull(Png.decode(byteArrayOf(1, 2, 3)))
        assertNull(Png.decode(Png.encode(Image(2, 2, ByteArray(16))).copyOf(30)))
    }
}
