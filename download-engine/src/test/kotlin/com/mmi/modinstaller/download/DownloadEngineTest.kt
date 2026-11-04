package com.mmi.modinstaller.download

import com.mmi.modinstaller.core.MemoryDocumentStore
import com.mmi.modinstaller.core.asJsonList
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
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

/** Il motore dei download contro un server HTTP locale: ripresa, verifica, errori, redirect, coda, riavvio. */
class DownloadEngineTest {

    private lateinit var base: File
    private lateinit var server: TestServer
    private lateinit var store: MemoryDocumentStore
    private val engines = ArrayList<DownloadEngine>()

    private val downloads: File get() = File(base, "downloads")

    @Before
    fun setUp() {
        base = TestServer.tmpDir()
        server = TestServer()
        store = MemoryDocumentStore()
    }

    @After
    fun tearDown() {
        for (e in engines) e.shutdown()
        server.close()
        TestServer.deleteTree(base)
    }

    // ------------------------------------------------------------------ attrezzi

    private fun fast(
        maxParallel: Int = 2,
        maxBytes: Long = 64L * 1024 * 1024,
        retries: LongArray = longArrayOf(30, 30, 30),
        free: (File) -> Long = { Long.MAX_VALUE }
    ) = DownloadOptions(
        maxParallel = maxParallel, maxBytes = maxBytes, connectTimeoutMs = 3_000, readTimeoutMs = 5_000,
        maxRedirects = 3, retryDelaysMs = retries, reserveBytes = 0, freeSpace = free
    )

    private fun policy() = UrlPolicy(
        userHosts = { DownloadEngine.approvedHosts(store) },
        insecureHosts = setOf("127.0.0.1:${server.port}")
    )

    private fun engine(options: DownloadOptions = fast(), checker: UrlChecker = policy()): DownloadEngine {
        val e = DownloadEngine(store, downloads, checker, options)
        engines.add(e)
        return e
    }

    private fun add(e: DownloadEngine, path: String, hash: ExpectedHash? = null, name: String? = null): DownloadRecord {
        val r = e.enqueue(DownloadRequest(server.url(path), fileName = name, expectedHash = hash))
        if (r !is EnqueueResult.Added) fail("accodamento rifiutato: " + (r as EnqueueResult.Rejected).message)
        return (r as EnqueueResult.Added).record
    }

