package com.mmi.modinstaller.modpack

import com.mmi.modinstaller.catalog.ModrinthApi
import com.mmi.modinstaller.catalog.ProjectKind
import com.mmi.modinstaller.catalog.ReleaseChannel
import com.mmi.modinstaller.catalog.SearchSort
import com.mmi.modinstaller.catalog.UrlConnectionHttp
import com.mmi.modinstaller.core.MemoryDocumentStore
import com.mmi.modinstaller.download.DownloadEngine
import com.mmi.modinstaller.download.DownloadOptions
import com.mmi.modinstaller.download.DownloadRequest
import com.mmi.modinstaller.download.DownloadState
import com.mmi.modinstaller.download.EnqueueResult
import com.mmi.modinstaller.download.ExpectedHash
import com.mmi.modinstaller.download.UrlPolicy
import com.mmi.modinstaller.jar.JarAnalyzer
import com.mmi.modinstaller.mods.ModManager
import com.mmi.modinstaller.mods.ProfileRepository
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Prova COL SERVIZIO VERO (api.modrinth.com e cdn.modrinth.com): modpack reali, non inventati. Serve a sapere se il lettore,
 * il pianificatore e l'installatore dei modpack funzionano con i .mrpack che le persone scaricano davvero.
 *
 * Si esegue solo con MMI_ONLINE=1 (in CI, nel passaggio «Prova col servizio vero (Modrinth)», che non blocca la compilazione se la
 * rete o il servizio non rispondono); altrimenti è saltata. Scrive un resoconto in MMI_ONLINE_MODPACK_REPORT (o
 * build/online-modpack-report.txt).
 *
 * Con MMI_ONLINE_FAKE=http://127.0.0.1:PORTA parla invece con il finto Modrinth di ci/dl_server.py (che elenca due modpack di prova):
 * serve solo a provare in locale questo test prima di usarlo contro il servizio vero.
 *
 * Controlla solo proprietà che non dipendono da quali modpack siano di moda oggi: i .mrpack dei modpack Fabric più scaricati si
 * scaricano con l'impronta del servizio, si leggono senza voci scartate e si possono installare; il più piccolo (fino a 60 file e
 * 120 MB) viene installato per intero, con tutti i file al loro posto e l'impronta giusta.
 */
class OnlineModpackTest {

    /** Un modpack vero scaricato da Modrinth e letto con il lettore dell'app. */
    private class RealPack(val title: String, val file: File, val pack: Pack) {
        val clientFiles: Int get() = pack.files.count { it.client != Side.UNSUPPORTED }
        val declaredBytes: Long get() = pack.files.filter { it.client != Side.UNSUPPORTED }.sumOf { it.size.coerceAtLeast(0L) }
    }

    companion object {
        private const val UA = "MinecraftModInstaller/ci-test (prova automatica)"
        private const val MAX_MRPACK_BYTES = 40L * 1024 * 1024
        private const val MAX_INSTALL_FILES = 60
        private const val MAX_INSTALL_BYTES = 120L * 1024 * 1024

        /** Il resoconto di tutte le prove della classe (ogni prova lo riscrive per intero). */
        private val report = StringBuilder()
        private var shared: File? = null
        private var packs: List<RealPack>? = null
        private val unreadable = ArrayList<String>()

        @AfterClass @JvmStatic fun cleanUp() {
            shared?.deleteRecursively()
            shared = null
            packs = null
        }
    }

    private lateinit var base: File
    private lateinit var api: ModrinthApi
    private lateinit var engine: DownloadEngine
    private val noCancel: () -> Unit = {}

    /** Il finto Modrinth per la prova in locale (senza percorso), o null per il servizio vero. */
    private val fake: String? = System.getenv("MMI_ONLINE_FAKE")?.trimEnd('/')?.takeIf { it.isNotBlank() }
    private val fakeHostPort: String? get() = fake?.substringAfter("://")?.substringBefore('/')

    @Before fun setUp() {
        assumeTrue("MMI_ONLINE non impostata: prova online saltata", System.getenv("MMI_ONLINE") == "1")
        base = Files.createTempDirectory("mmi-online-modpack").toFile()
        val local = fakeHostPort
        api = if (local == null) ModrinthApi(UrlConnectionHttp(UA)) else ModrinthApi(UrlConnectionHttp(UA, insecureHosts = setOf(local)), "$fake/modrinth/v2")
        // Come nell'app: i siti consentiti stanno nell'archivio e il controllo degli indirizzi li rilegge.
        val store = MemoryDocumentStore()
        engine = DownloadEngine(
            store, File(base, "downloads"),
            UrlPolicy(userHosts = { DownloadEngine.approvedHosts(store) }, insecureHosts = if (local == null) emptySet() else setOf(local)),
            DownloadOptions(userAgent = UA, reserveBytes = 0, freeSpace = { Long.MAX_VALUE })
        )
    }

