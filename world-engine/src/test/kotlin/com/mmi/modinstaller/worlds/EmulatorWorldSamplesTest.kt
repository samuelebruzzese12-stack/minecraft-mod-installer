package com.mmi.modinstaller.worlds

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * I mondi della prova su emulatore (ci/make_samples.py) letti e importati con il motore vero: controlla PRIMA che le attese di
 * ci/worlds_phase.sh siano quelle che l'app produrrà. Si esegue solo se MMI_SAMPLES indica la cartella dei file di prova:
 *   python3 ci/make_samples.py /tmp/samples && MMI_SAMPLES=/tmp/samples gradle :world-engine:test
 */
class EmulatorWorldSamplesTest {

    private lateinit var dir: File
    private lateinit var base: File

    @Before fun setUp() {
        val d = System.getenv("MMI_SAMPLES")?.let { File(it) }
        assumeTrue("MMI_SAMPLES non impostata: prova saltata", d != null && d.isDirectory)
        dir = d!!
        base = WFx.tempDir()
    }

    @After fun tearDown() {
        if (::base.isInitialized) WFx.deleteTree(base)
    }

    private fun only(name: String): WorldInfo {
        val r = WorldScanner.scan(File(dir, name))
        assertTrue(name + ": " + (r as? WorldScanResult.Failed)?.message, r is WorldScanResult.Ok)
        return (r as WorldScanResult.Ok).worlds.single()
    }

    @Test fun theJavaWorldIsDescribedAsTheScriptExpects() {
        val w = only("Mondo_Java.zip")
        assertEquals(WorldEdition.JAVA, w.edition)
        assertEquals("Mondo Java di Prova", w.root)
        assertEquals("Mondo Java di Prova", w.title)
        assertEquals("1.20.1", w.version)
        assertEquals("Sopravvivenza", w.gameModeLabel)
        assertEquals(listOf("Mondo principale", "Nether", "End"), w.dimensions.map { it.label })
        assertEquals(listOf(2, 1, 1), w.dimensions.map { it.regionFiles })
        assertEquals(1, w.players)
        assertEquals(listOf("pacchetto_di_prova.zip"), w.datapacks)
        // level.dat, icon.png, 4 regioni, un giocatore, un data pack (session.lock no)
        assertEquals(8, w.files)
        assertTrue(w.notes.any { it.text.contains("1 file fuori dal mondo") })
        assertTrue(w.notes.any { it.text.contains("session.lock non viene copiato") })
        assertNull(WorldVersions.warning(w.version, "1.20.1"))
    }

    @Test fun theRecentWorldWarnsAgainstTheTestProfile() {
        val w = only("Mondo_Java_Recente.zip")
        assertEquals("Mondo Recente", w.title)
        assertEquals("1.21.1", w.version)
        assertTrue(WorldVersions.warning(w.version, "1.20.1")!!.contains("più recente del profilo"))
    }

    @Test fun theBigWorldHasManyFilesAndImportsCompletely() {
        val w = only("Mondo_Java_Grande.zip")
        assertEquals("Mondo Grande", w.title)
        assertEquals(160, w.dimensions.first().regionFiles)
        assertEquals(166, w.files)
        assertTrue(w.bytes > 20L * 1024 * 1024)
        val saves = File(base, "saves")
        val r = WorldImporter(reserveBytes = 0).importJava(File(dir, "Mondo_Java_Grande.zip"), w, saves, w.title, "prova")
        assertTrue(r.fatal, r.ok)
        assertEquals(File(saves, "Mondo Grande"), r.worldDir)
        ZipFile(File(dir, "Mondo_Java_Grande.zip")).use { z ->
            val e = z.getEntry("Mondo Grande/region/r.159.0.mca")
            assertTrue(z.getInputStream(e).readBytes().contentEquals(File(r.worldDir, "region/r.159.0.mca").readBytes()))
        }
    }

    @Test fun theBedrockWorldGoesToTheBedrockInstaller() {
        val w = only("Mondo_di_Prova.mcworld")
        assertEquals(WorldEdition.BEDROCK, w.edition)
        assertEquals("Mondo di Prova", w.title)
        assertTrue(w.importable)
        assertTrue(w.notes.any { it.text.contains("INSTALLER BEDROCK") })
    }
}