    private fun await(e: DownloadEngine, id: String, timeoutMs: Long = 20_000, cond: (DownloadRecord) -> Boolean): DownloadRecord {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val r = e.record(id)
            if (r != null && cond(r)) return r
            Thread.sleep(15)
        }
        fail("tempo scaduto; stato attuale: " + e.record(id))
        throw IllegalStateException()
    }

    private fun finished(e: DownloadEngine, id: String) = await(e, id) { it.state.finished }

    private fun digest(algorithm: String, data: ByteArray): String =
        TestServer.hex(MessageDigest.getInstance(algorithm).digest(data))

    private fun fileOf(r: DownloadRecord) = File(downloads, r.fileName)

    private fun partOf(r: DownloadRecord) = File(downloads, r.id + ".part")

    // ------------------------------------------------------------------ download semplice

    @Test
    fun `scarica un file e ne calcola l'impronta`() {
        val content = TestServer.bytes(300_000)
        server.handler = TestServer.file(content)
        val e = engine()
        val r = finished(e, add(e, "/files/mod.jar").id)
        assertEquals(DownloadState.COMPLETED, r.state)
        assertEquals("mod.jar", r.fileName)
        assertArrayEquals(content, fileOf(r).readBytes())
        assertEquals(TestServer.sha256(content), r.sha256)
        assertEquals(content.size.toLong(), r.totalBytes)
        assertEquals(content.size.toLong(), r.doneBytes)
        assertNull(r.error)
        assertFalse(partOf(r).exists())
        assertEquals(1, server.requests.size)
        assertEquals("MinecraftModInstaller", server.requests[0].header("user-agent"))
        assertEquals("identity", server.requests[0].header("accept-encoding"))
        assertNull(server.requests[0].header("range"))
    }

    @Test
    fun `le impronte attese corrette sono accettate - SHA-1, SHA-256 e SHA-512`() {
        val content = TestServer.bytes(50_000)
        server.handler = TestServer.file(content)
        val e = engine()
        for ((algo, name) in listOf("SHA-1" to "a.bin", "SHA-256" to "b.bin", "SHA-512" to "c.bin")) {
            val hash = ExpectedHash.parse(digest(algo, content))!!
            val r = finished(e, add(e, "/$name", hash).id)
            assertEquals("$algo: " + r.error, DownloadState.COMPLETED, r.state)
            assertEquals(TestServer.sha256(content), r.sha256)
        }
    }

    @Test
    fun `impronta sbagliata - il file non viene tenuto`() {
        val content = TestServer.bytes(80_000)
        server.handler = TestServer.file(content)
        val e = engine()
        val wrong = ExpectedHash.parse("0".repeat(64))!!
        val r = finished(e, add(e, "/falso.jar", wrong).id)
        assertEquals(DownloadState.FAILED, r.state)
        assertTrue(r.error!!, r.error!!.contains("impronta"))
        assertFalse(fileOf(r).exists())
        assertFalse(partOf(r).exists())
        assertEquals(0L, r.doneBytes)
        // Non è un errore passeggero: nessun nuovo tentativo.
        assertEquals(1, server.requests.size)
    }

    // ------------------------------------------------------------------ ripresa

    @Test
    fun `dopo un taglio della connessione riprende con Range e If-Range`() {
        val content = TestServer.bytes(400_000)
        server.handler = TestServer.file(content, cutFirstAt = 150_000)
        val e = engine()
        val r = finished(e, add(e, "/grande.bin").id)
        assertEquals(r.error, DownloadState.COMPLETED, r.state)
        assertArrayEquals(content, fileOf(r).readBytes())
        assertEquals(2, server.requests.size)
        assertNull(server.requests[0].header("range"))
        assertEquals("bytes=150000-", server.requests[1].header("range"))
        assertEquals("\"v1\"", server.requests[1].header("if-range"))
        assertEquals(TestServer.sha256(content), r.sha256)
    }

    @Test
    fun `taglio con reset della connessione - riprende e il file è identico`() {
        val content = TestServer.bytes(500_000, seed = 11)
        server.handler = TestServer.file(content, cutFirstAt = 200_000, resetOnCut = true)
        val e = engine()
        val r = finished(e, add(e, "/reset.bin").id)
        assertEquals(r.error, DownloadState.COMPLETED, r.state)
        assertArrayEquals(content, fileOf(r).readBytes())
        assertTrue(server.requests.size >= 2)
        assertTrue(server.requests[1].header("range")!!.startsWith("bytes="))
    }

    @Test
    fun `se il server ignora Range si riparte da capo senza duplicare byte`() {
        val content = TestServer.bytes(300_000)
        server.handler = TestServer.file(content, supportRange = false, cutFirstAt = 100_000)
        val e = engine()
        val r = finished(e, add(e, "/senzarange.bin").id)
        assertEquals(r.error, DownloadState.COMPLETED, r.state)
        assertEquals(content.size.toLong(), fileOf(r).length())
        assertArrayEquals(content, fileOf(r).readBytes())
        assertEquals(2, server.requests.size)
    }

    @Test
    fun `se il file sul server è cambiato non si mescolano versioni diverse`() {
        val v1 = TestServer.bytes(300_000, seed = 1)
        val v2 = TestServer.bytes(300_000, seed = 2)
        val first = TestServer.file(v1, etag = "\"v1\"", cutFirstAt = 120_000)
        val second = TestServer.file(v2, etag = "\"v2\"")
        server.handler = { req -> if (server.requests.size <= 1) first(req) else second(req) }
        val e = engine()
        val r = finished(e, add(e, "/cambia.bin").id)
        assertEquals(r.error, DownloadState.COMPLETED, r.state)
        // Il server non ha riconosciuto il validatore (If-Range) e ha mandato la nuova versione intera.
        assertArrayEquals(v2, fileOf(r).readBytes())
        assertEquals(TestServer.sha256(v2), r.sha256)
    }

    @Test
    fun `senza ETag né data non si riprende alla cieca`() {
        val content = TestServer.bytes(200_000)
        server.handler = TestServer.file(content, etag = null, cutFirstAt = 80_000)
        val e = engine()
        val r = finished(e, add(e, "/senzavalidatore.bin").id)
        assertEquals(r.error, DownloadState.COMPLETED, r.state)
        assertArrayEquals(content, fileOf(r).readBytes())
        // Nessun Range: senza modo di sapere se il file è lo stesso si riparte da zero.
        assertNull(server.requests[1].header("range"))
    }

    @Test
    fun `un parziale già completo viene solo verificato (416)`() {
        val content = TestServer.bytes(120_000)
        server.handler = TestServer.file(content)
        // Un download rimasto in pausa con tutti i byte già sul disco.
        val id = "completo0001"
        downloads.mkdirs()
        File(downloads, "$id.part").writeBytes(content)
        val rec = DownloadRecord(
            id, server.url("/quasi.bin"), "quasi.bin", false, DownloadState.PAUSED, content.size.toLong(), content.size.toLong(),
            null, null, "\"v1\"", null, null, null, null, 1, 1
        )
        store.write(DownloadEngine.SECTION, listOf(rec.toMap()))
        val e = engine()
        e.resume(id)
        val r = finished(e, id)
        assertEquals(r.error, DownloadState.COMPLETED, r.state)
        assertArrayEquals(content, fileOf(r).readBytes())
        assertEquals("bytes=${content.size}-", server.requests[0].header("range"))
    }

    @Test
    fun `senza tentativi automatici il parziale resta e Riprova continua da lì`() {
        val content = TestServer.bytes(250_000)
        server.handler = TestServer.file(content, cutFirstAt = 90_000)
        val e = engine(fast(retries = longArrayOf()))
        val failed = await(e, add(e, "/manuale.bin").id) { it.state == DownloadState.FAILED }
        assertTrue(failed.error!!, failed.error!!.contains("incompleto"))
        assertEquals(90_000L, partOf(failed).length())
        e.retry(failed.id)
        val r = finished(e, failed.id)
        assertEquals(r.error, DownloadState.COMPLETED, r.state)
        assertArrayEquals(content, fileOf(r).readBytes())
        assertEquals("bytes=90000-", server.requests[1].header("range"))
    }

    // ------------------------------------------------------------------ errori del server

    @Test
    fun `errore 404 - non si riprova`() {
        server.handler = { TestServer.Response(404, body = "no".toByteArray()) }
        val e = engine()
        val r = finished(e, add(e, "/manca.jar").id)
        assertEquals(DownloadState.FAILED, r.state)
        assertTrue(r.error!!, r.error!!.contains("404"))
        assertEquals(1, server.requests.size)
        assertFalse(fileOf(r).exists())
    }

    @Test
    fun `errore 403 - non si riprova`() {
        server.handler = { TestServer.Response(403) }
        val e = engine()
        val r = finished(e, add(e, "/vietato.jar").id)
        assertEquals(DownloadState.FAILED, r.state)
        assertTrue(r.error!!.contains("403"))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `errore passeggero 503 - riprova da solo e poi riesce`() {
        val content = TestServer.bytes(60_000)
        val ok = TestServer.file(content)
        val calls = AtomicInteger(0)
        server.handler = { req -> if (calls.incrementAndGet() == 1) TestServer.Response(503) else ok(req) }
        val e = engine()
        val r = finished(e, add(e, "/occupato.jar").id)
        assertEquals(r.error, DownloadState.COMPLETED, r.state)
        assertEquals(2, server.requests.size)
        assertNull(r.error)
    }

    @Test
    fun `errore 503 che non passa - dopo i tentativi risulta non riuscito`() {
        server.handler = { TestServer.Response(503) }
        val e = engine()
        val r = finished(e, add(e, "/sempre503.jar").id)
        assertEquals(DownloadState.FAILED, r.state)
        assertTrue(r.error!!.contains("503"))
        assertEquals(4, server.requests.size) // 1 + 3 nuovi tentativi
    }

    // ------------------------------------------------------------------ limiti

    @Test
    fun `file più grande del limite - rifiutato prima di scaricarlo`() {
        server.handler = TestServer.file(TestServer.bytes(500_000))
        val e = engine(fast(maxBytes = 100_000))
        val r = finished(e, add(e, "/enorme.bin").id)
        assertEquals(DownloadState.FAILED, r.state)
        assertTrue(r.error!!, r.error!!.contains("limite"))
        assertFalse(partOf(r).exists())
        assertFalse(fileOf(r).exists())
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `senza dimensione dichiarata il limite vale comunque durante la lettura`() {
        val content = TestServer.bytes(500_000)
        server.handler = { TestServer.Response(200, body = content, declaredLength = null, chunked = true) }
        val e = engine(fast(maxBytes = 100_000))
        val r = finished(e, add(e, "/chunked-enorme.bin").id)
        assertEquals(DownloadState.FAILED, r.state)
        assertTrue(r.error!!, r.error!!.contains("limite"))
        assertFalse(partOf(r).exists())
    }

    @Test
    fun `spazio insufficiente - non si comincia`() {
        server.handler = TestServer.file(TestServer.bytes(500_000))
        val e = engine(fast(free = { 10_000L }))
        val r = finished(e, add(e, "/pieno.bin").id)
        assertEquals(DownloadState.FAILED, r.state)
        assertTrue(r.error!!, r.error!!.contains("Spazio insufficiente"))
        assertFalse(partOf(r).exists())
    }

    @Test
    fun `senza dimensione dichiarata scarica comunque`() {
        val content = TestServer.bytes(200_000)
        server.handler = { TestServer.Response(200, body = content, declaredLength = null, chunked = true) }
        val e = engine()
        val r = finished(e, add(e, "/chunked.bin").id)
        assertEquals(r.error, DownloadState.COMPLETED, r.state)
        assertArrayEquals(content, fileOf(r).readBytes())
        assertEquals(content.size.toLong(), r.totalBytes)
    }

    // ------------------------------------------------------------------ pausa, annulla, coda

    @Test
    fun `pausa e ripresa`() {
        val content = TestServer.bytes(1_500_000)
        server.handler = TestServer.file(content, chunkSize = 32 * 1024, delayPerChunkMs = 20)
        val e = engine()
        val id = add(e, "/lento.bin").id
        await(e, id) { it.state == DownloadState.RUNNING && it.doneBytes > 0 }
        e.pause(id)
        val paused = await(e, id) { it.state == DownloadState.PAUSED }
        assertNull(paused.error)
        val partial = partOf(paused).length()
        assertTrue("parziale $partial", partial > 0 && partial < content.size)
        e.resume(id)
        val r = finished(e, id)
        assertEquals(r.error, DownloadState.COMPLETED, r.state)
        assertArrayEquals(content, fileOf(r).readBytes())
        assertTrue(server.requests.last().header("range")!!.startsWith("bytes="))
    }

    @Test
    fun `annulla cancella il file parziale`() {
        val content = TestServer.bytes(1_500_000)
        server.handler = TestServer.file(content, chunkSize = 32 * 1024, delayPerChunkMs = 20)
        val e = engine()
        val id = add(e, "/daannullare.bin").id
        await(e, id) { it.state == DownloadState.RUNNING && it.doneBytes > 0 }
        e.cancel(id)
        val r = await(e, id) { it.state == DownloadState.CANCELED }
        assertFalse(partOf(r).exists())
        assertFalse(fileOf(r).exists())
        assertEquals(0L, r.doneBytes)
    }

    @Test
    fun `annullare un download in coda o in pausa lo toglie dalla rete`() {
        val content = TestServer.bytes(1_000_000)
        server.handler = TestServer.file(content, chunkSize = 32 * 1024, delayPerChunkMs = 30)
        val e = engine(fast(maxParallel = 1))
        val first = add(e, "/uno.bin")
        val second = add(e, "/due.bin")
        assertEquals(DownloadState.QUEUED, e.record(second.id)!!.state)
        await(e, first.id) { it.state == DownloadState.RUNNING && it.doneBytes > 0 }
        e.cancel(second.id)
        assertEquals(DownloadState.CANCELED, e.record(second.id)!!.state)
        e.cancel(first.id)
        await(e, first.id) { it.state == DownloadState.CANCELED }
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `la coda rispetta il numero massimo di download in parallelo`() {
        val content = TestServer.bytes(300_000)
        server.handler = TestServer.file(content, chunkSize = 32 * 1024, delayPerChunkMs = 25)
        val e = engine(fast(maxParallel = 2))
        val maxRunning = AtomicInteger(0)
        e.addListener { list ->
            val running = list.count { it.state == DownloadState.RUNNING }
            while (true) {
                val m = maxRunning.get()
                if (running <= m || maxRunning.compareAndSet(m, running)) break
            }
        }
        val ids = (1..6).map { add(e, "/p$it.bin").id }
        for (id in ids) assertEquals(DownloadState.COMPLETED, finished(e, id).state)
        assertEquals("download in corso nello stesso momento", 2, maxRunning.get())
        for (id in ids) assertArrayEquals(content, fileOf(e.record(id)!!).readBytes())
    }

    @Test
    fun `i download partono nell'ordine in cui sono stati messi in coda`() {
        val content = TestServer.bytes(100_000)
        server.handler = TestServer.file(content)
        val e = engine(fast(maxParallel = 1))
        val ids = (1..4).map { add(e, "/ordine$it.bin").id }
        for (id in ids) finished(e, id)
        assertEquals((1..4).map { "/ordine$it.bin" }, server.requests.map { it.path })
    }

    // ------------------------------------------------------------------ reindirizzamenti e indirizzi

    @Test
    fun `segue un reindirizzamento verso lo stesso sito`() {
        val content = TestServer.bytes(70_000)
        val file = TestServer.file(content)
        server.handler = { req ->
            when (req.path) {
                "/a" -> TestServer.Response(302, mapOf("Location" to "/dir/b"))
                "/dir/b" -> TestServer.Response(302, mapOf("Location" to server.url("/dir/vero.jar")))
                else -> file(req)
            }
        }
        val e = engine()
        val r = finished(e, add(e, "/a").id)
        assertEquals(r.error, DownloadState.COMPLETED, r.state)
        // Il nome si ricava dall'indirizzo richiesto («a» non ha estensione): resta un nome provvisorio senza suggerimenti.
        assertEquals(3, server.requests.size)
        assertArrayEquals(content, fileOf(r).readBytes())
    }

    @Test
    fun `reindirizzamento verso un sito non consentito - in pausa in attesa del consenso`() {
        server.handler = { TestServer.Response(302, mapOf("Location" to "https://altro-sito.example/file.jar")) }
        val e = engine()
        val r = await(e, add(e, "/vai.jar").id) { it.state == DownloadState.PAUSED }
        assertEquals("altro-sito.example", r.pendingHost)
        assertTrue(r.error!!, r.error!!.contains("altro-sito.example"))
        // Il motore non ha provato a contattare quel sito: c'è stata una sola richiesta, al server di prova.
        assertEquals(1, server.requests.size)
        assertTrue(e.approveHost("altro-sito.example"))
        assertTrue(DownloadEngine.approvedHosts(store).contains("altro-sito.example"))
        // Ora la politica lo ammette (la ripresa vera andrebbe in rete: qui basta il controllo).
        assertTrue(policy().check("https://altro-sito.example/file.jar") is UrlVerdict.Allowed)
    }

    @Test
    fun `reindirizzamento verso http o verso un indirizzo numerico - rifiutato`() {
        server.handler = { req ->
            when (req.path) {
                "/http" -> TestServer.Response(302, mapOf("Location" to "http://example.com/x.jar"))
                else -> TestServer.Response(302, mapOf("Location" to "https://10.0.0.5/x.jar"))
            }
        }
        val e = engine()
        val a = finished(e, add(e, "/http").id)
        assertEquals(DownloadState.FAILED, a.state)
        assertTrue(a.error!!, a.error!!.contains("https"))
        val b = finished(e, add(e, "/ip").id)
        assertEquals(DownloadState.FAILED, b.state)
        assertTrue(b.error!!, b.error!!.contains("numerico"))
    }

    @Test
    fun `troppi reindirizzamenti`() {
        server.handler = { TestServer.Response(302, mapOf("Location" to "/giro")) }
        val e = engine()
        val r = finished(e, add(e, "/giro").id)
        assertEquals(DownloadState.FAILED, r.state)
        assertTrue(r.error!!, r.error!!.contains("reindirizzamenti"))
        assertEquals(4, server.requests.size) // richiesta + 3 reindirizzamenti consentiti
    }

    @Test
    fun `indirizzi non ammessi vengono rifiutati all'accodamento`() {
        val e = engine()
        val http = e.enqueue(DownloadRequest("http://example.com/x.jar")) as EnqueueResult.Rejected
        assertTrue(http.message, http.message.contains("https"))
        assertNull(http.hostToApprove)
        val unknown = e.enqueue(DownloadRequest("https://sito-nuovo.example/x.zip")) as EnqueueResult.Rejected
        assertEquals("sito-nuovo.example", unknown.hostToApprove)
        assertTrue(e.enqueue(DownloadRequest("https://127.0.0.1/x.zip")) is EnqueueResult.Rejected)
        assertTrue(e.enqueue(DownloadRequest("")) is EnqueueResult.Rejected)
        assertTrue(e.records().isEmpty())
        assertEquals(0, server.requests.size)
        // Dopo l'approvazione lo stesso indirizzo passa il controllo (non si scarica davvero: nessuna rete in questo test).
        assertTrue(e.approveHost("sito-nuovo.example"))
        assertTrue(policy().check("https://sito-nuovo.example/x.zip") is UrlVerdict.Allowed)
        assertFalse(e.approveHost("127.0.0.1"))
        assertFalse(e.approveHost("https://sito-nuovo.example/x.zip"))
    }

    // ------------------------------------------------------------------ nomi

    @Test
    fun `il nome viene dal server quando l'indirizzo non ha estensione`() {
        val content = TestServer.bytes(40_000)
        server.handler = TestServer.file(content, extraHeaders = mapOf("Content-Disposition" to "attachment; filename=\"Mappa Bella.mcworld\""))
        val e = engine()
        val r = finished(e, add(e, "/get?id=3").id)
        assertEquals(r.error, DownloadState.COMPLETED, r.state)
        assertEquals("Mappa Bella.mcworld", r.fileName)
        assertTrue(fileOf(r).exists())
    }

    @Test
    fun `il nome dell'indirizzo ha la precedenza quando ha un'estensione`() {
        val content = TestServer.bytes(40_000)
        server.handler = TestServer.file(content, extraHeaders = mapOf("Content-Disposition" to "attachment; filename=\"altro.zip\""))
        val e = engine()
        val r = finished(e, add(e, "/mio.mcpack").id)
        assertEquals("mio.mcpack", r.fileName)
        val named = finished(e, add(e, "/qualunque", name = "scelto.mcaddon").id)
        assertEquals("scelto.mcaddon", named.fileName)
    }

    @Test
    fun `due file con lo stesso nome non si sovrascrivono`() {
        val content = TestServer.bytes(30_000)
        server.handler = TestServer.file(content)
        val e = engine()
        val a = add(e, "/x/mod.jar")
        val b = add(e, "/y/mod.jar")
        assertEquals("mod.jar", a.fileName)
        assertEquals("mod (1).jar", b.fileName)
        assertEquals(DownloadState.COMPLETED, finished(e, a.id).state)
        assertEquals(DownloadState.COMPLETED, finished(e, b.id).state)
        assertTrue(File(downloads, "mod.jar").exists() && File(downloads, "mod (1).jar").exists())
        // Scaricare di nuovo lo stesso indirizzo, dopo averlo finito, è permesso e dà un terzo nome.
        val c = add(e, "/x/mod.jar")
        assertEquals("mod (2).jar", c.fileName)
    }

    @Test
    fun `lo stesso indirizzo due volte insieme viene rifiutato`() {
        server.handler = TestServer.file(TestServer.bytes(500_000), chunkSize = 16 * 1024, delayPerChunkMs = 30)
        val e = engine()
        add(e, "/doppio.bin")
        val second = e.enqueue(DownloadRequest(server.url("/doppio.bin")))
        assertTrue(second is EnqueueResult.Rejected)
        assertEquals(1, e.records().size)
    }

    // ------------------------------------------------------------------ persistenza e riavvio

    @Test
    fun `i download completati sopravvivono al riavvio`() {
        val content = TestServer.bytes(50_000)
        server.handler = TestServer.file(content)
        val e1 = engine()
        val done = finished(e1, add(e1, "/resta.jar").id)
        e1.shutdown()
        val e2 = engine()
        val again = e2.record(done.id)!!
        assertEquals(DownloadState.COMPLETED, again.state)
        assertEquals(done.sha256, again.sha256)
        assertEquals(done.fileName, again.fileName)
        assertArrayEquals(content, e2.file(again).readBytes())
    }

    @Test
    fun `un download interrotto dalla chiusura dell'app resta in pausa e riprende senza ripartire da zero`() {
        val content = TestServer.bytes(1_200_000)
        server.handler = TestServer.file(content, chunkSize = 32 * 1024, delayPerChunkMs = 25)
        val e1 = engine()
        val id = add(e1, "/chiusura.bin").id
        await(e1, id) { it.state == DownloadState.RUNNING && it.doneBytes > 100_000 }
        e1.shutdown()
        await(e1, id) { it.state == DownloadState.PAUSED }
        // Come se il processo fosse stato ucciso: nell'archivio il download risulta ancora «in corso».
        val saved = store.read(DownloadEngine.SECTION).asJsonList()!!.map { DownloadRecord.fromValue(it)!! }
        store.write(DownloadEngine.SECTION, saved.map { it.copy(state = DownloadState.RUNNING).toMap() })
        val partial = File(downloads, "$id.part").length()
        assertTrue("parziale $partial", partial > 0 && partial < content.size)

        val e2 = engine()
        val back = e2.record(id)!!
        assertEquals("niente parte da solo all'avvio", DownloadState.PAUSED, back.state)
        assertTrue(back.error!!, back.error!!.contains("RIPRENDI"))
        val requestsBefore = server.requests.size
        e2.resume(id)
        val r = finished(e2, id)
        assertEquals(r.error, DownloadState.COMPLETED, r.state)
        assertArrayEquals(content, fileOf(r).readBytes())
        assertEquals("bytes=$partial-", server.requests[requestsBefore].header("range"))
    }

    @Test
    fun `all'avvio i parziali senza download vengono tolti`() {
        downloads.mkdirs()
        val orphan = File(downloads, "senzapadrone.part")
        orphan.writeText("x")
        engine()
        assertFalse(orphan.exists())
    }

    @Test
    fun `un archivio con voci rovinate non blocca il motore`() {
        store.write(DownloadEngine.SECTION, listOf("testo", mapOf("id" to "senzaurl"), 42))
        val e = engine()
        assertTrue(e.records().isEmpty())
    }

    @Test
    fun `togliere dall'elenco cancella anche il file`() {
        val content = TestServer.bytes(30_000)
        server.handler = TestServer.file(content)
        val e = engine()
        val r = finished(e, add(e, "/via.jar").id)
        assertTrue(fileOf(r).exists())
        assertTrue(e.remove(r.id))
        assertFalse(fileOf(r).exists())
        assertNull(e.record(r.id))
        assertTrue(store.read(DownloadEngine.SECTION).asJsonList()!!.isEmpty())
    }

    @Test
    fun `non si toglie dall'elenco un download in corso`() {
        server.handler = TestServer.file(TestServer.bytes(1_000_000), chunkSize = 32 * 1024, delayPerChunkMs = 30)
        val e = engine()
        val id = add(e, "/incorso.bin").id
        await(e, id) { it.state == DownloadState.RUNNING }
        assertFalse(e.remove(id))
        assertNotNull(e.record(id))
        e.cancel(id)
        await(e, id) { it.state == DownloadState.CANCELED }
        assertTrue(e.remove(id))
    }

    @Test
    fun `i listener ricevono gli aggiornamenti`() {
        server.handler = TestServer.file(TestServer.bytes(100_000))
        val e = engine()
        val seen = java.util.concurrent.CopyOnWriteArrayList<DownloadState>()
        val remove = e.addListener { list -> list.firstOrNull()?.let { seen.add(it.state) } }
        val id = add(e, "/ascolto.jar").id
        finished(e, id)
        remove()
        assertTrue(seen.toString(), seen.contains(DownloadState.QUEUED) || seen.contains(DownloadState.RUNNING))
        assertEquals(DownloadState.COMPLETED, seen.last())
        val count = seen.size
        add(e, "/dopo.jar")
        Thread.sleep(300)
        assertEquals("dopo averlo tolto non riceve più nulla", count, seen.size)
    }
}
