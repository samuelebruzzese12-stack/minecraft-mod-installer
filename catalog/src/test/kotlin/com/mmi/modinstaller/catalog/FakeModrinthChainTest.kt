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
import com.mmi.modinstaller.mods.ItemCategory
import com.mmi.modinstaller.mods.ModManager
import com.mmi.modinstaller.mods.Outcome
import com.mmi.modinstaller.mods.PlanDecision
import com.mmi.modinstaller.mods.ProfileDraft
import com.mmi.modinstaller.mods.ProfileRepository
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URI

/**
 * Prova il finto Modrinth del server di prova (`ci/dl_server.py --modrinth`) con il catalogo, il motore dei download,
 * l'analizzatore e il gestore dei profili VERI. Sono gli stessi dati su cui la prova su emulatore costruisce le sue attese
 * (quanti risultati, quali file, quali dipendenze): se qui tornano, un'eventuale differenza sull'emulatore sta
 * nell'interfaccia e non nei dati di prova.
 *
 * Si esegue solo se MMI_FAKE_MODRINTH indica l'indirizzo dell'API (es. http://127.0.0.1:8765/modrinth/v2) e MMI_SAMPLES la
 * cartella dei file di prova; altrimenti è saltato:
 *   python3 ci/make_samples.py /tmp/samples
 *   python3 -I ci/dl_server.py --port 8765 --payload /tmp/samples/Texture_Verde.mcpack --modrinth /tmp/samples --out /tmp/out &
 *   MMI_SAMPLES=/tmp/samples MMI_FAKE_MODRINTH=http://127.0.0.1:8765/modrinth/v2 gradle :catalog:test
 */
class FakeModrinthChainTest {

    private lateinit var samples: File
    private lateinit var base: File
    private lateinit var api: ModrinthApi
    private lateinit var engine: DownloadEngine
    private val noCancel: () -> Unit = {}
    private val fabric = PlanTarget(ProjectKind.MOD, listOf("fabric"), "1.20.1")
    private val nothingInstalled = InstalledCheck { _, _ -> InstalledState.NO }

    @Before fun setUp() {
        val url = System.getenv("MMI_FAKE_MODRINTH")
        val dir = System.getenv("MMI_SAMPLES")?.let { File(it) }
        assumeTrue("MMI_FAKE_MODRINTH o MMI_SAMPLES non impostate: prova saltata", !url.isNullOrBlank() && dir != null && dir.isDirectory)
        samples = dir!!
        base = TestServer.tmpDir()
        val uri = URI(url!!)
        val hostPort = uri.host + ":" + uri.port
        api = ModrinthApi(
            UrlConnectionHttp("MinecraftModInstaller/prova", insecureHosts = setOf(hostPort), connectTimeoutMs = 3_000, readTimeoutMs = 8_000),
            url.trimEnd('/')
        )
        val options = DownloadOptions(
            maxParallel = 2, connectTimeoutMs = 3_000, readTimeoutMs = 8_000, maxRedirects = 3,
            retryDelaysMs = longArrayOf(30, 30, 30), reserveBytes = 0, freeSpace = { Long.MAX_VALUE }
        )
        engine = DownloadEngine(MemoryDocumentStore(), File(base, "downloads"), UrlPolicy(insecureHosts = setOf(hostPort)), options)
    }

    @After fun tearDown() {
        if (::engine.isInitialized) engine.shutdown()
        if (::base.isInitialized) TestServer.deleteTree(base)
    }

    private fun sample(name: String): File = File(samples, name).also { assertTrue("manca il file di prova $name", it.isFile) }

    private fun search(
        text: String,
        kind: ProjectKind = ProjectKind.MOD,
        loaders: List<String> = listOf("fabric"),
        game: String? = "1.20.1",
        sort: SearchSort = SearchSort.RELEVANCE
    ): SearchPage = api.search(text, kind, loaders, game, sort, 0, 20, noCancel)

    private fun root(slug: String, kind: ProjectKind = ProjectKind.MOD): CatalogProject = search(slug, kind).hits.single()

    private fun plan(slug: String, installed: InstalledCheck = nothingInstalled): CatalogPlan =
        DependencyPlanner(api).plan(root(slug), fabric, installed, noCancel)

