package com.mmi.modinstaller.modpack

import com.mmi.modinstaller.core.MemoryDocumentStore
import com.mmi.modinstaller.download.DownloadEngine
import com.mmi.modinstaller.download.DownloadOptions
import com.mmi.modinstaller.download.UrlPolicy
import com.mmi.modinstaller.jar.JarAnalyzer
import com.mmi.modinstaller.jar.ModLoader
import com.mmi.modinstaller.mods.ModManager
import com.mmi.modinstaller.mods.ProfileRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * I modpack di prova della prova su emulatore (ci/make_samples.py) con i motori veri e il finto Modrinth di ci/dl_server.py:
 * controlla PRIMA che le attese dello script (ci/modpack_phase.sh) siano quelle che l'installatore produce davvero.
 *
 * Si esegue solo se MMI_SAMPLES indica la cartella dei file di prova e MMI_FAKE_SERVER l'indirizzo del server (senza percorso):
 *   python3 ci/make_samples.py /tmp/samples
 *   python3 -I ci/dl_server.py --port 8765 --payload /tmp/samples/Texture_Verde.mcpack --modrinth /tmp/samples --out /tmp/out &
 *   MMI_SAMPLES=/tmp/samples MMI_FAKE_SERVER=http://127.0.0.1:8765 gradle :modpack-engine:test
 * Il server va riavviato per ogni esecuzione: «Libreria_Catalogo.jar» risponde 404 solo alla prima richiesta.
 */
class EmulatorModpackSamplesTest {

    private lateinit var samples: File
    private lateinit var serverBase: String
    private lateinit var base: File
    private lateinit var engine: DownloadEngine
    private lateinit var manager: ModManager
    private lateinit var installer: ModpackInstaller

    @Before fun setUp() {
        val dir = System.getenv("MMI_SAMPLES")?.let { File(it) }
        val server = System.getenv("MMI_FAKE_SERVER")?.trimEnd('/')
        assumeTrue("MMI_SAMPLES o MMI_FAKE_SERVER non impostate: prova saltata", dir != null && dir.isDirectory && !server.isNullOrBlank())
        samples = dir!!
        serverBase = server!!
        base = Fx.tempDir()
        val options = DownloadOptions(
            maxParallel = 2, connectTimeoutMs = 3_000, readTimeoutMs = 5_000, maxRedirects = 3,
            retryDelaysMs = longArrayOf(30, 30), reserveBytes = 0, freeSpace = { Long.MAX_VALUE }
        )
        // Come nell'app: i siti consentiti dall'utente stanno nell'archivio e il controllo degli indirizzi li rilegge a ogni richiesta.
        val store = MemoryDocumentStore()
        engine = DownloadEngine(
            store, File(base, "downloads"),
            UrlPolicy(userHosts = { DownloadEngine.approvedHosts(store) }, insecureHosts = setOf(serverBase.substringAfter("://"))), options
        )
        var n = 0
        manager = ModManager(ProfileRepository(MemoryDocumentStore()), File(base, "profiles"), newId = { "id" + (++n) })
        installer = ModpackInstaller(manager, engine, { f, name, cancel -> JarAnalyzer(tempDir = base).analyze(f, name, cancel) }, pollMs = 20)
    }

    @After fun tearDown() {
        if (::engine.isInitialized) engine.shutdown()
        if (::base.isInitialized) TestServer.deleteTree(base)
    }

    // ------------------------------------------------------------------ attrezzi

    private fun sample(name: String) = File(samples, name)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String = sha256(file.readBytes())

    /** Il modpack letto dal file di prova, con gli indirizzi del finto CDN spostati sul server di questa prova. */
    private fun read(name: String): Pack {
        val r = ModrinthPackReader.read(sample(name))
        if (r is PackReadResult.Failed) fail(name + ": " + r.message + " " + r.detail)
        val pack = (r as PackReadResult.Ok).pack
        return Pack(
            pack.name, pack.versionId, pack.summary, pack.minecraft, pack.loader, pack.loaderVersion,
            pack.files.map { f -> f.copy(urls = f.urls.map { it.replace("http://10.0.2.2:8765", serverBase) }) },
            pack.overrides, pack.problems
        )
    }

