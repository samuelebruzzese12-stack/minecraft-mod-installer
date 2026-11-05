package com.mmi.modinstaller.catalog

import com.mmi.modinstaller.core.MemoryDocumentStore
import com.mmi.modinstaller.download.DownloadEngine
import com.mmi.modinstaller.download.DownloadOptions
import com.mmi.modinstaller.download.DownloadRecord
import com.mmi.modinstaller.download.DownloadRequest
import com.mmi.modinstaller.download.DownloadState
import com.mmi.modinstaller.download.EnqueueResult
import com.mmi.modinstaller.download.ExpectedHash
import com.mmi.modinstaller.download.UrlPolicy
import com.mmi.modinstaller.jar.JarAnalyzer
import com.mmi.modinstaller.jar.ModLoader
import com.mmi.modinstaller.mods.InstallResult
import com.mmi.modinstaller.mods.ModManager
import com.mmi.modinstaller.mods.Outcome
import com.mmi.modinstaller.mods.PlanCode
import com.mmi.modinstaller.mods.PlanDecision
import com.mmi.modinstaller.mods.ProfileDraft
import com.mmi.modinstaller.mods.ProfileRepository
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * La catena completa dell'app, con i motori veri e un finto Modrinth locale (JSON secondo lo schema ufficiale + un
 * finto CDN): ricerca → piano con dipendenze → download con verifica dell'impronta → lettura del .jar → controllo sul
 * profilo → installazione. È ciò che fa l'app quando si sceglie una mod dal catalogo.
 */
class CatalogChainTest {

    private lateinit var base: File
    private lateinit var server: TestServer
    private lateinit var engine: DownloadEngine
    private lateinit var api: ModrinthApi

    /** I due .jar della prova: la mod richiede la libreria. */
    private val libJar = fabricJar("demolib", "Libreria Demo", "1.0.0", emptyMap())
    private var modJar = fabricJar("demomod", "Mod Demo", "2.0.0", mapOf("demolib" to ">=1.0.0"))

    /** Cosa risponde il finto CDN per ogni file (si può cambiare nei test per simulare file alterati). */
    private val cdn = HashMap<String, ByteArray>()

    @Before fun setUp() {
        base = TestServer.tmpDir()
        server = TestServer()
        cdn["/cdn/demomod-2.0.0.jar"] = modJar
        cdn["/cdn/demolib-1.0.0.jar"] = libJar
        server.handler = { req -> handle(req) }
        val options = DownloadOptions(
            maxParallel = 2, connectTimeoutMs = 3_000, readTimeoutMs = 5_000, maxRedirects = 3,
            retryDelaysMs = longArrayOf(30, 30, 30), reserveBytes = 0, freeSpace = { Long.MAX_VALUE }
        )
        engine = DownloadEngine(
            MemoryDocumentStore(), File(base, "downloads"),
            UrlPolicy(insecureHosts = setOf("127.0.0.1:${server.port}")), options
        )
        api = ModrinthApi(
            UrlConnectionHttp("MinecraftModInstaller/test", insecureHosts = setOf("127.0.0.1:${server.port}"), connectTimeoutMs = 2_000, readTimeoutMs = 4_000),
            "http://127.0.0.1:${server.port}/v2"
        )
    }

    @After fun tearDown() {
        engine.shutdown()
        server.close()
        TestServer.deleteTree(base)
    }

    // ------------------------------------------------------------------ il finto servizio

    private var hashFor: (ByteArray) -> String = { Fx.sha512Hex(it) }
    private var hashKind = "sha512"

    private fun fileJson(path: String, name: String, bytes: ByteArray, primary: Boolean = true): String {
        val hashes = if (hashKind == "sha1") """{"sha1":"${Fx.sha1Hex(bytes)}"}""" else """{"sha1":"${Fx.sha1Hex(bytes)}","sha512":"${hashFor(bytes)}"}"""
        return """{"hashes":$hashes,"url":"http://127.0.0.1:${server.port}$path","filename":"$name","primary":$primary,"size":${bytes.size},"file_type":null}"""
    }

    private fun versionJson(id: String, project: String, number: String, path: String, name: String, bytes: ByteArray, deps: String): String =
        """{"id":"$id","project_id":"$project","name":"$name","version_number":"$number","game_versions":["1.20.1"],"version_type":"release",
           "loaders":["fabric"],"featured":true,"status":"listed","date_published":"2024-05-01T00:00:00Z","dependencies":[$deps],
           "files":[${fileJson(path, name, bytes)}]}"""

