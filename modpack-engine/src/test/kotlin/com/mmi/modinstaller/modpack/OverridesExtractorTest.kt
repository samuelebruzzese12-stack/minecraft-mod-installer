package com.mmi.modinstaller.modpack

import com.mmi.modinstaller.core.ArchiveException
import com.mmi.modinstaller.core.SafetyLimits
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

class OverridesExtractorTest {

    private lateinit var dir: File
    private lateinit var target: File

    @Before fun setUp() {
        dir = Fx.tempDir()
        target = File(dir, "profilo")
    }

    @After fun tearDown() {
        TestServer.deleteTree(dir)
    }

    private fun archive(entries: Map<String, ByteArray>): File {
        val f = File(dir, "p.mrpack")
        f.writeBytes(Fx.zip(entries))
        return f
    }

    private fun text(vararg pairs: Pair<String, String>): Map<String, ByteArray> = pairs.associate { it.first to it.second.toByteArray() }

    @Test fun commonAndClientFilesGoInsideTheProfileAndServerOnesAreLeftOut() {
        val r = OverridesExtractor.extract(
            archive(
                text(
                    "modrinth.index.json" to "{}",
                    "overrides/config/a.toml" to "a",
                    "overrides/options.txt" to "o",
                    "client-overrides/config/c.toml" to "c",
                    "server-overrides/config/s.toml" to "s",
                    "overrides/mods/extra.jar" to "J"
                )
            ),
            target
        )
        assertEquals(setOf("config/a.toml", "options.txt", "config/c.toml", "mods/extra.jar"), r.applied.toSet())
        assertTrue(r.skipped.isEmpty())
        assertEquals("a", File(target, "config/a.toml").readText())
        assertEquals("J", File(target, "mods/extra.jar").readText())
        assertFalse(File(target, "config/s.toml").exists())
        assertFalse("l'indice non è un override", File(target, "modrinth.index.json").exists())
    }

    @Test fun clientOverridesWinWhicheverComesFirstInTheArchive() {
        val a = OverridesExtractor.extract(archive(text("overrides/x.txt" to "comune", "client-overrides/x.txt" to "client")), target)
        assertEquals("client", File(target, "x.txt").readText())
        assertEquals(listOf("x.txt"), a.applied)
        File(target, "x.txt").delete()
        OverridesExtractor.extract(archive(text("client-overrides/x.txt" to "client", "overrides/x.txt" to "comune")), target)
        assertEquals("client", File(target, "x.txt").readText())
    }

    @Test fun executablesAndSuspiciousNamesAreSkippedAndReported() {
        val r = OverridesExtractor.extract(
            archive(
                text(
                    "overrides/ok.txt" to "ok",
                    "overrides/start.sh" to "#!/bin/sh",
                    "overrides/tools/setup.exe" to "MZ",
                    "overrides/.trash/x.txt" to "x",
                    "overrides/config/.cache/a" to "x",
                    "overrides/.gitkeep" to "",
                    "overrides/__MACOSX/a" to "x",
                    "overrides/Thumbs.db" to "x"
                )
            ),
            target
        )
        assertEquals(listOf("ok.txt"), r.applied)
        assertEquals("sh ed exe sono dichiarati", 2, r.skipped.size)
        assertTrue(r.skipped.any { it.startsWith("start.sh") })
        assertTrue(r.skipped.any { it.startsWith("tools/setup.exe") })
        assertEquals("nascosti e di sistema sono solo contati", 5, r.ignored)
        assertFalse(File(target, "start.sh").exists())
        assertFalse(File(target, ".trash").exists())
    }

    @Test fun anArchiveWhoseNamesEscapeIsNotOpenedAtAll() {
        try {
            OverridesExtractor.extract(archive(text("overrides/../../evil.txt" to "x", "overrides/ok.txt" to "ok")), target)
            fail("doveva rifiutare l'archivio")
        } catch (e: ArchiveException) {
            // atteso
        }
        assertFalse(File(dir, "evil.txt").exists())
        assertFalse(File(target, "ok.txt").exists())
    }

    @Test fun aSymbolicLinkInsideTheProfileCannotLeadOutside() {
        val outside = File(dir, "fuori").also { it.mkdirs() }
        target.mkdirs()
        try {
            java.nio.file.Files.createSymbolicLink(File(target, "config").toPath(), outside.toPath())
        } catch (e: Exception) {
            return // il sistema non permette i collegamenti: niente da provare
        }
        val r = OverridesExtractor.extract(archive(text("overrides/config/x.txt" to "x", "overrides/ok.txt" to "ok")), target)
        assertFalse(File(outside, "x.txt").exists())
        assertEquals(listOf("ok.txt"), r.applied)
        assertTrue(r.skipped.single().contains("esce dalla cartella"))
    }

    @Test fun existingFilesAreReplacedAndFoldersCannotBeOverwritten() {
        File(target, "config").mkdirs()
        File(target, "config/a.toml").writeText("vecchio")
        File(target, "dir").mkdirs()
        val r = OverridesExtractor.extract(archive(text("overrides/config/a.toml" to "nuovo", "overrides/dir" to "file")), target)
        assertEquals("nuovo", File(target, "config/a.toml").readText())
        assertTrue(File(target, "dir").isDirectory)
        assertTrue(r.skipped.single().contains("cartella"))
    }

    @Test fun sizesAreLimitedByWhatIsReallyReadNotByTheHeaders() {
        val limits = SafetyLimits(maxEntryBytes = 100, maxTotalUncompressedBytes = 150)
        try {
            OverridesExtractor.extract(archive(mapOf("overrides/big.bin" to ByteArray(101))), target, limits)
            fail("doveva superare il limite del file")
        } catch (e: ArchiveException) {
            // atteso
        }
        assertFalse("nessun file a metà", File(target, "big.bin").exists() || (target.listFiles()?.any { it.name.endsWith(".part") } ?: false))
        try {
            OverridesExtractor.extract(archive(mapOf("overrides/a.bin" to ByteArray(90), "overrides/b.bin" to ByteArray(90))), target, limits)
            fail("doveva superare il limite totale")
        } catch (e: ArchiveException) {
            // atteso
        }
    }

    @Test fun binaryContentIsCopiedExactlyAndCancelStopsTheWork() {
        val data = TestServer.bytes(70_000)
        OverridesExtractor.extract(archive(mapOf("overrides/data/blob.bin" to data)), target)
        assertArrayEquals(data, File(target, "data/blob.bin").readBytes())
        try {
            OverridesExtractor.extract(archive(text("overrides/a.txt" to "a")), File(dir, "altro"), cancel = { throw CancellationException("stop") })
            fail("doveva fermarsi")
        } catch (e: CancellationException) {
            // atteso
        }
    }
}
