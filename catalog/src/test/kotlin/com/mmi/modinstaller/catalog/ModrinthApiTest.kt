package com.mmi.modinstaller.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URLDecoder

/** Il client di Modrinth contro risposte scritte a mano secondo lo schema ufficiale (https://docs.modrinth.com/api/). */
class ModrinthApiTest {

    /** Finto servizio: ricorda gli indirizzi richiesti e risponde con quello che gli si dice. */
    private class FakeHttp(var reply: (String) -> HttpReply = { HttpReply(200, "[]") }) : CatalogHttp {
        val urls = ArrayList<String>()
        override fun get(url: String, cancel: () -> Unit): HttpReply {
            urls.add(url)
            return reply(url)
        }
    }

    private val base = "https://api.modrinth.com/v2"
    private val noCancel: () -> Unit = {}

    private fun decoded(url: String): String = URLDecoder.decode(url.replace("+", "%2B"), "UTF-8")

    private fun api(http: FakeHttp) = ModrinthApi(http, base)

    private fun search(
        http: FakeHttp, query: String = "sodium", kind: ProjectKind = ProjectKind.MOD, loaders: List<String> = listOf("fabric"),
        game: String? = "1.20.1", sort: SearchSort = SearchSort.RELEVANCE, offset: Int = 0, limit: Int = 20
    ) = api(http).search(query, kind, loaders, game, sort, offset, limit, noCancel)

    // ------------------------------------------------------------------ gli indirizzi richiesti

    @Test fun searchAsksForTheRightFilters() {
        val http = FakeHttp { HttpReply(200, """{"hits":[],"offset":0,"limit":20,"total_hits":0}""") }
        search(http, loaders = listOf("quilt", "fabric"))
        val url = decoded(http.urls.single())
        assertTrue(url, url.startsWith("$base/search?query=sodium&"))
        assertTrue(url, url.contains("""facets=[["project_type:mod"],["categories:quilt","categories:fabric"],["versions:1.20.1"]]"""))
        assertTrue(url, url.contains("index=relevance") && url.contains("offset=0") && url.contains("limit=20"))
    }

    @Test fun searchWithoutFiltersAsksOnlyForTheKind() {
        val http = FakeHttp { HttpReply(200, """{"hits":[],"offset":0,"limit":20,"total_hits":0}""") }
        search(http, loaders = emptyList(), game = null, sort = SearchSort.DOWNLOADS)
        val url = decoded(http.urls.single())
        assertTrue(url, url.contains("""facets=[["project_type:mod"]]"""))
        assertTrue(url, url.contains("index=downloads"))
    }

    @Test fun resourcePacksAndShadersAreNotFilteredByLoader() {
        val http = FakeHttp { HttpReply(200, """{"hits":[],"offset":0,"limit":20,"total_hits":0}""") }
        search(http, kind = ProjectKind.RESOURCE_PACK, loaders = listOf("fabric"))
        search(http, kind = ProjectKind.SHADER, loaders = listOf("fabric"))
        val a = decoded(http.urls[0])
        assertTrue(a, a.contains("""["project_type:resourcepack"]""") && !a.contains("categories:"))
        assertTrue(decoded(http.urls[1]).contains("""["project_type:shader"]"""))
    }

    @Test fun valuesThatCouldBreakTheFilterNeverReachTheRequest() {
        val http = FakeHttp { HttpReply(200, """{"hits":[],"offset":0,"limit":20,"total_hits":0}""") }
        search(http, loaders = listOf("""fabric"],["project_type:modpack""", "forge", "a b", ""), game = """1.20"],["x""")
        val url = decoded(http.urls.single())
        assertTrue(url, url.contains("""facets=[["project_type:mod"],["categories:forge"]]"""))
        assertFalse(url, url.contains("modpack") || url.contains("\"x"))
    }

    @Test fun theSearchTextIsEncodedAndShortened() {
        val http = FakeHttp { HttpReply(200, """{"hits":[],"offset":0,"limit":20,"total_hits":0}""") }
        search(http, query = "  fabric api & more=1#x  ")
        val raw = http.urls.single()
        assertTrue(raw, raw.contains("query=fabric%20api%20%26%20more%3D1%23x&"))
        search(http, query = "x".repeat(500))
        assertEquals(100, Regex("query=(x+)&").find(http.urls[1])!!.groupValues[1].length)
    }

