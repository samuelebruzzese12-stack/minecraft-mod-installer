package com.mmi.modinstaller.download

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Impronte attese, nomi dei file, contenuto salvato dei record. */
class DownloadBasicsTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = TestServer.tmpDir()
    }

    @After
    fun tearDown() {
        TestServer.deleteTree(dir)
    }

    // ------------------------------------------------------------------ impronte

    @Test
    fun `l'algoritmo si riconosce dalla lunghezza`() {
        assertEquals(HashAlgorithm.SHA1, ExpectedHash.parse("a".repeat(40))!!.algorithm)
        assertEquals(HashAlgorithm.SHA256, ExpectedHash.parse("b".repeat(64))!!.algorithm)
        assertEquals(HashAlgorithm.SHA512, ExpectedHash.parse("c".repeat(128))!!.algorithm)
    }

    @Test
    fun `maiuscole, spazi e prefisso sono ammessi`() {
        val h = ExpectedHash.parse("  SHA-256: " + "AB".repeat(32) + " \n")!!
        assertEquals(HashAlgorithm.SHA256, h.algorithm)
        assertEquals("ab".repeat(32), h.hex)
        assertEquals("ab".repeat(32), ExpectedHash.parse("sha256:" + "ab".repeat(32))!!.hex)
        assertEquals("1".repeat(40), ExpectedHash.parse("1".repeat(20) + " " + "1".repeat(20))!!.hex)
    }

    @Test
    fun `impronte non valide`() {
        assertNull(ExpectedHash.parse(""))
        assertNull(ExpectedHash.parse("zz".repeat(32)))
        assertNull(ExpectedHash.parse("a".repeat(63)))
        assertNull(ExpectedHash.parse("md5:" + "a".repeat(32)))
        // Il prefisso deve concordare con la lunghezza.
        assertNull(ExpectedHash.parse("sha1:" + "a".repeat(64)))
    }

    // ------------------------------------------------------------------ nomi

    @Test
    fun `nome dall'indirizzo`() {
        assertEquals(Pair("sodium-0.5.jar", false), DownloadNames.fromUrl("https://cdn.modrinth.com/data/x/versions/y/sodium-0.5.jar"))
        assertEquals(Pair("sodium-0.5.jar", false), DownloadNames.fromUrl("https://cdn.modrinth.com/a/sodium-0.5.jar?download=1&x=2#frag"))
        assertEquals(Pair("Mondo bello.mcworld", false), DownloadNames.fromUrl("https://example.com/Mondo%20bello.mcworld"))
        assertEquals(Pair("a+b.zip", false), DownloadNames.fromUrl("https://example.com/a+b.zip"))
    }

    @Test
    fun `senza estensione il nome è solo un'ipotesi`() {
        assertEquals(Pair("download", true), DownloadNames.fromUrl("https://example.com/"))
        assertEquals(Pair("get", true), DownloadNames.fromUrl("https://example.com/files/get?id=3"))
        assertEquals(Pair("download", true), DownloadNames.fromUrl("non è un indirizzo"))
    }

    @Test
    fun `nomi ostili diventano nomi innocui`() {
        assertEquals("passwd", DownloadNames.safe("../../etc/passwd"))
        assertEquals("x.jar", DownloadNames.safe("..\\..\\x.jar"))
        assertEquals("a_b_c.txt", DownloadNames.safe("a<b>c.txt"))
        assertEquals("download", DownloadNames.safe("..."))
        assertEquals("download", DownloadNames.safe(""))
        assertEquals("hidden.jar", DownloadNames.safe(".hidden.jar"))
        assertEquals("a_b.jar", DownloadNames.safe("a\u0000b.jar"))
        assertEquals("fallback", DownloadNames.safe("/", "fallback"))
    }

    @Test
    fun `nome troppo lungo mantiene l'estensione`() {
        val name = DownloadNames.safe("x".repeat(400) + ".mcpack")
        assertTrue(name.length <= 120)
        assertTrue(name.endsWith(".mcpack"))
    }

    @Test
    fun `nome dal Content-Disposition`() {
        assertEquals("mod.jar", DownloadNames.fromContentDisposition("attachment; filename=\"mod.jar\""))
        assertEquals("mod.jar", DownloadNames.fromContentDisposition("attachment; filename=mod.jar"))
        assertEquals("Città.mcpack", DownloadNames.fromContentDisposition("attachment; filename*=UTF-8''Citt%C3%A0.mcpack"))
        // La forma estesa vince su quella semplice.
        assertEquals("giusto.zip", DownloadNames.fromContentDisposition("attachment; filename=\"sbagliato.zip\"; filename*=UTF-8''giusto.zip"))
        assertEquals("x.jar", DownloadNames.fromContentDisposition("attachment; filename=\"../../x.jar\""))
        assertNull(DownloadNames.fromContentDisposition("inline"))
        assertNull(DownloadNames.fromContentDisposition(null))
    }

    @Test
    fun `nomi unici`() {
        assertEquals("mod.jar", DownloadNames.unique(dir, "mod.jar"))
        File(dir, "mod.jar").writeText("x")
        assertEquals("mod (1).jar", DownloadNames.unique(dir, "mod.jar"))
        File(dir, "mod (1).jar").writeText("x")
        assertEquals("mod (2).jar", DownloadNames.unique(dir, "mod.jar"))
        // Anche i nomi già prenotati da altri download (maiuscole comprese) contano.
        assertEquals("altro (1).zip", DownloadNames.unique(dir, "altro.zip", setOf("ALTRO.zip")))
        assertEquals("senza (1)", DownloadNames.unique(dir, "senza", setOf("senza")))
    }

    // ------------------------------------------------------------------ record salvati

    private fun sample(state: DownloadState = DownloadState.PAUSED) = DownloadRecord(
        id = "abc123", url = "https://cdn.modrinth.com/x.jar", fileName = "x.jar", nameProvisional = false, state = state,
        doneBytes = 10, totalBytes = 100, expectedHash = ExpectedHash.parse("e".repeat(64)), sha256 = null,
        etag = "\"v1\"", lastModified = "Wed, 21 Oct 2015 07:28:00 GMT", error = "interrotto", pendingHost = "other.example",
        note = "Mod Sodium", createdAt = 1, updatedAt = 2
    )

    @Test
    fun `un record salvato e riletto è identico`() {
        val r = sample()
        assertEquals(r, DownloadRecord.fromValue(r.toMap()))
        val noHash = r.copy(expectedHash = null, etag = null, error = null, pendingHost = null, note = null)
        assertEquals(noHash, DownloadRecord.fromValue(noHash.toMap()))
    }

    @Test
    fun `record rovinati vengono scartati senza errori`() {
        assertNull(DownloadRecord.fromValue(null))
        assertNull(DownloadRecord.fromValue("testo"))
        assertNull(DownloadRecord.fromValue(mapOf("id" to "x")))
        val bad = sample().toMap().toMutableMap()
        bad["state"] = "NON_ESISTE"
        assertNull(DownloadRecord.fromValue(bad))
        val negative = sample().toMap().toMutableMap()
        negative["doneBytes"] = -5L
        assertEquals(0L, DownloadRecord.fromValue(negative)!!.doneBytes)
    }

    @Test
    fun `avanzamento`() {
        assertEquals(0.1f, sample().fraction!!, 0.0001f)
        assertNull(sample().copy(totalBytes = -1).fraction)
        assertEquals(1f, sample().copy(doneBytes = 500).fraction!!, 0.0001f)
        assertTrue(DownloadState.QUEUED.active && DownloadState.RUNNING.active)
        assertFalse(DownloadState.PAUSED.active)
        assertTrue(DownloadState.COMPLETED.finished && DownloadState.FAILED.finished && DownloadState.CANCELED.finished)
        assertFalse(DownloadState.PAUSED.finished)
        assertNotNull(DownloadState.values().map { it.label })
    }
}
