package com.mmi.modinstaller.modpack

import com.mmi.modinstaller.core.MemoryDocumentStore
import com.mmi.modinstaller.download.DownloadEngine
import com.mmi.modinstaller.download.DownloadOptions
import com.mmi.modinstaller.download.DownloadRequest
import com.mmi.modinstaller.download.DownloadState
import com.mmi.modinstaller.download.EnqueueResult
import com.mmi.modinstaller.download.UrlPolicy
import com.mmi.modinstaller.jar.ContentKind
import com.mmi.modinstaller.jar.JarAnalyzer
import com.mmi.modinstaller.jar.ModLoader
import com.mmi.modinstaller.mods.ItemCategory
import com.mmi.modinstaller.mods.ModManager
import com.mmi.modinstaller.mods.Outcome
import com.mmi.modinstaller.mods.ProfileDraft
import com.mmi.modinstaller.mods.ProfileRepository
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * L'installazione di un modpack con i motori veri (download, profili, lettura dei .jar) e un finto sito locale: file giusti,
 * file manomessi, file che mancano, siti non consentiti, annullamento e ripresa.
 */
class ModpackInstallerTest {

    private lateinit var base: File
    private lateinit var server: TestServer
    private lateinit var engine: DownloadEngine
    private lateinit var manager: ModManager
    private lateinit var installer: ModpackInstaller

    /** Cosa risponde il finto sito per ogni percorso (si può cambiare nei test). */
    private val cdn = HashMap<String, ByteArray>()
    private var status = HashMap<String, Int>()
    private var slow = HashSet<String>()

    private val sodium = Fx.fabricJar("sodium", "Sodium", "0.5.0")
    private val lithium = Fx.fabricJar("lithium", "Lithium", "0.11.0", mapOf("sodium" to ">=0.5.0"))
    private val texture = Fx.resourcePack("Texture")

    @Before fun setUp() {
        base = Fx.tempDir()
        server = TestServer()
        server.handler = { req ->
            val path = req.path.substringBefore('?')
            val forced = status[path]
            val body = cdn[path]
            when {
                forced != null -> TestServer.Response(forced, emptyMap(), "errore".toByteArray())
                body == null -> TestServer.Response(404, emptyMap(), "non c'è".toByteArray())
                path in slow -> TestServer.Response(200, mapOf("Content-Type" to "application/octet-stream"), body, chunkSize = 4 * 1024, delayPerChunkMs = 40)
                else -> TestServer.Response(200, mapOf("Content-Type" to "application/octet-stream"), body)
            }
        }
        val options = DownloadOptions(
            maxParallel = 2, connectTimeoutMs = 3_000, readTimeoutMs = 5_000, maxRedirects = 3,
            retryDelaysMs = longArrayOf(30, 30), reserveBytes = 0, freeSpace = { Long.MAX_VALUE }
        )
        engine = DownloadEngine(
            MemoryDocumentStore(), File(base, "downloads"),
            UrlPolicy(insecureHosts = setOf("127.0.0.1:${server.port}")), options
        )
        var n = 0
        manager = ModManager(ProfileRepository(MemoryDocumentStore()), File(base, "profiles"), newId = { "id" + (++n) })
        installer = ModpackInstaller(manager, engine, { f, name, cancel -> JarAnalyzer(tempDir = base).analyze(f, name, cancel) }, pollMs = 20)
    }

    @After fun tearDown() {
        engine.shutdown()
        server.close()
        TestServer.deleteTree(base)
    }

    // ------------------------------------------------------------------ attrezzi

    private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

    private fun requestsFor(path: String) = server.requests.count { it.path.substringBefore('?') == path }

    private fun readPack(files: List<String>, overrides: Map<String, ByteArray> = emptyMap(), deps: String? = null): Pair<File, Pack> {
        val archive = Fx.mrpack(File(base, "p-${System.nanoTime()}.mrpack"), if (deps == null) Fx.index(files) else Fx.index(files, deps = deps), overrides)
        val r = ModrinthPackReader.read(archive)
        assertTrue(if (r is PackReadResult.Failed) r.message + " " + r.detail else "", r is PackReadResult.Ok)
        return archive to (r as PackReadResult.Ok).pack
    }