    @Test fun limitAndOffsetAreKeptInRange() {
        val http = FakeHttp { HttpReply(200, """{"hits":[],"offset":0,"limit":20,"total_hits":0}""") }
        search(http, limit = 10_000, offset = -5)
        val url = http.urls.single()
        assertTrue(url, url.contains("limit=50") && url.contains("offset=0"))
        search(http, limit = 0)
        assertTrue(http.urls[1].contains("limit=1"))
    }

    @Test fun versionsOfAProjectAreFilteredByLoadersAndGameVersions() {
        val http = FakeHttp()
        api(http).projectVersions("AABBCCDD", listOf("fabric", "quilt"), listOf("1.20.1"), noCancel)
        val url = decoded(http.urls.single())
        assertEquals("""$base/project/AABBCCDD/version?loaders=["fabric","quilt"]&game_versions=["1.20.1"]""", url)
        api(http).projectVersions("sodium", emptyList(), emptyList(), noCancel)
        assertEquals("$base/project/sodium/version", http.urls[1])
    }

    @Test fun aProjectIdThatCouldEscapeThePathIsRefused() {
        val http = FakeHttp()
        for (bad in listOf("../admin", "a/b", "x?y=1", "", "  ", "a b")) {
            try {
                api(http).projectVersions(bad, emptyList(), emptyList(), noCancel)
                fail("«$bad» doveva essere rifiutato")
            } catch (e: CatalogException) {
                assertEquals(CatalogError.BAD_RESPONSE, e.kind)
            }
        }
        assertTrue("nessuna richiesta deve partire", http.urls.isEmpty())
    }