    @After fun tearDown() {
        if (::engine.isInitialized) engine.shutdown()
        if (::base.isInitialized) base.deleteRecursively()
        if (System.getenv("MMI_ONLINE") == "1") {
            val target = File(System.getenv("MMI_ONLINE_MODPACK_REPORT") ?: "build/online-modpack-report.txt")
            target.parentFile?.mkdirs()
            target.writeText(report.toString())
        }
    }

    private fun say(text: String) {
        report.append(text).append('\n')
    }

    /** Scarica un file con il motore dei download dell'app (impronta verificata). Null, con il motivo nel resoconto, se non riesce. */
    private fun download(url: String, name: String, hash: ExpectedHash, note: String): File? {
        val added = engine.enqueue(DownloadRequest(url, name, hash, note))
        if (added !is EnqueueResult.Added) {
            say("    $name: rifiutato — " + (added as EnqueueResult.Rejected).message)
            return null
        }
        val end = System.currentTimeMillis() + 180_000
        var cur = engine.record(added.record.id)!!
        while (!cur.state.finished && System.currentTimeMillis() < end) {
            Thread.sleep(100)
            cur = engine.record(added.record.id)!!
        }
        if (cur.state != DownloadState.COMPLETED) {
            say("    $name: " + cur.state + (cur.error?.let { " — $it" } ?: ""))
            return null
        }
        return engine.file(cur)
    }

    /** I modpack Fabric più scaricati di Modrinth: il .mrpack dell'ultima versione stabile, scaricato e letto (una volta per classe). */
    private fun loadPacks(): List<RealPack> {
        packs?.let { return it }
        val dir = shared ?: Files.createTempDirectory("mmi-online-packs").toFile().also { shared = it }
        val hits = api.search("", ProjectKind.MODPACK, listOf("fabric"), null, SearchSort.DOWNLOADS, 0, 12, noCancel).hits
        say("Modpack Fabric più scaricati: " + hits.take(8).joinToString { it.displayName })
        val out = ArrayList<RealPack>()
        for (hit in hits) {
            if (out.size >= 5) break
            val versions = try {
                api.projectVersions(hit.id, listOf("fabric"), emptyList(), noCancel)
            } catch (e: Exception) {
                say("  «${hit.displayName}»: versioni non lette (${e.message})")
                continue
            }
            val withPack = versions.filter { v -> v.files.any { it.fileName.endsWith(".mrpack") } }
            val version = withPack.firstOrNull { it.channel == ReleaseChannel.RELEASE } ?: withPack.firstOrNull() ?: continue
            val f = version.files.firstOrNull { it.primary && it.fileName.endsWith(".mrpack") }
                ?: version.files.first { it.fileName.endsWith(".mrpack") }
            if (f.size !in 1..MAX_MRPACK_BYTES) {
                say("  «${hit.displayName}»: .mrpack di ${f.size} byte, troppo grande per questa prova")
                continue
            }
            val hash = f.expectedHash()?.let { ExpectedHash.parse(it) }
            if (hash == null) {
                say("  «${hit.displayName}»: il servizio non pubblica un'impronta valida del .mrpack")
                continue
            }
            val downloaded = download(f.url, f.fileName, hash, "Modpack (prova): " + hit.displayName) ?: continue
            val target = File(dir, "${out.size + unreadable.size}.mrpack")
            downloaded.copyTo(target, overwrite = true)
            val title = hit.displayName + " " + version.versionNumber
            when (val r = ModrinthPackReader.read(target)) {
                is PackReadResult.Ok -> out.add(RealPack(title, target, localized(r.pack)))
                is PackReadResult.Failed -> {
                    unreadable.add(title + ": " + r.message + (r.detail?.let { " ($it)" } ?: ""))
                    say("  «$title»: NON LETTO — " + r.message + (r.detail?.let { " ($it)" } ?: ""))
                }
            }
        }
        packs = out
        return out
    }

    /** Con il finto Modrinth i file dei modpack di prova puntano all'indirizzo che vede l'emulatore: si spostano sul server locale. */
    private fun localized(pack: Pack): Pack {
        val local = fake ?: return pack
        return Pack(
            pack.name, pack.versionId, pack.summary, pack.minecraft, pack.loader, pack.loaderVersion,
            pack.files.map { f -> f.copy(urls = f.urls.map { it.replace("http://10.0.2.2:8765", local) }) },
            pack.overrides, pack.problems
        )
    }