    private fun handle(req: TestServer.Request): TestServer.Response {
        val path = req.path.substringBefore('?')
        fun json(body: String) = TestServer.Response(200, mapOf("Content-Type" to "application/json"), body.toByteArray())
        return when {
            path == "/v2/search" -> json(
                """{"hits":[{"project_id":"MODMODMD","slug":"demomod","title":"Mod Demo","description":"Una mod di prova","author":"tester",
                   "downloads":42,"follows":1,"categories":["fabric"],"versions":["1.20.1"],"project_type":"mod"}],"offset":0,"limit":20,"total_hits":1}"""
            )
            path == "/v2/project/MODMODMD/version" -> json(
                "[" + versionJson("VERMOD01", "MODMODMD", "2.0.0", "/cdn/demomod-2.0.0.jar", "demomod-2.0.0.jar", cdn["/cdn/demomod-2.0.0.jar"]!!,
                    """{"project_id":"LIBLIBLB","dependency_type":"required"},{"project_id":"OPTOPTOP","dependency_type":"optional"}""") + "]"
            )
            path == "/v2/project/LIBLIBLB/version" -> json(
                "[" + versionJson("VERLIB01", "LIBLIBLB", "1.0.0", "/cdn/demolib-1.0.0.jar", "demolib-1.0.0.jar", cdn["/cdn/demolib-1.0.0.jar"]!!, "") + "]"
            )
            path == "/v2/projects" -> json(
                """[{"id":"LIBLIBLB","slug":"demolib","title":"Libreria Demo","description":"d","project_type":"mod","game_versions":["1.20.1"]},
                    {"id":"OPTOPTOP","slug":"opt","title":"Aggiunta Facoltativa","description":"d","project_type":"mod","game_versions":["1.20.1"]}]"""
            )
            cdn.containsKey(path) -> TestServer.Response(200, mapOf("Content-Type" to "application/java-archive"), cdn.getValue(path))
            else -> TestServer.Response(404, mapOf("Content-Type" to "application/json"), """{"error":"not_found"}""".toByteArray())
        }
    }