    private fun install(name: String, resume: String? = null, cancel: () -> Unit = {}, progress: (InstallProgress) -> Unit = {}): ModpackResult {
        val pack = read(name)
        return installer.install(InstallRequest(pack, sample(name), pack.name, resume), cancel, progress)
    }

    private fun profileFile(result: ModpackResult, path: String): File = File(manager.profileDir(result.profile!!.id), path)

    private fun assertPlaced(result: ModpackResult, path: String, source: String) {
        val f = profileFile(result, path)
        assertTrue("manca $path nel profilo", f.isFile)
        assertEquals("$path non è identico a $source", sha256(sample(source)), sha256(f))
    }

    // ------------------------------------------------------------------ i modpack di prova

    @Test fun theCompletePackIsReadAsTheScriptExpects() {
        val pack = read("Modpack_Prova.mrpack")
        assertEquals("Modpack di Prova", pack.name)
        assertEquals("1.0.0", pack.versionId)
        assertEquals("1.20.1", pack.minecraft)
        assertEquals(ModLoader.FABRIC, pack.loader)
        assertEquals("0.15.11", pack.loaderVersion)
        assertEquals(5, pack.files.size)
        assertEquals(OverrideCounts(common = 1, client = 1, server = 1), pack.overrides)
        assertTrue(pack.problems.isEmpty())
        val plan = ModpackPlanner.plan(pack, engine.checker)
        assertTrue(plan.canInstall)
        assertEquals(4, plan.toDownload.size)
        assertEquals(1, plan.skipped.size)
        assertEquals("Solo_Server.jar", plan.skipped.first().file.fileName)
        assertTrue(plan.blocked.isEmpty())
        assertEquals(0, plan.unknownSizes)
        assertTrue(plan.complete)
    }

    @Test fun theCompletePackInstallsEverythingAndLeavesTheServerOnlyFileOut() {
        val result = install("Modpack_Prova.mrpack")
        assertEquals(null, result.fatal)
        assertTrue(result.failures.joinToString { it.path + ": " + it.reason }, result.failures.isEmpty())
        assertTrue(result.complete)
        assertEquals(4, result.totalFiles)
        assertEquals(4, result.placedFiles)
        assertEquals(listOf("mods/Solo_Server.jar"), result.skippedByEnv)
        assertEquals(2, result.overridesApplied)
        assertEquals(3, result.registered)
        assertTrue(result.createdProfile)
        assertEquals("Modpack di Prova", result.profile!!.name)

        assertPlaced(result, "mods/Mod_Fabric_Esempio.jar", "Mod_Fabric_Esempio.jar")
        assertPlaced(result, "mods/Libreria_Esempio.jar", "Libreria_Esempio.jar")
        assertPlaced(result, "resourcepacks/Texture_Java.zip", "Texture_Java.zip")
        assertEquals("{\"modpack\": \"prova\", \"valore\": 42}\n", profileFile(result, "config/prova-modpack.json").readText())
        assertEquals("impostazione comune a tutti\n", profileFile(result, "config/impostazioni-comuni.txt").readText())
        assertEquals("guiScale:2\n", profileFile(result, "options.txt").readText())
        assertFalse("la mod solo per server non va scaricata", profileFile(result, "mods/Solo_Server.jar").exists())
        assertFalse("le impostazioni del server non vanno applicate", profileFile(result, "server.properties").exists())
        assertEquals(emptyList<String>(), File(base, "downloads").list()?.toList().orEmpty())
        assertEquals(3, manager.items(result.profile!!.id).size)

        // Quello che l'app mostrerà in «VERIFICA PROFILO»: nessun problema, 3 file attivi; e all'apertura del MOD MANAGER nulla
        // risulta mancante o cambiato (l'elenco e il disco coincidono).
        val (check, rec) = manager.check(result.profile!!.id)!!
        assertTrue(check.issues.joinToString { it.toString() }, check.issues.isEmpty())
        assertEquals(3, check.checkedItems)
        assertTrue(rec.missing.isEmpty() && rec.recovered.isEmpty() && rec.changedOutside.isEmpty())
        assertEquals(
            setOf("config/impostazioni-comuni.txt", "config/prova-modpack.json", "options.txt"),
            manager.extraFiles(result.profile!!.id).toSet()
        )
    }