    @Test fun realModpacksAreReadAndPlanned() {
        val list = loadPacks()
        for (p in list) {
            val plan = ModpackPlanner.plan(p.pack, engine.checker)
            say(
                "«${p.title}»: Minecraft ${p.pack.minecraft}, ${p.pack.loader?.label} ${p.pack.loaderVersion}, " +
                    "${p.pack.files.size} file (${p.clientFiles} per il client, ${p.declaredBytes} byte dichiarati), " +
                    "impostazioni ${p.pack.overrides.common}+${p.pack.overrides.client} (server ${p.pack.overrides.server}), " +
                    "voci scartate ${p.pack.problems.size}"
            )
            say(
                "    piano: da scaricare ${plan.toDownload.size}, solo server ${plan.skipped.size}, bloccati ${plan.blocked.size}" +
                    (if (plan.hostsToApprove.isNotEmpty()) " (siti da consentire: ${plan.hostsToApprove})" else "")
            )
            for (problem in p.pack.problems.take(5)) say("    ! $problem")
            for (b in plan.blocked.take(5)) say("    bloccato: ${b.file.path} — ${b.reason}")
        }
        assertTrue("modpack veri che il lettore dell'app non sa leggere: $unreadable", unreadable.isEmpty())
        assertTrue("servono almeno due modpack veri scaricati e letti (letti: ${list.size})", list.size >= 2)
        for (p in list) {
            val plan = ModpackPlanner.plan(p.pack, engine.checker)
            assertTrue(p.title, p.pack.files.isNotEmpty())
            assertTrue(p.title + ": " + plan.blockers, plan.canInstall)
            assertTrue(p.title + ": voci scartate: " + p.pack.problems.take(3), p.pack.problems.isEmpty())
            if (fake == null) for (f in plan.toDownload) assertTrue(p.title + ": " + f.urls.first(), f.urls.first().startsWith("https://"))
        }
    }

    @Test fun aSmallRealModpackIsInstalledCompletely() {
        val list = loadPacks()
        assumeTrue("nessun modpack vero scaricato", list.isNotEmpty())
        val candidate = list.filter { it.clientFiles in 1..MAX_INSTALL_FILES && it.declaredBytes in 1..MAX_INSTALL_BYTES }
            .minByOrNull { it.declaredBytes }
        if (candidate == null) {
            say(
                "Installazione completa non provata: nessun modpack abbastanza piccolo (fino a $MAX_INSTALL_FILES file e " +
                    "${MAX_INSTALL_BYTES / 1024 / 1024} MB): " + list.joinToString { "${it.title} ${it.clientFiles} file ${it.declaredBytes} byte" }
            )
            assumeTrue("nessun modpack abbastanza piccolo", false)
            return
        }
        val plan = ModpackPlanner.plan(candidate.pack, engine.checker)
        for (host in plan.hostsToApprove) {
            // È ciò che farebbe l'utente toccando «CONSENTI»: la prova lo dichiara nel resoconto.
            assertTrue(host, engine.approveHost(host))
            say("    consentito (come farebbe l'utente): $host")
        }
        var n = 0
        val manager = ModManager(ProfileRepository(MemoryDocumentStore()), File(base, "profiles"), newId = { "id" + (++n) })
        val installer = ModpackInstaller(manager, engine, { f, name, cancel -> JarAnalyzer(tempDir = base).analyze(f, name, cancel) })
        val started = System.currentTimeMillis()
        val result = installer.install(InstallRequest(candidate.pack, candidate.file, candidate.title))
        say(
            "Installazione di «${candidate.title}»: ${result.placedFiles} di ${result.totalFiles} file al loro posto, " +
                "impostazioni applicate ${result.overridesApplied} (non applicate ${result.overridesSkipped.size}, ignorate ${result.overridesIgnored}), " +
                "registrati nel MOD MANAGER ${result.registered}, non letti come mod ${result.unreadable.size}, " +
                "in ${(System.currentTimeMillis() - started) / 1000} s" + (result.fatal?.let { " — FERMATA: $it" } ?: "")
        )
        for (f in result.failures.take(10)) say("    non arrivato: ${f.path} — ${f.reason}")
        for (s in result.overridesSkipped.take(10)) say("    impostazione non applicata: $s")
        for (u in result.unreadable.take(10)) say("    non letto come mod: $u")

        assertNull(result.fatal)
        assertTrue("il modpack deve risultare completo: " + result.failures.take(5).map { it.path + ": " + it.reason } + " " + result.overridesSkipped.take(5), result.complete)
        assertEquals(result.totalFiles, result.placedFiles)

        // Controllo indipendente, sul disco: i file del modpack ci sono e hanno l'impronta pubblicata.
        val dir = manager.profileDir(result.profile!!.id)
        for (f in candidate.pack.files.filter { it.client != Side.UNSUPPORTED }) {
            val onDisk = File(dir, f.path)
            val disabled = File(dir, f.path + ".disabled")
            assertTrue(f.path, onDisk.isFile || disabled.isFile)
            val sha512 = f.sha512
            if (sha512 != null) assertEquals(f.path, sha512.lowercase(), Fx.sha512Hex((if (onDisk.isFile) onDisk else disabled).readBytes()))
        }
        val (check, rec) = manager.check(result.profile!!.id)!!
        say(
            "    verifica del profilo: ${check.checkedItems} file attivi, ${check.blocking.size} bloccanti, " +
                "${check.needsAttention.size} da controllare, ${check.warnings.size} avvisi"
        )
        for (i in check.issues.take(8)) say("      - ${i.level}: ${i.message}" + (i.detail?.let { " ($it)" } ?: ""))
        assertTrue("dopo l'installazione l'elenco del MOD MANAGER deve coincidere con il disco", rec.missing.isEmpty() && rec.changedOutside.isEmpty())
        assertTrue("almeno un file deve essere registrato nel MOD MANAGER", result.registered >= 1)
    }
}
