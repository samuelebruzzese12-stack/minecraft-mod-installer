package com.mmi.modinstaller.worlds

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

class WorldImporterTest {

    private lateinit var base: File
    private lateinit var saves: File
    private val importer = WorldImporter(reserveBytes = 0)

    @Before fun setUp() {
        base = WFx.tempDir()
        saves = File(base, "profilo/saves")
    }

    @After fun tearDown() {
        WFx.deleteTree(base)
    }

    private fun archive(entries: Map<String, ByteArray>, name: String = "mondo.zip", stored: Boolean = false) = WFx.zip(File(base, name), entries, stored)

    private fun worldOf(file: File, index: Int = 0): WorldInfo {
        val r = WorldScanner.scan(file)
        assertTrue(if (r is WorldScanResult.Failed) r.message else "", r is WorldScanResult.Ok)
        return (r as WorldScanResult.Ok).worlds[index]
    }

    /** Ogni file del mondo (tranne session.lock) è nella cartella, identico; e non c'è nient'altro. */
    private fun assertSameWorld(dir: File, entries: Map<String, ByteArray>, prefix: String = "") {
        val expected = entries.filterKeys { k -> (prefix.isEmpty() || k.startsWith("$prefix/")) }
            .mapKeys { (k, _) -> if (prefix.isEmpty()) k else k.removePrefix("$prefix/") }
            .filterKeys { it != "session.lock" }
        val found = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path.replace(File.separatorChar, '/') }.toSet()
        assertEquals(expected.keys, found)
        for ((rel, data) in expected) assertArrayEquals(rel, data, File(dir, rel).readBytes())
    }

    private fun noWorkFolders() = saves.listFiles { f -> f.name.startsWith(WorldImporter.STAGING_PREFIX) }?.toList().orEmpty()

    @Test fun aJavaWorldIsImportedFileByFileAndAppearsAllAtOnce() {
        val entries = WFx.javaWorld()
        val file = archive(entries)
        val world = worldOf(file)
        var calls = 0
        var last = 0L
        val r = importer.importJava(file, world, saves, world.title, "t1", progress = { done, total, _ ->
            calls++
            assertTrue(done >= last)
            assertEquals(world.bytes, total)
            last = done
        })
        assertNull(r.fatal)
        assertTrue(r.ok)
        assertEquals(File(saves, "Mondo di Prova"), r.worldDir)
        assertEquals(world.files, r.files)
        assertEquals(world.bytes, r.bytes)
        assertEquals(0, r.resumedFiles)
        assertTrue(calls >= world.files)
        assertSameWorld(r.worldDir!!, entries)
        assertTrue("non deve restare la cartella di lavoro", noWorkFolders().isEmpty())
        assertTrue(importer.pending(saves).isEmpty())
        assertEquals("l'archivio non viene toccato", entries.size, java.util.zip.ZipFile(file).use { it.size() })
    }

    @Test fun aWorldInsideAFolderLosesTheFolderAndKeepsOnlyItsFiles() {
        val entries = WFx.javaWorld("Cartella/Il Mio Mondo")
        entries["Cartella/LEGGIMI.txt"] = "fuori".toByteArray()
        val file = archive(entries)
        val world = worldOf(file)
        assertEquals("Cartella/Il Mio Mondo", world.root)
        val r = importer.importJava(file, world, saves, "Il Mio Mondo", "t1")
        assertTrue(r.fatal, r.ok)
        assertSameWorld(r.worldDir!!, entries.filterKeys { it.startsWith("Cartella/Il Mio Mondo/") }, "Cartella/Il Mio Mondo")
    }

    @Test fun theFolderNameIsMadeSafeAndDoesNotOverwriteAnotherWorld() {
        val file = archive(WFx.javaWorld())
        val world = worldOf(file)
        File(saves, "a_b_c").mkdirs()
        File(saves, "a_b_c/level.dat").writeText("mondo che c'era già")
        val r = importer.importJava(file, world, saves, "a/b:c", "t1")
        assertTrue(r.fatal, r.ok)
        assertEquals(File(saves, "a_b_c (2)"), r.worldDir)
        assertEquals("mondo che c'era già", File(saves, "a_b_c/level.dat").readText())
        assertEquals("Mondo", WorldImporter.safeFolderName("  ..  "))
        assertEquals(60, WorldImporter.safeFolderName("x".repeat(200)).length)
    }

    @Test fun anInterruptedImportResumesWithoutCopyingTheFinishedFilesAgain() {
        val entries = WFx.javaWorld()
        val file = archive(entries)
        val world = worldOf(file)
        var seen = 0
        // L'app «muore» (qui: un errore di scrittura) dopo 5 file.
        val first = importer.importJava(file, world, saves, world.title, "t1", progress = { _, _, _ ->
            seen++
            if (seen == 5) throw IOException("spazio finito all'improvviso")
        })
        assertFalse(first.ok)
        assertNotNull(first.fatal)
        assertTrue(first.fatal!!.contains("restano per la ripresa"))
        assertFalse(File(saves, "Mondo di Prova").exists())
        val pending = importer.pending(saves).single()
        assertEquals("t1", pending.token)
        assertEquals("Mondo di Prova", pending.folderName)
        assertEquals(file.absoluteFile, pending.archive.absoluteFile)
        assertEquals(file.length(), pending.archiveSize)
        // Il quinto file era in corso quando l'app si è fermata: non risulta completato e si ricopia.
        assertEquals(4, pending.copiedFiles)

        val second = importer.importJava(file, world, saves, world.title, "t1")
        assertTrue(second.fatal, second.ok)
        assertEquals(4, second.resumedFiles)
        assertEquals(world.files, second.files)
        assertSameWorld(second.worldDir!!, entries)
        assertTrue(noWorkFolders().isEmpty())
    }

    @Test fun aDamagedCopiedFileIsCopiedAgainOnResume() {
        val entries = WFx.javaWorld()
        val file = archive(entries)
        val world = worldOf(file)
        var seen = 0
        importer.importJava(file, world, saves, world.title, "t1", progress = { _, _, _ ->
            seen++
            if (seen == 6) throw IOException("interrotta")
        })
        // Un file già copiato (e segnato nel diario) si rovina: stessa dimensione, byte diversi. La ripresa lo deve ricopiare.
        val damaged = File(importer.stagingDir(saves, "t1"), "region/r.0.0.mca")
        assertTrue(damaged.isFile)
        damaged.writeBytes(ByteArray(damaged.length().toInt()))
        val r = importer.importJava(file, world, saves, world.title, "t1")
        assertTrue(r.fatal, r.ok)
        assertEquals(4, r.resumedFiles)
        assertSameWorld(r.worldDir!!, entries)
    }

    @Test fun aHalfWrittenJournalLineDoesNotBreakTheResume() {
        val entries = WFx.javaWorld()
        val file = archive(entries)
        val world = worldOf(file)
        var seen = 0
        importer.importJava(file, world, saves, world.title, "t1", progress = { _, _, _ ->
            seen++
            if (seen == 4) throw IOException("interrotta")
        })
        val journal = importer.stagingDir(saves, "t1").listFiles()!!.first { it.name.startsWith(".mmi") }
        journal.appendText("region/r.9.9.mca\t12")   // riga lasciata a metà, senza a capo
        val r = importer.importJava(file, world, saves, world.title, "t1")
        assertTrue(r.fatal, r.ok)
        assertEquals(3, r.resumedFiles)
        assertSameWorld(r.worldDir!!, entries)
    }

    @Test fun cancellingRemovesTheWorkFolderAndLeavesNoWorld() {
        val file = archive(WFx.javaWorld())
        val world = worldOf(file)
        var seen = 0
        try {
            importer.importJava(file, world, saves, world.title, "t1", cancel = {
                if (seen >= 3) throw CancellationException("annullata")
            }, progress = { _, _, _ -> seen++ })
            fail("doveva essere annullata")
        } catch (e: CancellationException) {
            // atteso
        }
        assertTrue(noWorkFolders().isEmpty())
        assertFalse(File(saves, "Mondo di Prova").exists())
        assertTrue(importer.pending(saves).isEmpty())
    }

    @Test fun notEnoughSpaceStopsBeforeCopyingAnything() {
        val file = archive(WFx.javaWorld())
        val world = worldOf(file)
        val tight = WorldImporter(reserveBytes = 0, freeSpace = { 1000L })
        val r = tight.importJava(file, world, saves, world.title, "t1")
        assertFalse(r.ok)
        assertTrue(r.fatal!!.contains("Spazio insufficiente"))
        assertTrue(noWorkFolders().isEmpty())
    }

    @Test fun aDamagedArchiveEntryStopsTheImportAndKeepsWhatWasCopied() {
        val entries = WFx.javaWorld()
        val file = archive(entries, stored = true)
        val world = worldOf(file)
        // Si altera un byte dei dati di una regione: il CRC non torna più.
        val bytes = file.readBytes()
        val target = entries.getValue("region/r.-1.0.mca")
        val at = indexOf(bytes, target.copyOfRange(100, 140)) + 10
        bytes[at] = (bytes[at] + 1).toByte()
        file.writeBytes(bytes)
        val r = importer.importJava(file, world, saves, world.title, "t1")
        assertFalse(r.ok)
        assertTrue(r.fatal, r.fatal!!.contains("archivio"))
        assertFalse(File(saves, "Mondo di Prova").exists())
        val pending = importer.pending(saves).single()
        // La regione danneggiata non risulta copiata: una ripresa la rileggerebbe dall'archivio (e la rifiuterebbe di nuovo).
        assertEquals(4, pending.copiedFiles)
        importer.discard(saves, "t1")
        assertTrue(noWorkFolders().isEmpty())
    }

    @Test fun anotherArchiveWithTheSameTokenStartsFromScratch() {
        val file = archive(WFx.javaWorld())
        val world = worldOf(file)
        var seen = 0
        importer.importJava(file, world, saves, world.title, "t1", progress = { _, _, _ ->
            seen++
            if (seen == 3) throw IOException("interrotta")
        })
        val other = WFx.javaWorld(name = "Altro")
        other["region/r.5.5.mca"] = WFx.bytes(3000, 77)
        val file2 = archive(other, "altro.zip")
        val world2 = worldOf(file2)
        val r = importer.importJava(file2, world2, saves, world2.title, "t1")
        assertTrue(r.fatal, r.ok)
        assertEquals(0, r.resumedFiles)
        assertSameWorld(r.worldDir!!, other)
    }

    @Test fun bedrockWorldsAndBrokenWorldsAreNotImportedHere() {
        val bedrock = archive(WFx.bedrockWorld(), "b.zip")
        val r1 = importer.importJava(bedrock, worldOf(bedrock), saves, "B", "t1")
        assertFalse(r1.ok)
        assertTrue(r1.fatal!!.contains("INSTALLER BEDROCK"))
        val broken = archive(WFx.javaWorld(levelDat = WFx.bytes(300, 3)), "rotto.zip")
        val r2 = importer.importJava(broken, worldOf(broken), saves, "R", "t2")
        assertFalse(r2.ok)
        assertTrue(r2.fatal!!.contains("level.dat"))
        assertTrue(noWorkFolders().isEmpty())
    }

    @Test fun theTestOnlyPauseSlowsTheImportAndCanBeCancelled() {
        val file = archive(WFx.javaWorld())
        val world = worldOf(file)
        val slow = WorldImporter(reserveBytes = 0, pauseAfterEachFileMs = 60)
        val started = System.currentTimeMillis()
        val r = slow.importJava(file, world, saves, world.title, "t1")
        assertTrue(r.fatal, r.ok)
        assertTrue("con la pausa l'importazione deve durare almeno 60 ms per file", System.currentTimeMillis() - started >= 60L * world.files)
        var calls = 0
        try {
            slow.importJava(file, world, saves, "Altro", "t2", cancel = { if (++calls > 5) throw CancellationException("annullata") })
            fail("doveva essere annullata durante la pausa")
        } catch (e: CancellationException) {
            // atteso
        }
        assertTrue(noWorkFolders().isEmpty())
    }

    @Test fun aBadTokenIsRefused() {
        val file = archive(WFx.javaWorld())
        val r = importer.importJava(file, worldOf(file), saves, "M", "../fuori")
        assertFalse(r.ok)
        assertFalse(File(base, "profilo/fuori").exists())
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        throw AssertionError("non trovato")
    }
}