    private fun standardPack(overrides: Map<String, ByteArray> = emptyMap()): Pair<File, Pack> {
        cdn["/m/sodium.jar"] = sodium
        cdn["/m/lithium.jar"] = lithium
        cdn["/m/texture.zip"] = texture
        return readPack(
            listOf(
                Fx.fileJson("mods/sodium-0.5.0.jar", sodium, url("/m/sodium.jar")),
                Fx.fileJson("mods/lithium-0.11.0.jar", lithium, url("/m/lithium.jar")),
                Fx.fileJson("resourcepacks/Texture.zip", texture, url("/m/texture.zip"))
            ),
            overrides
        )
    }

    private fun run(
        pack: Pair<File, Pack>,
        name: String = "Il mio modpack",
        resume: String? = null,
        cancel: () -> Unit = {},
        progress: (InstallProgress) -> Unit = {}
    ): ModpackResult = installer.install(InstallRequest(pack.second, pack.first, name, resume), cancel, progress)

    private fun profileFile(result: ModpackResult, path: String) = File(manager.profileDir(result.profile!!.id), path)

    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes()
            }
        }
        return out
    }

    // ------------------------------------------------------------------ l'installazione riuscita

    @Test fun installsFilesAndSettingsAndRegistersEverythingInOneStep() {
        val pack = standardPack(
            mapOf(
                "overrides/config/sodium.toml" to "quality=high".toByteArray(),
                "overrides/options.txt" to "fov:90".toByteArray(),
                "overrides/mods/locale.jar" to Fx.fabricJar("locale", "Locale", "1.0.0")
            )
        )
        val phases = ArrayList<Phase>()
        var lastDone = 0
        val r = run(pack) { p ->
            if (phases.lastOrNull() != p.phase) phases.add(p.phase)
            if (p.phase == Phase.DOWNLOADING) {
                assertTrue("i file fatti non devono tornare indietro", p.doneFiles >= lastDone)
                lastDone = p.doneFiles
            }
        }
        assertNull(r.fatal)
        assertTrue("completo: " + r.failures.joinToString { it.path + " " + it.reason }, r.complete)
        assertEquals(3, r.totalFiles)
        assertEquals(3, r.placedFiles)
        assertEquals(3, r.overridesApplied)
        assertEquals(4, r.registered)
        assertTrue(r.createdProfile)
        assertTrue(r.unreadable.isEmpty())
        assertEquals(listOf(Phase.PREPARING, Phase.DOWNLOADING, Phase.EXTRACTING, Phase.CHECKING, Phase.DONE), phases.distinct())

        val profile = r.profile!!
        assertEquals("Il mio modpack", profile.name)
        assertEquals("1.20.1", profile.minecraft)
        assertEquals(ModLoader.FABRIC, profile.loader)
        assertEquals("0.15.7", profile.loaderVersion)

        // I file sono quelli giusti, byte per byte.
        assertArrayEquals(sodium, profileFile(r, "mods/sodium-0.5.0.jar").readBytes())
        assertArrayEquals(lithium, profileFile(r, "mods/lithium-0.11.0.jar").readBytes())
        assertArrayEquals(texture, profileFile(r, "resourcepacks/Texture.zip").readBytes())
        assertEquals("quality=high", profileFile(r, "config/sodium.toml").readText())
        assertEquals("fov:90", profileFile(r, "options.txt").readText())

        // Il MOD MANAGER li conosce tutti, con quello che ha letto.
        val items = manager.items(profile.id)
        assertEquals(4, items.size)
        assertEquals(setOf("sodium-0.5.0.jar", "lithium-0.11.0.jar", "locale.jar", "Texture.zip"), items.map { it.fileName }.toSet())
        assertTrue(items.all { it.source == "modpack" && it.enabled })
        assertEquals(ContentKind.RESOURCE_PACK, items.first { it.fileName == "Texture.zip" }.kind)
        assertEquals("sodium", items.first { it.fileName == "sodium-0.5.0.jar" }.mods.single().id)
        assertEquals(ItemCategory.MOD, items.first { it.fileName == "locale.jar" }.category)

        // Nessun file avanzato nel DOWNLOAD MANAGER né nella sua cartella.
        assertTrue(engine.records().isEmpty())
        assertEquals(emptyList<String>(), File(base, "downloads").list()?.toList().orEmpty())

        // Una sola voce di cronologia per tutto il modpack, e le impostazioni sono nell'elenco dei file «extra».
        val journal = manager.journal(profile.id)
        assertEquals(1, journal.size)
        assertTrue(journal.single().label.contains("Pacchetto Prova"))
        assertEquals(4, journal.single().steps.size)
        assertEquals(listOf("config/sodium.toml", "options.txt"), manager.extraFiles(profile.id))
    }

    @Test fun theExportedZipHasTheModsAndTheSettingsOfTheModpack() {
        val r = run(standardPack(mapOf("overrides/config/sodium.toml" to "quality=high".toByteArray(), "client-overrides/options.txt" to "fov:70".toByteArray())))
        assertTrue(r.complete)
        val out = ByteArrayOutputStream()
        val summary = (manager.exportZip(r.profile!!.id, out) as Outcome.Ok).value
        val entries = zipEntries(out.toByteArray())
        assertEquals(
            setOf("mods/sodium-0.5.0.jar", "mods/lithium-0.11.0.jar", "resourcepacks/Texture.zip", "config/sodium.toml", "options.txt"),
            entries.keys
        )
        assertEquals("fov:70", String(entries.getValue("options.txt")))
        assertEquals(5, summary.files)
        assertEquals(2, summary.extras)
        assertTrue(summary.missing.isEmpty() && summary.changed.isEmpty())
    }

    @Test fun theWholeInstallationCanBeUndoneInOneStepAndTheProfileCanBeDeleted() {
        val r = run(standardPack())
        val pid = r.profile!!.id
        assertTrue(manager.undo(pid) is Outcome.Ok)
        assertTrue(manager.items(pid).isEmpty())
        assertEquals(emptyList<String>(), File(manager.profileDir(pid), "mods").list()?.toList().orEmpty())
        assertTrue(manager.deleteProfile(pid) is Outcome.Ok)
        assertFalse(manager.profileDir(pid).exists())
    }

    // ------------------------------------------------------------------ quando qualcosa non va

    @Test fun aTamperedDownloadIsNeverKeptAndTheResultSaysIncomplete() {
        val pack = standardPack()
        val altered = sodium.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        cdn["/m/sodium.jar"] = altered
        val r = run(pack)
        assertNull(r.fatal)
        assertFalse(r.complete)
        assertEquals(listOf("mods/sodium-0.5.0.jar"), r.failures.map { it.path })
        assertTrue(r.failures.single().reason, r.failures.single().reason.isNotBlank())
        assertFalse(profileFile(r, "mods/sodium-0.5.0.jar").exists())
        assertEquals(2, r.placedFiles)
        assertEquals(2, manager.items(r.profile!!.id).size)
        assertTrue("niente resta in coda", engine.records().isEmpty())
        assertEquals(emptyList<String>(), File(base, "downloads").list()?.toList().orEmpty())
    }

    @Test fun aSha1OnlyPackIsVerifiedWithSha1() {
        cdn["/m/sodium.jar"] = sodium.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val pack = readPack(listOf(Fx.fileJson("mods/sodium-0.5.0.jar", sodium, url("/m/sodium.jar"), sha512 = false)))
        val r = run(pack)
        assertFalse(r.complete)
        assertEquals(1, r.failures.size)
        assertFalse(profileFile(r, "mods/sodium-0.5.0.jar").exists())
    }

    @Test fun aFileTheSiteDoesNotHaveIsReportedWithItsPath() {
        val pack = standardPack()
        cdn.remove("/m/lithium.jar")
        val r = run(pack)
        assertFalse(r.complete)
        assertEquals(listOf("mods/lithium-0.11.0.jar"), r.failures.map { it.path })
        assertEquals(2, r.placedFiles)
        assertTrue(engine.records().isEmpty())
    }

    @Test fun anUnapprovedSiteIsNeverContactedAndTheHostIsOfferedForApproval() {
        val pack = readPack(listOf(Fx.fileJson("mods/a.jar", sodium, "https://mods.example.org/a.jar"), Fx.fileJson("mods/b.jar", lithium, url("/m/b.jar"))))
        cdn["/m/b.jar"] = lithium
        val plan = ModpackPlanner.plan(pack.second, engine.checker)
        assertEquals(listOf("mods.example.org"), plan.hostsToApprove)
        val r = run(pack)
        assertFalse(r.complete)
        assertEquals(listOf("mods/a.jar"), r.failures.map { it.path })
        assertTrue(r.failures.single().reason.contains("mods.example.org"))
        assertEquals(1, r.placedFiles)
        assertEquals(1, requestsFor("/m/b.jar"))
    }

    @Test fun refusedSettingsMakeTheResultIncompleteWithoutHidingIt() {
        val r = run(standardPack(mapOf("overrides/start.sh" to "#!/bin/sh".toByteArray(), "overrides/config/a.toml" to "a".toByteArray())))
        assertNull(r.fatal)
        assertTrue(r.failures.isEmpty())
        assertFalse(r.complete)
        assertEquals(1, r.overridesApplied)
        assertTrue(r.overridesSkipped.single().startsWith("start.sh"))
        assertFalse(profileFile(r, "start.sh").exists())
    }

    @Test fun discardedIndexEntriesKeepThePackFromBeingComplete() {
        cdn["/m/sodium.jar"] = sodium
        val pack = readPack(
            listOf(
                Fx.fileJson("mods/sodium-0.5.0.jar", sodium, url("/m/sodium.jar")),
                """{"path":"../evil.jar","hashes":{"sha1":"${Fx.sha1Hex(sodium)}"},"downloads":["${url("/m/sodium.jar")}"]}"""
            )
        )
        val r = run(pack)
        assertEquals(1, r.problems.size)
        assertFalse(r.complete)
        assertEquals(1, r.placedFiles)
    }

    @Test fun aFileThatIsNotAReadableModIsStillKeptAndFlagged() {
        val junk = "questo non è uno zip".toByteArray()
        cdn["/m/broken.jar"] = junk
        val r = run(readPack(listOf(Fx.fileJson("mods/broken.jar", junk, url("/m/broken.jar")))))
        assertTrue(r.failures.isEmpty())
        assertEquals(1, r.unreadable.size)
        assertTrue(profileFile(r, "mods/broken.jar").isFile)
        val item = manager.items(r.profile!!.id).single()
        assertEquals("broken.jar", item.fileName)
        assertNotNull(item.note)
        assertTrue(item.note!!, item.note!!.contains("non sembra"))
        // Si esporta lo stesso: un file del modpack non sparisce in silenzio.
        val out = ByteArrayOutputStream()
        manager.exportZip(r.profile!!.id, out)
        assertTrue(zipEntries(out.toByteArray()).containsKey("mods/broken.jar"))
    }

    @Test fun clientUnsupportedFilesAreNeitherRequestedNorCounted() {
        cdn["/m/server.jar"] = lithium
        cdn["/m/sodium.jar"] = sodium
        val pack = readPack(
            listOf(
                Fx.fileJson("mods/server.jar", lithium, url("/m/server.jar"), env = """{"client":"unsupported","server":"required"}"""),
                Fx.fileJson("mods/sodium.jar", sodium, url("/m/sodium.jar"))
            )
        )
        val r = run(pack)
        assertTrue(r.complete)
        assertEquals(1, r.totalFiles)
        assertEquals(listOf("mods/server.jar"), r.skippedByEnv)
        assertEquals(0, requestsFor("/m/server.jar"))
        assertFalse(profileFile(r, "mods/server.jar").exists())
    }

    @Test fun twoPathsWithTheSameAddressDownloadOnce() {
        cdn["/m/shared.jar"] = sodium
        val pack = readPack(
            listOf(
                Fx.fileJson("mods/uno.jar", sodium, url("/m/shared.jar")),
                Fx.fileJson("mods/due.jar", sodium, url("/m/shared.jar"))
            )
        )
        val r = run(pack)
        assertTrue(r.complete)
        assertEquals(1, requestsFor("/m/shared.jar"))
        assertEquals(2, r.placedFiles)
        assertArrayEquals(sodium, profileFile(r, "mods/uno.jar").readBytes())
        assertArrayEquals(sodium, profileFile(r, "mods/due.jar").readBytes())
        assertTrue(engine.records().isEmpty())
    }

    // ------------------------------------------------------------------ prima di creare qualsiasi cosa

    @Test fun aPackThatCannotBeInstalledLeavesNothingBehind() {
        val noLoader = readPack(listOf(Fx.fileJson("mods/a.jar", sodium, url("/m/a.jar"))), deps = """{"minecraft":"1.20.1"}""")
        val r = run(noLoader)
        assertNotNull(r.fatal)
        assertTrue(manager.profiles().isEmpty())
        assertEquals(0, server.requests.size)
    }

    @Test fun aProfileNameThatAlreadyExistsStopsBeforeDownloading() {
        manager.createProfile(ProfileDraft("Il mio modpack", "1.20.1", ModLoader.FABRIC, null))
        val r = run(standardPack())
        assertTrue(r.fatal!!, r.fatal!!.contains("nome"))
        assertEquals(1, manager.profiles().size)
        assertEquals(0, server.requests.size)
    }

    @Test fun resumingAProfileThatNoLongerExistsFails() {
        val r = run(standardPack(), resume = "non-esiste")
        assertNotNull(r.fatal)
        assertEquals(0, server.requests.size)
    }

    // ------------------------------------------------------------------ annullare e riprendere

    @Test fun cancelingRemovesEverythingTheInstallationCreated() {
        val big = TestServer.bytes(120_000, seed = 3)
        cdn["/m/sodium.jar"] = sodium
        cdn["/m/big.jar"] = big
        slow.add("/m/big.jar")
        val pack = readPack(
            listOf(Fx.fileJson("mods/sodium.jar", sodium, url("/m/sodium.jar")), Fx.fileJson("mods/big.jar", big, url("/m/big.jar"))),
            mapOf("overrides/config/a.toml" to "a".toByteArray())
        )
        val stop = AtomicBoolean(false)
        try {
            run(pack, cancel = { if (stop.get()) throw CancellationException("annullato") }) { p ->
                if (p.phase == Phase.DOWNLOADING && p.doneFiles >= 1) stop.set(true)
            }
            fail("doveva essere annullata")
        } catch (e: CancellationException) {
            // atteso
        }
        assertTrue("il profilo creato deve sparire", manager.profiles().isEmpty())
        assertEquals(emptyList<String>(), File(base, "profiles").list()?.toList().orEmpty())
        assertTrue(engine.records().isEmpty())
        assertEquals(emptyList<String>(), File(base, "downloads").list()?.toList().orEmpty())
    }

    @Test fun resumingDownloadsOnlyWhatIsMissingAndCompletesTheProfile() {
        val pack = standardPack(mapOf("overrides/config/a.toml" to "a".toByteArray()))
        status["/m/lithium.jar"] = 500
        val first = run(pack)
        assertFalse(first.complete)
        assertEquals(listOf("mods/lithium-0.11.0.jar"), first.failures.map { it.path })
        val pid = first.profile!!.id
        assertEquals(2, manager.items(pid).size)

        status.remove("/m/lithium.jar")
        val sodiumBefore = requestsFor("/m/sodium.jar")
        val textureBefore = requestsFor("/m/texture.zip")
        val second = run(pack, resume = pid)
        assertTrue(second.failures.joinToString { it.path + " " + it.reason }, second.complete)
        assertFalse("riprendere non crea un altro profilo", second.createdProfile)
        assertEquals(1, manager.profiles().size)
        assertEquals("i file già al loro posto non si riscaricano", sodiumBefore, requestsFor("/m/sodium.jar"))
        assertEquals(textureBefore, requestsFor("/m/texture.zip"))
        assertEquals(3, second.placedFiles)
        assertEquals(3, manager.items(pid).size)
        assertEquals(1, second.registered)
        assertArrayEquals(lithium, profileFile(second, "mods/lithium-0.11.0.jar").readBytes())
    }

    @Test fun resumingReplacesAFileThatWasDamagedOnDisk() {
        val pack = standardPack()
        val first = run(pack)
        val pid = first.profile!!.id
        val damaged = profileFile(first, "mods/sodium-0.5.0.jar")
        damaged.writeBytes(ByteArray(sodium.size) { 9 })
        val second = run(pack, resume = pid)
        assertTrue(second.complete)
        assertArrayEquals(sodium, damaged.readBytes())
        assertEquals(2, requestsFor("/m/sodium.jar"))
        val item = manager.items(pid).first { it.fileName == "sodium-0.5.0.jar" }
        assertEquals(Fx.sha1Hex(sodium), item.sha1)
    }

    @Test fun resumingDoesNotBringBackAModTheUserDisabled() {
        val pack = standardPack()
        val first = run(pack)
        val pid = first.profile!!.id
        val item = manager.items(pid).first { it.fileName == "sodium-0.5.0.jar" }
        assertTrue(manager.setEnabled(pid, item.id, false) is Outcome.Ok)
        val before = requestsFor("/m/sodium.jar")
        val second = run(pack, resume = pid)
        assertTrue(second.failures.joinToString { it.path + " " + it.reason }, second.complete)
        assertEquals("un file disattivato non si riscarica", before, requestsFor("/m/sodium.jar"))
        assertEquals(3, second.placedFiles)
        assertFalse(profileFile(second, "mods/sodium-0.5.0.jar").exists())
        assertTrue(profileFile(second, "mods/sodium-0.5.0.jar.disabled").isFile)
        assertFalse(manager.items(pid).first { it.fileName == "sodium-0.5.0.jar" }.enabled)
        assertEquals(3, manager.items(pid).size)
    }

    // ------------------------------------------------------------------ rapporti con il DOWNLOAD MANAGER

    @Test fun aDownloadTheUserStartedHimselfIsUsedButNeverTakenAway() {
        cdn["/m/sodium.jar"] = sodium
        val mine = engine.enqueue(DownloadRequest(url("/m/sodium.jar"), "mio.jar", null, "scaricato da me")) as EnqueueResult.Added
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && engine.record(mine.record.id)?.state != DownloadState.COMPLETED) Thread.sleep(20)
        assertEquals(DownloadState.COMPLETED, engine.record(mine.record.id)!!.state)

        val r = run(readPack(listOf(Fx.fileJson("mods/sodium.jar", sodium, url("/m/sodium.jar")))))
        assertTrue(r.complete)
        assertArrayEquals(sodium, profileFile(r, "mods/sodium.jar").readBytes())
        val still = engine.record(mine.record.id)
        assertNotNull("il download dell'utente resta nella lista", still)
        assertTrue("e il suo file resta dov'era", engine.file(still!!).isFile)
        assertEquals(1, requestsFor("/m/sodium.jar"))
    }

    @Test fun manyFilesAreDownloadedInGroupsWithoutFillingTheQueue() {
        val files = ArrayList<String>()
        for (i in 1..30) {
            val bytes = Fx.fabricJar("mod$i", "Mod $i", "1.0.$i")
            cdn["/m/mod$i.jar"] = bytes
            files.add(Fx.fileJson("mods/mod$i.jar", bytes, url("/m/mod$i.jar")))
        }
        var maxQueue = 0
        val stop = engine.addListener { maxQueue = maxOf(maxQueue, it.size) }
        val r = run(readPack(files))
        stop()
        assertTrue(r.failures.joinToString { it.path + " " + it.reason }, r.complete)
        assertEquals(30, r.placedFiles)
        assertEquals(30, manager.items(r.profile!!.id).size)
        assertTrue("la coda non deve riempirsi con tutto il modpack: $maxQueue", maxQueue <= 13)
    }
}
