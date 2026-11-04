package com.mmi.modinstaller.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ArchiveTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = T.tmpDir()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun readAll(file: File, limits: SafetyLimits = SafetyLimits()): Map<String, Int> {
        val seen = LinkedHashMap<String, Int>()
        ZipArchiveReader.open(file, limits).use { r ->
            r.forEachEntry { e, c ->
                seen[e.rawName] = c?.readBytes()?.size ?: -1
            }
        }
        return seen
    }

    @Test
    fun readsValidZip() {
        val f = T.zip(File(dir, "a.zip"), linkedMapOf("a.txt" to T.bytes("ciao"), "d/" to ByteArray(0), "d/b.bin" to ByteArray(1000)))
        val seen = readAll(f)
        assertEquals(4, seen["a.txt"])
        assertEquals(-1, seen["d/"])
        assertEquals(1000, seen["d/b.bin"])
    }

    @Test
    fun zipSizeHintIsReported() {
        val f = T.zip(File(dir, "a.zip"), linkedMapOf("a.bin" to ByteArray(5000), "b.bin" to ByteArray(300)))
        ZipArchiveReader.open(f).use { assertEquals(5300L, it.totalSizeHint) }
    }

    @Test
    fun truncatedZipIsCorrupt() {
        val full = T.zipBytes(linkedMapOf("a.bin" to ByteArray(20000) { (it * 31).toByte() }, "b.txt" to T.bytes("hello")))
        for (cut in listOf(full.size - 5, full.size / 2, 30, 10)) {
            val f = File(dir, "t$cut.zip")
            f.writeBytes(full.copyOf(cut))
            try {
                readAll(f)
                fail("taglio a $cut byte non rilevato")
            } catch (e: ArchiveCorruptException) {
                // ok
            }
        }
    }

    @Test
    fun htmlPretendingToBeZipIsCorrupt() {
        val f = File(dir, "page.zip")
        f.writeText("<!DOCTYPE html><html><body>Download expired</body></html>".repeat(3))
        try {
            readAll(f)
            fail()
        } catch (e: ArchiveCorruptException) {
            // ok
        }
    }

    @Test
    fun flippedDataByteFailsCrc() {
        val marker = "UNIQUE-MARKER-0123456789-ABCDEF"
        val f = T.zip(File(dir, "s.zip"), linkedMapOf("data.txt" to T.bytes(marker), "other.txt" to T.bytes("x")), stored = true)
        val raw = f.readBytes()
        val idx = String(raw, Charsets.ISO_8859_1).indexOf(marker)
        assertTrue(idx > 0)
        raw[idx + 5] = (raw[idx + 5].toInt() xor 0x01).toByte()
        f.writeBytes(raw)
        try {
            readAll(f)
            fail("CRC alterato non rilevato")
        } catch (e: ArchiveCorruptException) {
            assertTrue(e.message!!.contains("CRC") || e.message!!.contains("Checksum"))
        }
    }

    @Test
    fun encryptedFlagIsDetected() {
        val f = T.zip(File(dir, "e.zip"), linkedMapOf("secret.txt" to T.bytes("segreto")))
        val raw = f.readBytes()
        // bit 0 dei flag generali nella directory centrale (firma 0x02014b50, flag a +8)
        val cd = findSignature(raw, 0x50, 0x4B, 0x01, 0x02)
        raw[cd + 8] = (raw[cd + 8].toInt() or 1).toByte()
        f.writeBytes(raw)
        try {
            readAll(f)
            fail()
        } catch (e: ArchivePasswordException) {
            // ok
        }
    }

    @Test
    fun unsupportedCompressionIsDetected() {
        val f = T.zip(File(dir, "m.zip"), linkedMapOf("a.txt" to T.bytes("abc")))
        val raw = f.readBytes()
        val cd = findSignature(raw, 0x50, 0x4B, 0x01, 0x02)
        raw[cd + 10] = 99
        raw[cd + 11] = 0
        f.writeBytes(raw)
        try {
            readAll(f)
            fail()
        } catch (e: ArchiveUnsupportedException) {
            assertEquals(FindingCode.UNSUPPORTED_COMPRESSION, e.code)
        }
    }

    @Test
    fun zipSlipNamesAreRejectedBeforeReading() {
        for (name in listOf("../evil.txt", "a/../../evil.txt", "/abs/evil.txt", "..\\evil.txt", "C:\\evil.txt")) {
            val f = File(dir, "slip.zip")
            ZipOutputStream(f.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("ok.txt"))
                z.write(1)
                z.closeEntry()
                z.putNextEntry(ZipEntry(name))
                z.write(2)
                z.closeEntry()
            }
            try {
                readAll(f)
                fail("percorso non bloccato: $name")
            } catch (e: ArchiveUnsafeException) {
                assertTrue(e.paths.contains(name))
            }
        }
    }

    @Test
    fun tooManyEntriesIsRejected() {
        val entries = LinkedHashMap<String, ByteArray>()
        for (i in 0 until 50) entries["f$i.txt"] = T.bytes("x")
        val f = T.zip(File(dir, "many.zip"), entries)
        try {
            readAll(f, SafetyLimits(maxEntries = 10))
            fail()
        } catch (e: LimitExceededException) {
            assertEquals(FindingCode.TOO_MANY_ENTRIES, e.code)
        }
    }

    @Test
    fun boundedStreamStopsAtLimit() {
        val s = BoundedInputStream(ByteArray(1000).inputStream(), 100, FindingCode.ENTRY_TOO_LARGE, "x")
        try {
            s.readBytes()
            fail()
        } catch (e: LimitExceededException) {
            assertEquals(FindingCode.ENTRY_TOO_LARGE, e.code)
        }
    }

    @Test
    fun scannerEnforcesLimitsOnBytesActuallyRead() {
        val f = T.zip(File(dir, "big.zip"), linkedMapOf("big.bin" to ByteArray(50_000)))
        val limits = SafetyLimits(maxEntryBytes = 10_000)
        try {
            ZipArchiveReader.open(f, limits).use { r ->
                ArchiveScanner(limits).scan(r, f.length(), dir, 0, {}, null, longArrayOf(1L shl 30))
            }
            fail()
        } catch (e: LimitExceededException) {
            assertEquals(FindingCode.ENTRY_TOO_LARGE, e.code)
        }
    }

    @Test
    fun scannerDetectsCompressionBomb() {
        val f = T.zip(File(dir, "bomb.zip"), linkedMapOf("zeros.bin" to ByteArray(20 * 1024 * 1024)))
        val limits = SafetyLimits(maxCompressionRatio = 50, ratioCheckMinBytes = 1024 * 1024)
        try {
            ZipArchiveReader.open(f, limits).use { r ->
                ArchiveScanner(limits).scan(r, f.length(), dir, 0, {}, null, longArrayOf(1L shl 30))
            }
            fail()
        } catch (e: LimitExceededException) {
            assertEquals(FindingCode.COMPRESSION_BOMB, e.code)
        }
    }

    @Test
    fun scannerCollectsKeyFilesAndSkipsJunk() {
        val u = T.uuidOf("k")
        val entries = T.packEntries("", "Pack", u)
        entries["__MACOSX/._manifest.json"] = ByteArray(10)
        entries[".DS_Store"] = ByteArray(10)
        entries["tool.exe"] = ByteArray(10)
        val f = T.zip(File(dir, "k.zip"), entries)
        val res = ZipArchiveReader.open(f).use { r ->
            ArchiveScanner(SafetyLimits()).scan(r, f.length(), dir, 0, {}, null, longArrayOf(1L shl 30))
        }
        assertTrue(res.keyFiles.containsKey("manifest.json"))
        assertTrue(res.keyFiles.containsKey("pack_icon.png"))
        assertEquals(2, res.junkCount)
        assertEquals(listOf("tool.exe"), res.executables)
        assertTrue(res.textures.isNotEmpty())
        assertEquals(0, res.nonCanonicalNames)
        assertEquals(0, res.duplicateNames)
    }

    private fun findSignature(raw: ByteArray, a: Int, b: Int, c: Int, d: Int): Int {
        for (i in raw.size - 4 downTo 0) {
            if ((raw[i].toInt() and 0xFF) == a && (raw[i + 1].toInt() and 0xFF) == b &&
                (raw[i + 2].toInt() and 0xFF) == c && (raw[i + 3].toInt() and 0xFF) == d
            ) {
                // la prima voce della directory centrale è la più piccola posizione con questa firma
                var first = i
                var j = i - 1
                while (j >= 0) {
                    if ((raw[j].toInt() and 0xFF) == a && (raw[j + 1].toInt() and 0xFF) == b &&
                        (raw[j + 2].toInt() and 0xFF) == c && (raw[j + 3].toInt() and 0xFF) == d
                    ) first = j
                    j--
                }
                return first
            }
        }
        fail("firma non trovata")
        return -1
    }
}