    private fun download(item: PlanItem): DownloadRecord {
        val hash = ExpectedHash.parse(item.file.expectedHash()!!)
        assertNotNull(hash)
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

    private fun profile(): Pair<ModManager, String> {
        var n = 0
        val mm = ModManager(ProfileRepository(MemoryDocumentStore()), File(base, "profiles"), newId = { "id" + (++n) })
        val created = mm.createProfile(ProfileDraft("Fabric 1.20.1", "1.20.1", ModLoader.FABRIC, null)) as Outcome.Ok
        return mm to created.value.id
    }

    // ------------------------------------------------------------------ la ricerca

    @Test fun searchFiltersByLoaderAndMinecraftVersionLikeTheRealService() {
        val forProfile = search("")
        assertEquals(6, forProfile.total)
        assertEquals(
            setOf("Mod dal Catalogo", "Libreria dal Catalogo", "Extra Opzionale", "Mod con Impronta Sbagliata", "Mod con Libreria", "Libreria Esempio"),
            forProfile.hits.map { it.displayName }.toSet()
        )
        assertFalse(forProfile.hits.any { it.displayName == "Mod Solo Forge" })

        // Senza filtri compare anche la mod che esiste solo per Forge.
        val everything = search("", loaders = emptyList(), game = null)
        assertEquals(7, everything.total)
        assertTrue(everything.hits.any { it.displayName == "Mod Solo Forge" })

        // Un profilo Forge vede solo la mod per Forge.
        assertEquals(listOf("Mod Solo Forge"), search("", loaders = listOf("forge")).hits.map { it.displayName })
    }

    @Test fun searchTextSortAndKindWork() {
        assertEquals(listOf("Mod dal Catalogo"), search("mod-dal-catalogo").hits.map { it.displayName })
        assertEquals(listOf("Mod con Impronta Sbagliata"), search("impronta-sbagliata").hits.map { it.displayName })
        assertEquals("Mod dal Catalogo", search("", sort = SearchSort.DOWNLOADS).hits.first().displayName)
        assertEquals(2_500_000L, search("mod-dal-catalogo").hits.single().downloads)
        // Un resource pack non dipende dal caricatore.
        val packs = search("", kind = ProjectKind.RESOURCE_PACK)
        assertEquals(listOf("Texture dal Catalogo"), packs.hits.map { it.displayName })
        assertEquals(ProjectKind.RESOURCE_PACK, packs.hits.single().kind)
    }

    @Test fun serviceErrorsBecomeClearMessages() {
        try {
            search("errore500")
            fail("doveva fallire")
        } catch (e: CatalogException) {
            assertEquals(CatalogError.SERVER, e.kind)
            assertTrue(e.message, e.message!!.contains("problema"))
        }
        try {
            search("limite429")
            fail("doveva fallire")
        } catch (e: CatalogException) {
            assertEquals(CatalogError.RATE_LIMITED, e.kind)
            assertEquals(7, e.retryAfterSeconds)
            assertTrue(e.message, e.message!!.contains("7"))
        }
    }

    // ------------------------------------------------------------------ il piano

    @Test fun planPrefersTheStableVersionAndListsTheDependencies() {
        val p = plan("mod-dal-catalogo")
        // La beta è la più recente ma si sceglie la stabile; la 1.0.0 è per Minecraft 1.19.2.
        assertEquals(listOf("Mod dal Catalogo", "Libreria dal Catalogo"), p.items.map { it.project.displayName })
        assertEquals(listOf("1.5.0", "1.2.0"), p.items.map { it.version.versionNumber })
        assertEquals(listOf("Mod_Catalogo.jar", "Libreria_Catalogo.jar"), p.items.map { it.file.fileName })
        assertEquals(listOf(null, "Mod dal Catalogo"), p.items.map { it.requiredBy })
        assertEquals(listOf("Extra Opzionale"), p.optional.map { it.title })
        assertEquals(listOf("Mod Solo Forge"), p.incompatible.map { it.title })
        assertTrue(p.problems.toString(), p.problems.isEmpty())
        assertEquals(sample("Mod_Catalogo.jar").length() + sample("Libreria_Catalogo.jar").length(), p.totalBytes)
    }

    @Test fun modsAlreadyInTheProfileAreNotDownloadedAgain() {
        // Come fa l'app: stesso nome di file oppure stesso SHA-1 di un file già nel profilo.
        val sha1 = listOf("Mod_Con_Dipendenza.jar", "Libreria_Esempio.jar").map { Fx.sha1Hex(sample(it).readBytes()) }.toSet()
        val installed = InstalledCheck { _, file ->
            if (file.sha1 != null && file.sha1 in sha1) InstalledState.SAME_FILE else InstalledState.NO
        }
        val p = plan("mod-con-libreria", installed)
        assertTrue(p.items.isEmpty())
        assertEquals(listOf("Mod con Libreria", "Libreria Esempio"), p.alreadyInstalled.map { it.title })
        assertFalse(p.blocked)
    }

    @Test fun noVersionForTheProfileMeansNothingIsOffered() {
        val forge = search("mod-solo-forge", loaders = emptyList(), game = null).hits.single()
        val p = DependencyPlanner(api).plan(forge, fabric, nothingInstalled, noCancel)
        assertTrue(p.blocked)
        assertTrue(p.items.isEmpty())
    }

    // ------------------------------------------------------------------ dal catalogo al profilo

    @Test fun fromTheFakeCatalogToTheInstalledProfile() {
        val p = plan("mod-dal-catalogo")
        val records = p.items.map { download(it) }
        for ((item, record) in p.items.zip(records)) {
            assertEquals(record.error, DownloadState.COMPLETED, record.state)
            assertArrayEquals(sample(item.file.fileName).readBytes(), engine.file(record).readBytes())
        }
        val (mm, pid) = profile()
        val analyzer = JarAnalyzer(tempDir = base)
        val modReport = analyzer.analyze(engine.file(records[0]), records[0].fileName)
        val libReport = analyzer.analyze(engine.file(records[1]), records[1].fileName)
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, mm.plan(pid, modReport)!!.decision)
        assertEquals(PlanDecision.READY, mm.plan(pid, libReport)!!.decision)
        assertTrue(mm.install(pid, engine.file(records[1]), libReport, false, origin = "Modrinth", sourceLabel = "modrinth") is InstallResult.Installed)
        assertEquals(PlanDecision.READY, mm.plan(pid, modReport)!!.decision)
        assertTrue(mm.install(pid, engine.file(records[0]), modReport, false, origin = "Modrinth", sourceLabel = "modrinth") is InstallResult.Installed)
        assertTrue(mm.check(pid)!!.first.isClean)
        assertEquals(setOf("Mod_Catalogo.jar", "Libreria_Catalogo.jar"), mm.items(pid).map { it.fileName }.toSet())
    }

