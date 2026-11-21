package com.mmi.modinstaller.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** L'icona disegnata da un modello 3D: un cubo con la posa dei blocchi di Java si vede di tre quarti. */
class IconRenderTest {
    @Test fun cubeSeenLikeTheJavaInventory() {
        val red = Image(16, 16, ByteArray(16 * 16 * 4) { i -> when (i % 4) { 0 -> 200.toByte(); 3 -> 255.toByte(); else -> 0 } })
        val faces = JElement.DIRECTIONS.associateWith { JFace(null, "#all", 0, false) }
        val cube = JElement(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(16.0, 16.0, 16.0), null, null, 0.0, faces)
        val geo = ModelResolver.ModelGeometry(listOf(cube), mapOf("all" to Rl("t", "block/red")), false)
        val img = IconRenderer.render(IconJob(geo, GuiTransform.BLOCK), { red }, 32)
        assertNotNull(img)
        fun px(x: Int, y: Int) = (0..3).map { img!!.rgba[(y * 32 + x) * 4 + it].toInt() and 0xFF }
        assertEquals(255, px(16, 16)[3]) // il centro è pieno
        assertEquals(0, px(0, 0)[3]) // gli angoli sono vuoti (il cubo è di tre quarti e rimpicciolito)
        assertEquals(0, px(31, 31)[3])
        // Sopra (in alto al centro) più chiaro dei lati (in basso a sinistra e a destra), come la luce dei blocchi.
        val top = px(16, 9)[0]; val left = px(10, 20)[0]; val right = px(22, 20)[0]
        assertTrue("$top $left $right", top > left && top > right && left != right)
    }
}
