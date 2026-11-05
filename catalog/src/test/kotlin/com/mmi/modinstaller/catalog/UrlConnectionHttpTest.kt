package com.mmi.modinstaller.catalog

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** La rete vera del catalogo contro un server locale: regole di sicurezza, errori, limiti e annullamento. */
class UrlConnectionHttpTest {

    private lateinit var server: TestServer

    @Before fun setUp() {
        server = TestServer()
    }

    @After fun tearDown() {
        server.close()
    }

    private fun http(
        allowed: Set<String> = setOf("api.modrinth.com"),
        max: Int = 1024 * 1024,
        readTimeout: Int = 3_000
    ) = UrlConnectionHttp(
        userAgent = "MinecraftModInstaller/test", allowedHosts = allowed, insecureHosts = setOf("127.0.0.1:${server.port}"),
        maxBodyBytes = max, connectTimeoutMs = 2_000, readTimeoutMs = readTimeout
    )

    private fun expect(kind: CatalogError, block: () -> Unit): CatalogException {
        try {
            block()
        } catch (e: CatalogException) {
            assertEquals(e.message, kind, e.kind)
            return e
        }
        fail("doveva fallire con $kind")
        throw IllegalStateException()
    }

    private val noCancel: () -> Unit = {}

    private fun json(text: String, status: Int = 200, headers: Map<String, String> = emptyMap()) =
        TestServer.Response(status, headers + ("Content-Type" to "application/json"), text.toByteArray())

    @Test fun readsAJsonBodyAndSendsOnlyTheAppIdentity() {
        server.handler = { json("""{"ok":true,"testo":"àèì ✓"}""") }
        val reply = http().get(server.url("/v2/search?query=a"), noCancel)
        assertEquals(200, reply.status)
        assertEquals("""{"ok":true,"testo":"àèì ✓"}""", reply.body)
        val req = server.requests.single()
        assertEquals("GET", req.method)
        assertEquals("/v2/search?query=a", req.path)
        assertEquals("MinecraftModInstaller/test", req.header("user-agent"))
        assertEquals("application/json", req.header("accept"))
        assertEquals("identity", req.header("accept-encoding"))
        // Nessun dato dell'utente: niente cookie, niente autorizzazioni.
        assertTrue(req.headers.keys.none { it == "cookie" || it == "authorization" })
    }

    @Test fun errorStatusesAreReturnedWithTheirBodyAndRetryAfter() {
        server.handler = { json("""{"error":"ratelimit"}""", 429, mapOf("Retry-After" to "17")) }
        val reply = http().get(server.url("/x"), noCancel)
        assertEquals(429, reply.status)
        assertEquals(17, reply.retryAfterSeconds)
        assertTrue(reply.body.contains("ratelimit"))
        server.handler = { json("""{}""", 404) }
        assertEquals(404, http().get(server.url("/x"), noCancel).status)
        server.handler = { json("""{}""", 500, mapOf("X-Ratelimit-Reset" to "9")) }
        val r500 = http().get(server.url("/x"), noCancel)
        assertEquals(500, r500.status)
        assertEquals("senza Retry-After si usa il tempo di reset", 9, r500.retryAfterSeconds)
        server.handler = { json("""{}""", 429, mapOf("Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT")) }
        assertEquals("una data al posto dei secondi non vale", null, http().get(server.url("/x"), noCancel).retryAfterSeconds)
    }

    @Test fun redirectsAreNeverFollowed() {
        server.handler = { TestServer.Response(302, mapOf("Location" to "https://evil.example/steal"), ByteArray(0)) }
        expect(CatalogError.BAD_RESPONSE) { http().get(server.url("/x"), noCancel) }
        assertEquals("nessuna seconda richiesta", 1, server.requests.size)
    }

    @Test fun anOversizedBodyIsRefusedWhetherDeclaredOrNot() {
        val big = ByteArray(200_000) { 'a'.code.toByte() }
        server.handler = { TestServer.Response(200, emptyMap(), big) }
        expect(CatalogError.TOO_LARGE) { http(max = 100_000).get(server.url("/x"), noCancel) }
        // Senza dimensione dichiarata (a pezzi): si accorge lo stesso, mentre legge.
        server.handler = { TestServer.Response(200, emptyMap(), big, chunked = true) }
        expect(CatalogError.TOO_LARGE) { http(max = 100_000).get(server.url("/x"), noCancel) }
        // Sotto il limite passa.
        server.handler = { TestServer.Response(200, emptyMap(), big) }
        assertEquals(200_000, http(max = 300_000).get(server.url("/x"), noCancel).body.length)
    }