    private fun fabricJar(id: String, name: String, version: String, deps: Map<String, String>): ByteArray {
        val depends = LinkedHashMap<String, String>()
        depends["fabricloader"] = ">=0.14.0"
        depends["minecraft"] = "~1.20.1"
        depends["java"] = ">=17"
        depends.putAll(deps)
        val dependsJson = depends.entries.joinToString(",") { "\"" + it.key + "\":\"" + it.value + "\"" }
        val descriptor = """{"schemaVersion":1,"id":"$id","version":"$version","name":"$name","environment":"*","depends":{$dependsJson}}"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(entry: String, data: ByteArray) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(data)
                zip.closeEntry()
            }
            put("fabric.mod.json", descriptor.toByteArray())
            put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".toByteArray())
            // Un .class minimo: solo l'intestazione (Java 17 = versione 61); non è eseguibile.
            put("prova/$id/Main.class", byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, 61, 0, 0))
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ attrezzi

    private val noCancel: () -> Unit = {}

    private fun searchRoot(): CatalogProject =
        api.search("demo", ProjectKind.MOD, listOf("fabric"), "1.20.1", SearchSort.RELEVANCE, 0, 20, noCancel).hits.single()

    private fun plan(): CatalogPlan = DependencyPlanner(api).plan(searchRoot(), Fx.FABRIC_1201, InstalledCheck { _, _ -> InstalledState.NO }, noCancel)

    private fun download(item: PlanItem): DownloadRecord {
        val hash = ExpectedHash.parse(item.file.expectedHash()!!)
        assertNotNull("l'impronta del catalogo deve essere leggibile dal motore dei download", hash)
        val added = engine.enqueue(DownloadRequest(item.file.url, item.file.fileName, hash, "Modrinth: " + item.project.displayName))
        assertTrue("accodamento: " + (added as? EnqueueResult.Rejected)?.message, added is EnqueueResult.Added)
        val id = (added as EnqueueResult.Added).record.id
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            val r = engine.record(id)!!
            if (r.state.finished) return r
            Thread.sleep(15)
        }
        throw AssertionError("download non terminato: " + engine.record(id))
    }

    // ------------------------------------------------------------------ prove

    @Test fun planListsTheModThenItsRequiredLibraryAndMentionsTheOptionalOne() {
        val p = plan()
        assertEquals(listOf("MODMODMD", "LIBLIBLB"), p.items.map { it.project.id })
        assertEquals("Libreria Demo", p.items[1].project.displayName)
        assertEquals("Mod Demo", p.items[1].requiredBy)
        assertEquals(listOf("Aggiunta Facoltativa"), p.optional.map { it.title })
        assertEquals((modJar.size + libJar.size).toLong(), p.totalBytes)
        assertTrue(p.problems.isEmpty() && !p.blocked)
    }

    @Test fun fromTheCatalogToTheInstalledProfile() {
        val p = plan()
        val records = p.items.map { download(it) }
        for ((item, record) in p.items.zip(records)) {
            assertEquals(record.error, DownloadState.COMPLETED, record.state)
            assertEquals(item.file.fileName, record.fileName)
            assertEquals("Modrinth: " + item.project.displayName, record.note)
            assertEquals(Fx.sha512Hex(engine.file(record).readBytes()), item.file.sha512)
        }
        assertArrayEquals(modJar, engine.file(records[0]).readBytes())
        assertArrayEquals(libJar, engine.file(records[1]).readBytes())

        // Ora, come fa l'app: lettura del file e controllo sul profilo.
        var n = 0
        val mm = ModManager(ProfileRepository(MemoryDocumentStore()), File(base, "profiles"), newId = { "id" + (++n) })
        val profile = (mm.createProfile(ProfileDraft("Fabric 1.20.1", "1.20.1", ModLoader.FABRIC, null)) as Outcome.Ok).value
        val analyzer = JarAnalyzer(tempDir = base)
        val modReport = analyzer.analyze(engine.file(records[0]), records[0].fileName)
        val libReport = analyzer.analyze(engine.file(records[1]), records[1].fileName)

        // La mod da sola manca della libreria: il MOD MANAGER lo dice e chiede conferma…
        val before = mm.plan(profile.id, modReport)!!
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, before.decision)
        assertTrue(before.has(PlanCode.DEP_MISSING))
        assertEquals(listOf("demolib"), before.missing.map { it.id })
        // …la libreria è pronta; dopo averla installata anche la mod lo è.
        assertEquals(PlanDecision.READY, mm.plan(profile.id, libReport)!!.decision)
        assertTrue(mm.install(profile.id, engine.file(records[1]), libReport, false, origin = "Modrinth", sourceLabel = "Modrinth") is InstallResult.Installed)
        assertEquals(PlanDecision.READY, mm.plan(profile.id, modReport)!!.decision)
        assertTrue(mm.install(profile.id, engine.file(records[0]), modReport, false, origin = "Modrinth", sourceLabel = "Modrinth") is InstallResult.Installed)

        assertEquals(setOf("demomod-2.0.0.jar", "demolib-1.0.0.jar"), mm.items(profile.id).map { it.fileName }.toSet())
        val check = mm.check(profile.id)!!.first
        assertTrue("il profilo deve risultare in ordine: " + check.issues, check.isClean)
    }

    @Test fun aFileThatDoesNotMatchThePublishedHashIsRejectedAndNotKept() {
        val p = plan()
        // Il CDN serve byte diversi da quelli descritti dal catalogo (file alterato o corrotto).
        cdn["/cdn/demolib-1.0.0.jar"] = libJar.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x55).toByte() }
        val record = download(p.items[1])
        assertEquals(DownloadState.FAILED, record.state)
        assertNotNull(record.error)
        assertFalse("il file alterato non deve restare sul telefono", engine.file(record).exists())
        // L'altro file, intatto, si scarica normalmente.
        assertEquals(DownloadState.COMPLETED, download(p.items[0]).state)
    }

    @Test fun aServiceThatOnlyGivesSha1IsStillVerified() {
        hashKind = "sha1"
        val p = plan()
        assertTrue(p.items.all { it.file.expectedHash()!!.startsWith("sha1:") })
        assertEquals(DownloadState.COMPLETED, download(p.items[1]).state)
        // E un SHA-1 sbagliato è respinto come qualsiasi altra impronta.
        cdn["/cdn/demomod-2.0.0.jar"] = fabricJar("demomod", "Mod Demo", "2.0.1", emptyMap())
        assertEquals(DownloadState.FAILED, download(p.items[0]).state)
    }

    @Test fun anAddressOutsideTheAllowedSitesIsRefusedByTheDownloadEngine() {
        val p = plan()
        val evil = p.items[0].file.copy(url = "https://evil.example/mod.jar")
        val result = engine.enqueue(DownloadRequest(evil.url, evil.fileName, ExpectedHash.parse(evil.expectedHash()!!)))
        assertTrue(result is EnqueueResult.Rejected)
        assertEquals("evil.example", (result as EnqueueResult.Rejected).hostToApprove)
        assertNull("nulla è stato scaricato", engine.records().firstOrNull { it.url.contains("evil") })
    }

    @Test fun ifTheServiceHasNoVersionForTheProfileNothingIsOffered() {
        val root = searchRoot()
        val quilt1201 = PlanTarget(ProjectKind.MOD, listOf("forge"), "1.20.1")
        // Il finto servizio ignora i filtri (come farebbe uno sbagliato): la scelta locale li ricontrolla comunque.
        val p = DependencyPlanner(api).plan(root, quilt1201, InstalledCheck { _, _ -> InstalledState.NO }, noCancel)
        assertTrue(p.blocked)
        assertTrue(p.items.isEmpty())
    }
}
