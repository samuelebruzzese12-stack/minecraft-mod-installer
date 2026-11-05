package com.mmi.modinstaller.worlds

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class WorldScannerTest {

    private lateinit var base: File

    @Before fun setUp() {
        base = WFx.tempDir()
    }

    @After fun tearDown() {
        WFx.deleteTree(base)
    }

    private fun scan(entries: Map<String, ByteArray>, name: String = "mondo.zip"): WorldScanResult =
        WorldScanner.scan(WFx.zip(File(base, name), entries))

    private fun ok(entries: Map<String, ByteArray>): List<WorldInfo> {
        val r = scan(entries)
        assertTrue(if (r is WorldScanResult.Failed) r.message + " " + r.detail else "", r is WorldScanResult.Ok)
        return (r as WorldScanResult.Ok).worlds
    }

    private fun WorldInfo.has(level: WorldNoteLevel, text: String) = notes.any { it.level == level && it.text.contains(text) }

    @Test fun aJavaWorldAtTheRootIsDescribedWithoutExtractingIt() {
        val w = ok(WFx.javaWorld()).single()
        assertEquals("", w.root)
        assertEquals(WorldEdition.JAVA, w.edition)
        assertEquals("Mondo di Prova", w.levelName)
        assertEquals("Mondo di Prova", w.title)
        assertEquals("1.20.1", w.version)
        assertEquals(3465, w.dataVersion)
        assertEquals("Sopravvivenza", w.gameModeLabel)
        assertEquals(listOf("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", "mymod:sky"), w.dimensions.map { it.id })
        assertEquals(listOf(2, 1, 1, 1), w.dimensions.map { it.regionFiles })
        assertEquals(5, w.regionFiles)
        assertEquals(1, w.players)
        assertEquals(listOf("pacchetto.zip"), w.datapacks)
        assertTrue(w.hasIcon)
        // Tutti i file tranne session.lock.
        val all = WFx.javaWorld()
        assertEquals(all.size - 1, w.files)
        assertEquals(all.filterKeys { it != "session.lock" }.values.sumOf { it.size.toLong() }, w.bytes)
        assertTrue(w.has(WorldNoteLevel.INFO, "session.lock non viene copiato"))
        assertTrue(w.importable)
    }

    @Test fun aWorldInsideAFolderIsFoundAndFilesOutsideAreMentioned() {
        val entries = WFx.javaWorld("Il Mio Mondo")
        entries["LEGGIMI.txt"] = "ciao".toByteArray()
        val w = ok(entries).single()
        assertEquals("Il Mio Mondo", w.root)
        assertTrue(w.has(WorldNoteLevel.INFO, "1 file fuori dal mondo"))
        assertEquals(WFx.javaWorld("Il Mio Mondo").size - 1, w.files)
    }

    @Test fun twoWorldsInOneArchiveAreBothListed() {
        val entries = WFx.javaWorld("Primo", "Primo mondo")
        entries.putAll(WFx.javaWorld("Secondo", "Secondo mondo"))
        val worlds = ok(entries)
        assertEquals(listOf("Primo", "Secondo"), worlds.map { it.root })
        assertEquals(listOf("Primo mondo", "Secondo mondo"), worlds.map { it.title })
    }

    @Test fun aCopyOfLevelDatInsideAWorldIsNotASecondWorld() {
        val entries = WFx.javaWorld()
        entries["backup/level.dat"] = WFx.javaLevelDat("Copia")
        val w = ok(entries).single()
        assertEquals("Mondo di Prova", w.title)
    }

    @Test fun aBedrockWorldIsRecognizedAndSentToTheBedrockInstaller() {
        val w = ok(WFx.bedrockWorld()).single()
        assertEquals(WorldEdition.BEDROCK, w.edition)
        assertEquals("Mondo Bedrock", w.levelName)
        assertEquals("1.21.0", w.version)
        assertEquals("Creativa", w.gameModeLabel)
        assertTrue(w.hasIcon)
        assertTrue(w.dimensions.isEmpty())
        assertTrue(w.has(WorldNoteLevel.INFO, "INSTALLER BEDROCK"))
        assertTrue(w.importable)
    }

    @Test fun aBedrockWorldWithoutItsDataIsIncomplete() {
        val w = ok(WFx.bedrockWorld(withDb = false)).single()
        assertEquals(WorldEdition.BEDROCK, w.edition)
        assertTrue(w.has(WorldNoteLevel.ERROR, "Manca la cartella db"))
        assertFalse(w.importable)
    }

    @Test fun anUnreadableLevelDatMakesTheWorldNotImportable() {
        val w = ok(WFx.javaWorld(levelDat = WFx.bytes(500, 99))).single()
        assertEquals(WorldEdition.JAVA, w.edition)
        assertNull(w.levelName)
        assertTrue(w.has(WorldNoteLevel.ERROR, "level.dat"))
        assertFalse(w.importable)
    }

    @Test fun anUncompressedLevelDatIsReadToo() {
        val w = ok(WFx.javaWorld(levelDat = WFx.javaLevelDat("Senza gzip", gzip = false))).single()
        assertEquals("Senza gzip", w.levelName)
        assertEquals("1.20.1", w.version)
    }

    @Test fun anOldWorldWithoutVersionAndWithMcRegionFilesIsExplained() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["level.dat"] = WFx.javaLevelDat("Vecchio", version = null, dataVersion = 0, gameType = 1)
        entries["region/r.0.0.mcr"] = WFx.bytes(4096, 1)
        val w = ok(entries).single()
        assertNull(w.version)
        assertEquals("Creativa", w.gameModeLabel)
        assertTrue(w.has(WorldNoteLevel.INFO, "non indica la versione"))
        assertTrue(w.has(WorldNoteLevel.WARNING, "McRegion"))
        assertTrue(w.has(WorldNoteLevel.WARNING, "non ha regioni"))
        assertTrue(w.importable)
    }

    @Test fun hardcoreIsShownAsSuch() {
        val w = ok(WFx.javaWorld(levelDat = WFx.javaLevelDat(hardcore = true))).single()
        assertTrue(w.hardcore)
        assertEquals("Hardcore", w.gameModeLabel)
    }

    @Test fun anArchiveWithoutLevelDatHasNoWorld() {
        val r = scan(mapOf("region/r.0.0.mca" to WFx.bytes(100, 1), "LEGGIMI.txt" to "x".toByteArray()))
        assertTrue(r is WorldScanResult.Failed)
        assertTrue((r as WorldScanResult.Failed).message.contains("level.dat"))
    }

    @Test fun dangerousPathsAreRefused() {
        val entries = WFx.javaWorld()
        entries["../fuori.txt"] = "no".toByteArray()
        val r = scan(entries)
        assertTrue(r is WorldScanResult.Failed)
        assertTrue((r as WorldScanResult.Failed).message.contains("pericolosi"))
    }

    @Test fun aFileThatIsNotAZipIsRefused() {
        val f = File(base, "non-zip.zip").also { it.writeBytes(WFx.bytes(2000, 5)) }
        val r = WorldScanner.scan(f)
        assertTrue(r is WorldScanResult.Failed)
    }

    @Test fun anMcworldIsAnArchiveLikeAnyOther() {
        val r = WorldScanner.scan(WFx.zip(File(base, "mondo.mcworld"), WFx.bedrockWorld()))
        assertTrue(r is WorldScanResult.Ok)
        assertEquals(WorldEdition.BEDROCK, (r as WorldScanResult.Ok).worlds.single().edition)
    }
}