    @Test fun anHugeErrorBodyIsTruncatedNotRefused() {
        val big = ByteArray(500_000) { 'e'.code.toByte() }
        server.handler = { TestServer.Response(500, emptyMap(), big, declaredLength = null, chunked = true) }
        val reply = http(max = 1_000_000).get(server.url("/x"), noCancel)
        assertEquals(500, reply.status)
        assertTrue(reply.body.length <= 64 * 1024)
    }

    @Test fun aSlowServerGivesATimeout() {
        server.handler = { TestServer.Response(200, emptyMap(), ByteArray(100_000), chunkSize = 1000, delayPerChunkMs = 400) }
        val e = expect(CatalogError.TIMEOUT) { http(readTimeout = 300).get(server.url("/x"), noCancel) }
        assertTrue(e.message, e.message!!.isNotBlank())
    }

    @Test fun aClosedPortGivesANetworkError() {
        val port = server.port
        server.close()
        expect(CatalogError.NETWORK) {
            UrlConnectionHttp("x", insecureHosts = setOf("127.0.0.1:$port"), connectTimeoutMs = 1_000).get("http://127.0.0.1:$port/x", noCancel)
        }
    }

    @Test fun cancelBeforeAndDuringTheTransferStopsIt() {
        server.handler = { TestServer.Response(200, emptyMap(), ByteArray(400_000), chunkSize = 4096, delayPerChunkMs = 5) }
        try {
            http().get(server.url("/x"), { throw IllegalStateException("annullato") })
            fail()
        } catch (e: IllegalStateException) {
            assertEquals(0, server.requests.size)
        }
        var reads = 0
        try {
            http(max = 10_000_000).get(server.url("/x"), { if (++reads > 5) throw IllegalStateException("annullato") })
            fail("doveva interrompersi durante la lettura")
        } catch (e: IllegalStateException) {
            assertTrue("ha letto qualcosa prima di fermarsi", reads > 1)
        }
    }

    @Test fun onlyAllowedHostsAndSecureAddressesAreContacted() {
        val h = http()
        val bad = listOf(
            "http://api.modrinth.com/v2/search",           // senza cifratura
            "https://evil.example/v2/search",               // sito non ammesso
            "https://api.modrinth.com.evil.example/x",      // sito che finge di essere quello giusto
            "https://user:pass@api.modrinth.com/x",         // credenziali nell'indirizzo
            "https://api.modrinth.com:8443/x",              // porta non standard
            "ftp://api.modrinth.com/x",
            "file:///etc/passwd",
            "https://127.0.0.1/x",
            "http://127.0.0.1:1/x",                         // http verso una porta che non è quella di prova
            "non un indirizzo",
            ""
        )
        for (url in bad) {
            val e = expect(CatalogError.BLOCKED) { h.get(url, noCancel) }
            assertTrue(url + " → " + e.message, e.message!!.isNotBlank())
        }
        assertTrue("nessuna richiesta deve essere partita", server.requests.isEmpty())
        // Gli indirizzi giusti passano il controllo.
        h.checkUrl("https://api.modrinth.com/v2/search?query=a")
        h.checkUrl("https://API.MODRINTH.COM:443/v2/x")
        h.checkUrl("http://127.0.0.1:${server.port}/x")
        assertFalse(h.checkUrl("https://api.modrinth.com/v2/x").host.isNullOrEmpty())
    }

    @Test fun anotherServiceCanBeAllowedExplicitly() {
        UrlConnectionHttp("x", allowedHosts = setOf("api.modrinth.com", "example.org")).checkUrl("https://example.org/x")
        expect(CatalogError.BLOCKED) { UrlConnectionHttp("x").checkUrl("https://example.org/x") }
    }

    @Test fun theWholeStackWorksAgainstALocalServiceThroughTheApi() {
        server.handler = { req ->
            when {
                req.path.startsWith("/v2/search") -> json(
                    """{"hits":[{"project_id":"AAAA1111","slug":"demo","title":"Demo","description":"d","downloads":5,"versions":["1.20.1"],"categories":["fabric"],"project_type":"mod"}],"offset":0,"limit":20,"total_hits":1}"""
                )
                else -> json("""{"error":"not_found","description":"no"}""", 404)
            }
        }
        val api = ModrinthApi(http(), "http://127.0.0.1:${server.port}/v2")
        val page = api.search("demo", ProjectKind.MOD, listOf("fabric"), "1.20.1", SearchSort.RELEVANCE, 0, 20, noCancel)
        assertEquals("Demo", page.hits.single().title)
        expect(CatalogError.NOT_FOUND) { api.projectVersions("nonesiste", emptyList(), emptyList(), noCancel) }
    }
}
