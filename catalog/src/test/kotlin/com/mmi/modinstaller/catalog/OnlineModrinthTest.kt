package com.mmi.modinstaller.catalog

import com.mmi.modinstaller.core.MemoryDocumentStore
import com.mmi.modinstaller.download.DownloadEngine
import com.mmi.modinstaller.download.DownloadOptions
import com.mmi.modinstaller.download.DownloadRequest
import com.mmi.modinstaller.download.DownloadState
import com.mmi.modinstaller.download.EnqueueResult
import com.mmi.modinstaller.download.ExpectedHash
import com.mmi.modinstaller.download.UrlPolicy
import com.mmi.modinstaller.jar.ContentKind
import com.mmi.modinstaller.jar.JarAnalyzer
import com.mmi.modinstaller.jar.ModLoader
import com.mmi.modinstaller.mods.InstallResult
import com.mmi.modinstaller.mods.ModManager
import com.mmi.modinstaller.mods.Outcome
import com.mmi.modinstaller.mods.PlanDecision
import com.mmi.modinstaller.mods.ProfileDraft
import com.mmi.modinstaller.mods.ProfileRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Prova COL SERVIZIO VERO (api.modrinth.com e cdn.modrinth.com): serve a controllare che le richieste, le risposte e le
 * impronte reali corrispondano a ciò che il codice si aspetta. Si esegue solo se la variabile d'ambiente MMI_ONLINE=1
 * (in CI, in un passaggio a parte che non blocca la compilazione se la rete o il servizio non rispondono); altrimenti è
 * saltata. Scrive un resoconto in MMI_ONLINE_REPORT (o build/online-report.txt).
 *
 * Verifica solo proprietà che non dipendono da quali mod siano di moda oggi: i filtri sono rispettati, i file hanno
 * impronte e indirizzi sul CDN, il download con verifica riesce, il .jar scaricato si legge e si installa in un profilo.
 */
class OnlineModrinthTest {

    private val report = StringBuilder()
    private lateinit var base: File
    private lateinit var api: ModrinthApi
    private lateinit var engine: DownloadEngine
    private val noCancel: () -> Unit = {}

    @Before fun setUp() {
        assumeTrue("MMI_ONLINE non impostata: prova online saltata", System.getenv("MMI_ONLINE") == "1")
        base = Files.createTempDirectory("mmi-online").toFile()
        api = ModrinthApi(UrlConnectionHttp("MinecraftModInstaller/ci-test (prova automatica)"))
        engine = DownloadEngine(
            MemoryDocumentStore(), File(base, "downloads"), UrlPolicy(),
            DownloadOptions(userAgent = "MinecraftModInstaller/ci-test (prova automatica)", reserveBytes = 0, freeSpace = { Long.MAX_VALUE })
        )
    }

    @After fun tearDown() {
        if (::engine.isInitialized) engine.shutdown()
        if (::base.isInitialized) base.deleteRecursively()
        val target = File(System.getenv("MMI_ONLINE_REPORT") ?: "build/online-report.txt")
        target.parentFile?.mkdirs()
        target.writeText(report.toString())
    }

    private fun say(text: String) {
        report.append(text).append('\n')
    }

    @Test fun searchFiltersAreHonoredByTheRealService() {
        val page = api.search("fabric", ProjectKind.MOD, listOf("fabric"), "1.20.1", SearchSort.DOWNLOADS, 0, 20, noCancel)
        say("Ricerca «fabric» (mod, fabric, 1.20.1): ${page.total} risultati, ${page.hits.size} letti")
        assertTrue("la ricerca deve dare risultati", page.hits.isNotEmpty())
        for (hit in page.hits) {
            assertEquals(hit.title, ProjectKind.MOD, hit.kind)
            assertTrue("«${hit.title}» deve essere per 1.20.1: ${hit.gameVersions}", "1.20.1" in hit.gameVersions)
            assertTrue("«${hit.title}» deve essere per fabric: ${hit.categories}", "fabric" in hit.categories)
            assertTrue(hit.id.isNotBlank() && hit.displayName.isNotBlank())
        }
        say("Primi risultati: " + page.hits.take(5).joinToString { it.displayName + " (" + it.downloads + ")" })
        // Ordinati per download: non crescenti.
        assertTrue(page.hits.zipWithNext().all { (a, b) -> a.downloads >= b.downloads })

        val packs = api.search("", ProjectKind.RESOURCE_PACK, listOf("fabric"), "1.20.1", SearchSort.DOWNLOADS, 0, 5, noCancel)
        say("Resource pack per 1.20.1: ${packs.total}")
        assertTrue(packs.hits.isNotEmpty())
        assertTrue(packs.hits.all { it.kind == ProjectKind.RESOURCE_PACK })
        val shaders = api.search("", ProjectKind.SHADER, emptyList(), null, SearchSort.NEWEST, 0, 5, noCancel)
        say("Shader: ${shaders.total}")
        assertTrue(shaders.hits.all { it.kind == ProjectKind.SHADER })
    }