    @Test fun manyIdsAreSplitInSeveralRequests() {
        val http = FakeHttp()
        val ids = (1..120).map { "ID%05d".format(it) }
        api(http).versions(ids, noCancel)
        assertEquals(3, http.urls.size)
        assertTrue(decoded(http.urls[0]).startsWith("""$base/versions?ids=["ID00001","ID00002""""))
        api(http).projects(listOf("AAAA", "bad id", "AAAA", "BBBB"), noCancel)
        assertEquals("""$base/projects?ids=["AAAA","BBBB"]""", decoded(http.urls.last()))
        val before = http.urls.size
        api(http).projects(emptyList(), noCancel)
        assertEquals("niente da chiedere = nessuna richiesta", before, http.urls.size)
    }

    // ------------------------------------------------------------------ lettura delle risposte

    private val searchBody = """
        {"hits":[
          {"project_id":"AANobbMI","project_type":"mod","slug":"sodium","author":"jellysquid3","title":"Sodium",
           "description":"Il motore di rendering.","categories":["fabric","optimization"],"display_categories":["fabric"],
           "versions":["1.20","1.20.1","1.21"],"downloads":12345678,"follows":3000,"icon_url":"https://cdn.modrinth.com/data/AANobbMI/icon.png",
           "date_created":"2021-01-03T00:00:00Z","date_modified":"2024-05-01T00:00:00Z","latest_version":"1.21",
           "license":"LGPL-3.0","client_side":"required","server_side":"unsupported","gallery":[],"featured_gallery":null,"color":123},
          {"project_id":"bad id","title":"Rotto"},
          {"title":"Senza id"},
          "non un oggetto",
          {"project_id":"P2P2P2P2","downloads":"molti","follows":-5,"categories":"nessuna","versions":[1,"1.20.1",null]}
        ],"offset":0,"limit":20,"total_hits":2}
    """.trimIndent()

    @Test fun searchHitsAreReadAndBrokenOnesAreSkipped() {
        val page = search(FakeHttp { HttpReply(200, searchBody) })
        assertEquals(2, page.total)
        assertEquals(listOf("AANobbMI", "P2P2P2P2"), page.hits.map { it.id })
        val sodium = page.hits[0]
        assertEquals("sodium", sodium.slug)
        assertEquals("Sodium", sodium.title)
        assertEquals("jellysquid3", sodium.author)
        assertEquals(12345678L, sodium.downloads)
        assertEquals(listOf("fabric", "optimization"), sodium.categories)
        assertEquals(listOf("1.20", "1.20.1", "1.21"), sodium.gameVersions)
        assertEquals(ProjectKind.MOD, sodium.kind)
        val second = page.hits[1]
        assertEquals("campi di tipo sbagliato non fanno cadere la lettura", 0L, second.downloads)
        assertEquals(0L, second.follows)
        assertTrue(second.categories.isEmpty())
        assertEquals(listOf("1.20.1"), second.gameVersions)
        assertEquals("senza titolo si mostra l'id", "P2P2P2P2", second.displayName)
    }

    @Test fun aProjectObjectUsesIdAndGameVersions() {
        val body = """[{"id":"AANobbMI","slug":"sodium","project_type":"mod","title":"Sodium","description":"d","game_versions":["1.20.1"],
            "versions":["IIJJKKLL","QQRRSSTT"],"loaders":["fabric"],"downloads":10,"followers":5}]"""
        val projects = api(FakeHttp { HttpReply(200, body) }).projects(listOf("AANobbMI"), noCancel)
        assertEquals("AANobbMI", projects.single().id)
        assertEquals(listOf("1.20.1"), projects.single().gameVersions)
    }

    private val versionBody = """
        [{"id":"IIJJKKLL","project_id":"AANobbMI","author_id":"EEFFGGHH","name":"Sodium 0.5.3","version_number":"0.5.3+mc1.20.1",
          "changelog":"…","dependencies":[
            {"version_id":null,"project_id":"P7dR8mSH","file_name":null,"dependency_type":"required"},
            {"version_id":"VVVV1111","project_id":null,"file_name":"x.jar","dependency_type":"optional"},
            {"project_id":"INCOMPAT","dependency_type":"incompatible"},
            {"project_id":"EMBEDDED","dependency_type":"embedded"},
            {"project_id":"WEIRD","dependency_type":"suggested"},
            {"project_id":"bad id!","dependency_type":"required"},
            "no"
          ],"game_versions":["1.20.1"],"version_type":"release","loaders":["fabric","quilt"],"featured":true,"status":"listed",
          "date_published":"2024-03-01T10:00:00.123456Z","downloads":99,
          "files":[
            {"hashes":{"sha1":"0123456789abcdef0123456789abcdef01234567","sha512":"${"ab".repeat(64)}"},
             "url":"https://cdn.modrinth.com/data/AANobbMI/versions/IIJJKKLL/sodium-fabric-0.5.3+mc1.20.1.jar",
             "filename":"sodium-fabric-0.5.3+mc1.20.1.jar","primary":true,"size":1234567,"file_type":null},
            {"hashes":{},"url":"https://cdn.modrinth.com/x.jar","filename":"senza-impronte.jar","primary":false,"size":1},
            {"hashes":{"sha1":"x"},"filename":"senza-url.jar"},
            {"url":"https://cdn.modrinth.com/y.jar"}
          ]},
         {"id":"zzz","project_id":"AANobbMI","version_type":"strange","files":"niente","dependencies":null}]
    """.trimIndent()

    @Test fun versionsAreReadWithFilesAndDependencies() {
        val versions = api(FakeHttp { HttpReply(200, versionBody) }).projectVersions("AANobbMI", emptyList(), emptyList(), noCancel)
        assertEquals(2, versions.size)
        val v = versions[0]
        assertEquals("IIJJKKLL", v.id)
        assertEquals("AANobbMI", v.projectId)
        assertEquals("0.5.3+mc1.20.1", v.versionNumber)
        assertEquals(ReleaseChannel.RELEASE, v.channel)
        assertEquals(listOf("fabric", "quilt"), v.loaders)
        assertEquals("listed", v.status)
        assertEquals("2024-03-01T10:00:00.123456Z", v.datePublished)
        assertTrue(v.featured)
        // File senza indirizzo o senza nome sono scartati; uno senza impronte resta (lo scarta poi chi sceglie).
        assertEquals(listOf("sodium-fabric-0.5.3+mc1.20.1.jar", "senza-impronte.jar"), v.files.map { it.fileName })
        val f = v.files[0]
        assertTrue(f.primary)
        assertEquals(1234567L, f.size)
        assertEquals("sha512:" + "ab".repeat(64), f.expectedHash())
        assertNull(v.files[1].expectedHash())
        // Dipendenze: un tipo sconosciuto è scartato; un id non valido non diventa mai parte di un indirizzo, ma una
        // dipendenza obbligatoria resta (senza identificativi) perché il piano la segnali invece di ignorarla.
        assertEquals(
            listOf(DependencyType.REQUIRED, DependencyType.OPTIONAL, DependencyType.INCOMPATIBLE, DependencyType.EMBEDDED, DependencyType.REQUIRED),
            v.dependencies.map { it.type }
        )
        assertNull(v.dependencies[4].projectId)
        assertNull(v.dependencies[4].versionId)
        assertEquals("P7dR8mSH", v.dependencies[0].projectId)
        assertEquals("VVVV1111", v.dependencies[1].versionId)
        assertEquals("x.jar", v.dependencies[1].fileName)
        // La seconda versione è poverissima ma non fa cadere nulla; un canale sconosciuto vale «alpha».
        assertEquals(ReleaseChannel.ALPHA, versions[1].channel)
        assertTrue(versions[1].files.isEmpty() && versions[1].dependencies.isEmpty())
    }

    @Test fun aHostileResponseCannotMakeTheListsHuge() {
        val deps = (1..500).joinToString(",") { """{"project_id":"P$it","dependency_type":"required"}""" }
        val games = (1..2000).joinToString(",") { "\"1.$it\"" }
        val body = """[{"id":"V1","project_id":"P0","game_versions":[$games],"dependencies":[$deps],"loaders":["fabric"]}]"""
        val v = api(FakeHttp { HttpReply(200, body) }).projectVersions("P0", emptyList(), emptyList(), noCancel).single()
        assertEquals(100, v.dependencies.size)
        assertEquals(500, v.gameVersions.size)
    }

    // ------------------------------------------------------------------ errori

    private fun failure(status: Int, body: String = "", retry: Int? = null): CatalogException {
        try {
            search(FakeHttp { HttpReply(status, body, retry) })
            fail("doveva fallire con codice $status")
        } catch (e: CatalogException) {
            return e
        }
        throw IllegalStateException()
    }

    @Test fun errorCodesBecomeUnderstandableMessages() {
        assertEquals(CatalogError.NOT_FOUND, failure(404).kind)
        val limited = failure(429, "", 42)
        assertEquals(CatalogError.RATE_LIMITED, limited.kind)
        assertEquals(42, limited.retryAfterSeconds)
        assertTrue(limited.message, limited.message!!.contains("42"))
        assertNull(failure(429).retryAfterSeconds)
        assertEquals(CatalogError.SERVER, failure(503).kind)
        val bad = failure(400, """{"error":"invalid_input","description":"facet non valida"}""")
        assertEquals(CatalogError.BAD_RESPONSE, bad.kind)
        assertTrue(bad.message, bad.message!!.contains("facet non valida"))
        assertEquals(CatalogError.BAD_RESPONSE, failure(403, "<html>vietato</html>").kind)
    }

    @Test fun garbageIsReportedNotCrashed() {
        assertEquals(CatalogError.BAD_RESPONSE, failure(200, "<html>captive portal</html>").kind)
        assertEquals(CatalogError.BAD_RESPONSE, failure(200, "").kind)
        assertEquals("un array dove serve un oggetto", CatalogError.BAD_RESPONSE, failure(200, "[1,2,3]").kind)
        try {
            api(FakeHttp { HttpReply(200, """{"hits":[]}""") }).projects(listOf("AAAA"), noCancel)
            fail("un oggetto dove serve un array")
        } catch (e: CatalogException) {
            assertEquals(CatalogError.BAD_RESPONSE, e.kind)
        }
    }

    @Test fun missingTotalsFallBackToWhatWasReceived() {
        val page = search(FakeHttp { HttpReply(200, """{"hits":[{"project_id":"AAAA","title":"A"}]}""") })
        assertEquals(1, page.hits.size)
        assertEquals(1, page.total)
        assertNotNull(page)
    }

    @Test fun networkErrorsFromTheTransportPassThroughUnchanged() {
        val http = object : CatalogHttp {
            override fun get(url: String, cancel: () -> Unit): HttpReply = throw CatalogException(CatalogError.TIMEOUT, "lento")
        }
        try {
            ModrinthApi(http, base).versions(listOf("AAAA"), noCancel)
            fail()
        } catch (e: CatalogException) {
            assertEquals(CatalogError.TIMEOUT, e.kind)
        }
    }
}