    @Test fun aResourcePackGoesToItsOwnFolder() {
        val p = DependencyPlanner(api).plan(
            root("texture-dal-catalogo", ProjectKind.RESOURCE_PACK),
            PlanTarget(ProjectKind.RESOURCE_PACK, listOf("fabric"), "1.20.1"), nothingInstalled, noCancel
        )
        assertEquals(listOf("Texture_Catalogo.zip"), p.items.map { it.file.fileName })
        val record = download(p.items.single())
        assertEquals(record.error, DownloadState.COMPLETED, record.state)
        val (mm, pid) = profile()
        val report = JarAnalyzer(tempDir = base).analyze(engine.file(record), record.fileName)
        val decision = mm.plan(pid, report)!!
        assertEquals(decision.issues.toString(), PlanDecision.READY, decision.decision)
        assertEquals(ItemCategory.RESOURCE_PACK, decision.category)
        assertTrue(mm.install(pid, engine.file(record), report, false, origin = "Modrinth", sourceLabel = "modrinth") is InstallResult.Installed)
    }

    @Test fun aFileThatDoesNotMatchThePublishedHashIsRejectedAndNotKept() {
        val p = plan("impronta-sbagliata")
        assertEquals(listOf("Mod_Impronta_Sbagliata.jar"), p.items.map { it.file.fileName })
        val record = download(p.items.single())
        assertEquals(DownloadState.FAILED, record.state)
        assertNotNull(record.error)
        assertFalse("il file alterato non deve restare sul telefono", engine.file(record).exists())
    }
}
