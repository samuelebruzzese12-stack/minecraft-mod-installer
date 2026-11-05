package com.mmi.modinstaller.worlds

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile

/** Mondi già estratti: descrizione della cartella e esportazione in ZIP (andata e ritorno). */
class WorldFolderTest {

    private lateinit var base: File
    private lateinit var saves: File

    @Before fun setUp() {
        base = WFx.tempDir()
        saves = File(base, "saves")
    }

    @After fun tearDown() {
        WFx.deleteTree(base)
    }

    private fun imported(entries: Map<String, ByteArray> = WFx.javaWorld()): File {
        val file = WFx.zip(File(base, "m.zip"), entries)
        val world = (WorldScanner.scan(file) as WorldScanResult.Ok).worlds.single()
        val r = WorldImporter(reserveBytes = 0).importJava(file, world, saves, world.title, "t1")
        assertTrue(r.fatal, r.ok)
        return r.worldDir!!
    }

    @Test fun anImportedWorldFolderIsDescribedLikeTheArchive() {
        val dir = imported()
        File(dir, "session.lock").writeBytes(byteArrayOf(1))   // il gioco lo ricrea quando apre il mondo
        val w = WorldScanner.scanFolder(dir)!!
        assertEquals("Mondo di Prova", w.title)
        assertEquals("1.20.1", w.version)
        assertEquals(WorldEdition.JAVA, w.edition)
        assertEquals(5, w.regionFiles)
        assertEquals(WFx.javaWorld().size - 1, w.files)
        assertTrue(w.notes.none { it.text.contains("session.lock") })
    }

    @Test fun aFolderWithoutLevelDatIsNotAWorld() {
        val dir = File(saves, "vuota").also { it.mkdirs() }
        File(dir, "region").mkdirs()
        assertNull(WorldScanner.scanFolder(dir))
        assertNull(WorldScanner.scanFolder(File(saves, "non-esiste")))
    }

    @Test fun anExportedWorldIsTheSameWorldInsideItsFolder() {
        val entries = WFx.javaWorld()
        val dir = imported(entries)
        File(dir, "session.lock").writeBytes(byteArrayOf(1))
        val zipFile = File(base, "export.zip")
        var calls = 0
        val summary = zipFile.outputStream().use { WorldExporter.zip(dir, it, "Mondo di Prova", progress = { _, _, _ -> calls++ }) }
        assertEquals(entries.size - 1, summary.files)
        assertEquals(entries.filterKeys { it != "session.lock" }.values.sumOf { it.size.toLong() }, summary.bytes)
        assertEquals(summary.files, calls)
        ZipFile(zipFile).use { z ->
            val names = z.entries().toList().map { it.name }.toSet()
            assertEquals(entries.keys.filter { it != "session.lock" }.map { "Mondo di Prova/$it" }.toSet(), names)
            for ((rel, data) in entries) {
                if (rel == "session.lock") continue
                assertArrayEquals(rel, data, z.getInputStream(z.getEntry("Mondo di Prova/$rel")).readBytes())
            }
        }
        // Andata e ritorno: lo ZIP esportato si riconosce e si reimporta.
        val again = (WorldScanner.scan(zipFile) as WorldScanResult.Ok).worlds.single()
        assertEquals("Mondo di Prova", again.root)
        assertEquals(summary.files, again.files)
    }

    @Test fun anExportCanBeCancelled() {
        val dir = imported()
        var n = 0
        try {
            File(base, "parziale.zip").outputStream().use {
                WorldExporter.zip(dir, it, cancel = { if (++n > 3) throw CancellationException("annullata") })
            }
            fail("doveva essere annullata")
        } catch (e: CancellationException) {
            // atteso: chi chiama cancella il file incompleto
        }
    }
}