    @Test fun versionListsRespectLoaderAndGameVersionFilters() {
        val hit = api.search("", ProjectKind.MOD, listOf("fabric"), "1.20.1", SearchSort.DOWNLOADS, 0, 3, noCancel).hits.first()
        val versions = api.projectVersions(hit.id, listOf("fabric"), listOf("1.20.1"), noCancel)
        say("«${hit.displayName}»: ${versions.size} versioni per fabric 1.20.1")
        assertTrue(versions.isNotEmpty())
        for (v in versions) {
            assertTrue(v.loaders.toString(), v.loaders.any { it.equals("fabric", true) })
            assertTrue(v.gameVersions.toString(), "1.20.1" in v.gameVersions)
            assertEquals(hit.id, v.projectId)
        }
        val pick = VersionPicker.best(versions, Fx.FABRIC_1201)
        assertNotNull(pick)
        val file = pick!!.file
        say("Scelta: ${pick.version.versionNumber} (${pick.version.channel.label}) → ${file.fileName}, ${file.size} byte, ${file.expectedHash()?.take(14)}…")
        assertTrue(file.url, file.url.startsWith("https://cdn.modrinth.com/"))
        assertNotNull(file.expectedHash())
        assertTrue(file.size > 0)

        // Le stesse versioni, chieste per id in blocco, devono coincidere con quelle dell'elenco.
        val byId = api.versions(versions.take(3).map { it.id }, noCancel)
        assertEquals(versions.take(3).map { it.id }.toSet(), byId.map { it.id }.toSet())
        val titles = api.projects(listOf(hit.id), noCancel)
        assertEquals(hit.id, titles.single().id)
        assertEquals(hit.title, titles.single().title)
    }

    @Test fun realPlansHaveValidFilesAndSomeResolveDependencies() {
        val hits = api.search("", ProjectKind.MOD, listOf("fabric"), "1.20.1", SearchSort.DOWNLOADS, 0, 12, noCancel).hits
        val planner = DependencyPlanner(api)
        var withDependencies = 0
        for (hit in hits) {
            val p = planner.plan(hit, Fx.FABRIC_1201, InstalledCheck { _, _ -> InstalledState.NO }, noCancel)
            say("Piano «${hit.displayName}»: ${p.items.size} file, ${p.totalBytes} byte, problemi=${p.problems.size}, facoltative=${p.optional.size}, incompatibili=${p.incompatible.size}")
            for (problem in p.problems) say("    ! " + problem.text)
            assertFalse("«${hit.displayName}» deve avere una versione per fabric 1.20.1: " + p.problems.map { it.text }, p.blocked)
            assertTrue(p.items.first().isRoot)
            assertEquals(p.items.size, p.items.map { it.project.id }.toSet().size)
            for (item in p.items) {
                assertNotNull(item.file.fileName, item.file.expectedHash())
                assertTrue(item.file.url, item.file.url.startsWith("https://cdn.modrinth.com/"))
                assertTrue(item.version.loaders.any { it.equals("fabric", true) })
                assertTrue("1.20.1" in item.version.gameVersions)
            }
            if (p.items.size > 1) {
                withDependencies++
                say("    dipendenze: " + p.items.drop(1).joinToString { it.project.displayName + " (da " + it.requiredBy + ")" })
                assertTrue(p.items.drop(1).all { it.project.title.isNotBlank() })
            }
        }
        say("Mod con dipendenze obbligatorie risolte: $withDependencies su ${hits.size}")
        assertTrue("tra le mod più scaricate almeno una deve avere dipendenze", withDependencies >= 1)
    }