    @Test fun theIncompletePackReportsWhatIsMissingAndTheResumeBringsOnlyWhatCanArrive() {
        val first = install("Modpack_Incompleto.mrpack")
        assertEquals(null, first.fatal)
        assertFalse(first.complete)
        assertEquals(3, first.totalFiles)
        assertEquals(1, first.placedFiles)
        assertEquals(2, first.failures.size)
        assertEquals(
            setOf("mods/Libreria_Catalogo.jar", "mods/Mod_Impronta_Sbagliata.jar"),
            first.failures.map { it.path }.toSet()
        )
        assertPlaced(first, "mods/Mod_Catalogo.jar", "Mod_Catalogo.jar")
        assertFalse(profileFile(first, "mods/Libreria_Catalogo.jar").exists())
        assertFalse("un file con l'impronta sbagliata non deve restare nel profilo", profileFile(first, "mods/Mod_Impronta_Sbagliata.jar").exists())
        assertEquals(emptyList<String>(), File(base, "downloads").list()?.toList().orEmpty())

        val plan = ModpackPlanner.plan(read("Modpack_Incompleto.mrpack"), engine.checker, manager.profileDir(first.profile!!.id))
        assertEquals(1, plan.present.size)
        assertEquals(2, plan.toDownload.size)

        val second = install("Modpack_Incompleto.mrpack", resume = first.profile!!.id)
        assertEquals(null, second.fatal)
        assertFalse(second.createdProfile)
        assertFalse(second.complete)
        assertEquals(3, second.totalFiles)
        assertEquals(2, second.placedFiles)
        assertEquals(listOf("mods/Mod_Impronta_Sbagliata.jar"), second.failures.map { it.path })
        assertPlaced(second, "mods/Mod_Catalogo.jar", "Mod_Catalogo.jar")
        assertPlaced(second, "mods/Libreria_Catalogo.jar", "Libreria_Catalogo.jar")
        assertFalse(profileFile(second, "mods/Mod_Impronta_Sbagliata.jar").exists())
        assertEquals(2, manager.items(second.profile!!.id).size)
    }

    @Test fun thePackFromAnotherSiteNeedsTheSiteApprovedFirst() {
        val pack = read("Modpack_Altro_Sito.mrpack")
        val before = ModpackPlanner.plan(pack, engine.checker)
        assertTrue(before.canInstall)
        assertEquals(1, before.blocked.size)
        assertEquals(listOf("cdn.modpack-test.invalid"), before.hostsToApprove)
        assertTrue(before.toDownload.isEmpty())
        assertFalse(before.complete)

        assertTrue(engine.approveHost("cdn.modpack-test.invalid"))
        val after = ModpackPlanner.plan(pack, engine.checker)
        assertTrue(after.blocked.isEmpty())
        assertEquals(1, after.toDownload.size)
        assertTrue(after.hostsToApprove.isEmpty())
    }

    @Test fun theSlowPackIsCancelledAndLeavesNothingBehind() {
        val pack = read("Modpack_Lento.mrpack")
        val plan = ModpackPlanner.plan(pack, engine.checker)
        assertTrue(plan.canInstall)
        assertEquals(2, plan.toDownload.size)
        assertTrue(plan.blocked.isEmpty())

        var cancelled = false
        val started = System.currentTimeMillis()
        try {
            install("Modpack_Lento.mrpack", cancel = {
                if (cancelled) throw CancellationException("annullato dalla prova")
            }, progress = { p ->
                // La mod veloce arriva subito; il file «incagliato» tiene l'installazione in corso: si annulla quando la prima è arrivata.
                if (p.phase == Phase.DOWNLOADING && p.doneFiles >= 1) cancelled = true
            })
            fail("l'installazione doveva essere annullata")
        } catch (e: CancellationException) {
            // atteso
        }
        assertTrue("l'annullamento deve essere rapido", System.currentTimeMillis() - started < 30_000)
        assertTrue("l'annullamento deve cancellare il profilo creato", manager.profiles().isEmpty())
        assertEquals(emptyList<String>(), File(base, "downloads").list()?.toList().orEmpty())
        assertTrue(engine.records().isEmpty())
    }
}