    @Test fun aRealModIsDownloadedVerifiedReadAndInstalled() {
        val hits = api.search("", ProjectKind.MOD, listOf("fabric"), "1.20.1", SearchSort.DOWNLOADS, 0, 15, noCancel).hits
        val planner = DependencyPlanner(api)
        // La più piccola tra le mod più scaricate (con le sue dipendenze), per non scaricare troppo.
        val plans = hits.map { it to planner.plan(it, Fx.FABRIC_1201, InstalledCheck { _, _ -> InstalledState.NO }, noCancel) }
            .filter { !it.second.blocked && it.second.totalBytes in 1..6_000_000 }
        assertTrue("serve almeno una mod piccola tra i risultati", plans.isNotEmpty())
        val (hit, plan) = plans.minByOrNull { it.second.totalBytes }!!
        say("Scarico «${hit.displayName}»: " + plan.items.joinToString { it.file.fileName + " (" + it.file.size + ")" })

        val records = plan.items.map { item ->
            val hash = ExpectedHash.parse(item.file.expectedHash()!!)
            assertNotNull(hash)
            val added = engine.enqueue(DownloadRequest(item.file.url, item.file.fileName, hash, "Modrinth: " + item.project.displayName))
            assertTrue((added as? EnqueueResult.Rejected)?.message ?: "", added is EnqueueResult.Added)
            (added as EnqueueResult.Added).record
        }
        val done = records.map { r ->
            val end = System.currentTimeMillis() + 120_000
            var cur = engine.record(r.id)!!
            while (!cur.state.finished && System.currentTimeMillis() < end) {
                Thread.sleep(100)
                cur = engine.record(r.id)!!
            }
            cur
        }
        for (r in done) say("    " + r.fileName + ": " + r.state + (r.error?.let { " — $it" } ?: "") + " sha256=" + r.sha256?.take(12))
        for ((item, r) in plan.items.zip(done)) {
            assertEquals(r.error, DownloadState.COMPLETED, r.state)
            val file = engine.file(r)
            assertEquals("la dimensione deve essere quella dichiarata", item.file.size, file.length())
            assertEquals("l'impronta SHA-512 dei byte scaricati deve essere quella del catalogo", item.file.sha512, Fx.sha512Hex(file.readBytes()))
        }

        // Il file è davvero una mod Fabric e il MOD MANAGER la installa (le dipendenze prima, come fa «INSTALLA I FILE PRONTI»).
        var n = 0
        val mm = ModManager(ProfileRepository(MemoryDocumentStore()), File(base, "profiles"), newId = { "id" + (++n) })
        val profile = (mm.createProfile(ProfileDraft("Fabric 1.20.1", "1.20.1", ModLoader.FABRIC, null)) as Outcome.Ok).value
        val analyzer = JarAnalyzer(tempDir = base)
        val reports = done.map { analyzer.analyze(engine.file(it), it.fileName) }
        for (r in reports) {
            say("    letto: " + r.mods.firstOrNull()?.id + " " + r.mods.firstOrNull()?.version + " loader=" + r.mods.firstOrNull()?.loader + " tipo=" + r.kind)
            assertEquals(ContentKind.MOD, r.kind)
            assertEquals(ModLoader.FABRIC, r.mods.first().loader)
        }
        val pending = done.indices.toMutableList()
        var guard = 0
        while (pending.isNotEmpty() && guard++ < 10) {
            val next = pending.firstOrNull { mm.plan(profile.id, reports[it])!!.decision == PlanDecision.READY } ?: break
            val result = mm.install(profile.id, engine.file(done[next]), reports[next], false, origin = "Modrinth", sourceLabel = "Modrinth")
            assertTrue(result.toString(), result is InstallResult.Installed)
            pending.remove(next)
        }
        say("    installati nel profilo: " + mm.items(profile.id).joinToString { it.fileName } + "; non installabili da soli: " + pending.map { done[it].fileName })
        assertTrue("le mod scaricate devono entrare nel profilo (restano: ${pending.map { done[it].fileName }})", pending.isEmpty())
        assertEquals(plan.items.size, mm.items(profile.id).size)
    }
}
