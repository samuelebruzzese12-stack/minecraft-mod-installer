package com.mmi.modinstaller.core

import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** L'archivio centrale esteso: sezioni dei motori, impostazioni, registro errori. */
class CentralStoreTest {
    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        dir = T.tmpDir()
        file = File(dir, "library/library.json")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun mods(vararg names: String): Map<String, Any?> =
        linkedMapOf("profiles" to names.map { linkedMapOf("name" to it, "count" to 3L) })

    @Test
    fun sectionsSurviveReopeningTheFile() {
        val a = LibraryStore(file)
        assertTrue(a.write("mods", mods("Principale", "Prova")))
        a.addRecent(RecentFile("content://x", "x.zip", 10, 1))

        val b = LibraryStore(file)
        assertEquals(mods("Principale", "Prova"), b.read("mods"))
        assertEquals(1, b.recents().size)
    }

    @Test
    fun savingTheOldSectionsDoesNotDropTheNewOnes() {
        val store = LibraryStore(file)
        store.write("downloads", linkedMapOf("queue" to listOf("a", "b")))
        store.addHistory("T", "azione", "ok")
        store.putInstalled(entry("1"))
        store.clearRecents()

        val reopened = LibraryStore(file)
        assertEquals(linkedMapOf("queue" to listOf("a", "b")), reopened.read("downloads"))
        assertEquals(1, reopened.history().size)
        assertEquals(1, reopened.installed().size)
    }

    @Test
    fun aFileWrittenByTheFirstVersionStillLoads() {
        file.parentFile!!.mkdirs()
        file.writeText(
            """{"version":1,"recents":[{"uri":"content://a","name":"a.mcpack","size":5,"at":9}],
               "installed":[{"id":"i1","title":"Vecchio","kind":"ADDON","file":"v.mcaddon","size":7,"source":"s",
                             "sentAt":1,"state":"CONFIRMED","stateAt":2,"packs":[]}],
               "history":[{"at":3,"title":"Vecchio","action":"Invio","result":"ok"}]}"""
        )
        val store = LibraryStore(file)
        assertFalse(store.recoveredFromCorruption)
        assertEquals("a.mcpack", store.recents().single().name)
        assertEquals(InstallState.CONFIRMED, store.installed().single().state)
        assertEquals(1, store.history().size)

        store.write("settings", linkedMapOf("theme" to "DARK"))
        val reopened = LibraryStore(file)
        assertEquals(InstallState.CONFIRMED, reopened.installed().single().state)
        assertEquals("DARK", (reopened.read("settings") as Map<*, *>)["theme"])
    }

    @Test
    fun readingGivesCopies() {
        val store = LibraryStore(file)
        store.write("mods", mods("A"))
        @Suppress("UNCHECKED_CAST")
        val copy = store.read("mods") as MutableMap<String, Any?>
        copy["profiles"] = "alterato"
        assertEquals(mods("A"), store.read("mods"))
    }

    @Test
    fun reservedNamesAreRefused() {
        val store = LibraryStore(file)
        for (name in listOf("installed", "recents", "history", "version", " ")) {
            try {
                store.write(name, "x")
                fail("«$name» non doveva essere accettato")
            } catch (e: IllegalArgumentException) {
                // atteso
            }
        }
    }

    @Test
    fun aDamagedFileIsSetAsideAndTheStoreStartsEmpty() {
        file.parentFile!!.mkdirs()
        file.writeText("{ non è json")
        val store = LibraryStore(file)
        assertTrue(store.recoveredFromCorruption)
        assertNull(store.read("mods"))
        assertTrue(File(file.parentFile, "library.json.corrupt").isFile)
        assertTrue(store.write("mods", mods("Nuovo")))
        assertEquals(mods("Nuovo"), LibraryStore(file).read("mods"))
    }

    @Test
    fun updatesFromManyThreadsDoNotLoseWrites() {
        val store = LibraryStore(file)
        val start = CountDownLatch(1)
        val threads = (1..4).map {
            thread {
                start.await()
                repeat(25) {
                    store.update("counter") { cur -> ((cur as? Number)?.toLong() ?: 0L) + 1 }
                }
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(100L, (LibraryStore(file).read("counter") as Number).toLong())
    }

    @Test
    fun deletingASectionRemovesItFromTheFile() {
        val store = LibraryStore(file)
        store.write("mods", mods("A"))
        store.write("mods", null)
        assertNull(LibraryStore(file).read("mods"))
        assertFalse(file.readText().contains("mods"))
    }

    @Test
    fun memoryStoreBehavesTheSame() {
        val store: DocumentStore = MemoryDocumentStore()
        assertNull(store.read("a"))
        store.write("a", mods("X"))
        store.update("a") { cur -> (cur as Map<*, *>) + mapOf("extra" to true) }
        assertEquals(true, (store.read("a") as Map<*, *>)["extra"])
    }

    // ------------------------------------------------------------------ impostazioni

    @Test
    fun settingsDefaultsAndPersistence() {
        val repo = SettingsRepository(LibraryStore(file))
        assertEquals(AppSettings(), repo.load())
        val changed = repo.update { it.copy(theme = ThemeMode.DARK, profile = PerformanceProfile.CUSTOM, customMemoryMb = 3072) }
        assertEquals(ThemeMode.DARK, changed.theme)
        val again = SettingsRepository(LibraryStore(file)).load()
        assertEquals(ThemeMode.DARK, again.theme)
        assertEquals(PerformanceProfile.CUSTOM, again.profile)
        assertEquals(3072, again.customMemoryMb)
    }

    @Test
    fun settingsIgnoreUnknownValuesAndClampNumbers() {
        val store = MemoryDocumentStore()
        store.write(AppSettings.SECTION, linkedMapOf("theme" to "ARCOBALENO", "profile" to 5L, "customMemoryMb" to 99999L))
        val s = SettingsRepository(store).load()
        assertEquals(ThemeMode.AUTO, s.theme)
        assertEquals(PerformanceProfile.BALANCED, s.profile)
        assertEquals(AppSettings.MAX_CUSTOM_MB, s.customMemoryMb)
        store.write(AppSettings.SECTION, "non è un oggetto")
        assertEquals(AppSettings(), SettingsRepository(store).load())
    }

    // ------------------------------------------------------------------ memoria consigliata

    @Test
    fun memoryPlanIsPrudentAndNeverAboveHalfTheDevice() {
        val ram = 8192
        val saver = MemoryPlanner.plan(AppSettings(profile = PerformanceProfile.SAVER), ram, null).memoryMb
        val balanced = MemoryPlanner.plan(AppSettings(profile = PerformanceProfile.BALANCED), ram, null).memoryMb
        val performance = MemoryPlanner.plan(AppSettings(profile = PerformanceProfile.PERFORMANCE), ram, null).memoryMb
        assertTrue("$saver < $balanced < $performance", saver < balanced && balanced < performance)
        assertTrue(performance <= ram / 2)
        assertEquals(0, saver % 64)
        val custom = MemoryPlanner.plan(AppSettings(profile = PerformanceProfile.CUSTOM, customMemoryMb = 8000), ram, null)
        assertEquals(ram / 2, custom.memoryMb)
        assertTrue(custom.note.contains("metà"))
    }

    @Test
    fun memoryPlanShrinksWhenTheDeviceIsShortOnFreeMemory() {
        val settings = AppSettings(profile = PerformanceProfile.PERFORMANCE)
        val roomy = MemoryPlanner.plan(settings, 8192, 5000).memoryMb
        val tight = MemoryPlanner.plan(settings, 8192, 2500)
        assertTrue(tight.memoryMb < roomy)
        assertTrue(tight.note.contains("libera"))
        val starving = MemoryPlanner.plan(settings, 8192, 600)
        assertEquals(512, starving.memoryMb)
        assertTrue(starving.note.contains("chiudi"))
        assertEquals(0, MemoryPlanner.plan(settings, 0, null).memoryMb)
        assertEquals(1024, MemoryPlanner.plan(AppSettings(), 2048, null).memoryMb)
    }

    @Test
    fun javaVersionFollowsTheMinecraftVersion() {
        fun java(v: String) = JavaRequirements.javaFor(McVersion.parse(v)!!)
        assertEquals(8, java("1.12.2"))
        assertEquals(8, java("1.16.5"))
        assertEquals(16, java("1.17"))
        assertEquals(16, java("1.17.1"))
        assertEquals(17, java("1.18"))
        assertEquals(17, java("1.20.4"))
        assertEquals(21, java("1.20.5"))
        assertEquals(21, java("1.21.1"))
    }

    // ------------------------------------------------------------------ registro errori

    @Test
    fun errorLogKeepsTheNewestFirstAndCapsItsSize() {
        var now = 1000L
        val log = ErrorLog(LibraryStore(file)) { now++ }
        assertTrue(log.recent().isEmpty())
        repeat(ErrorLog.MAX + 20) { log.record("prova", "errore $it", "dettaglio $it") }
        assertEquals(ErrorLog.MAX, log.count())
        val recent = ErrorLog(LibraryStore(file)).recent(3)
        assertEquals("errore ${ErrorLog.MAX + 19}", recent[0].message)
        assertEquals("errore ${ErrorLog.MAX + 18}", recent[1].message)
        assertTrue(recent[0].at > recent[1].at)
        log.clear()
        assertEquals(0, log.count())
    }

    @Test
    fun errorLogTruncatesHugeTexts() {
        val log = ErrorLog(MemoryDocumentStore())
        log.record("x".repeat(500), "m".repeat(5000), "d".repeat(50000))
        val e = log.recent().single()
        assertEquals(80, e.where.length)
        assertEquals(500, e.message.length)
        assertEquals(4000, e.detail!!.length)
    }

    // ------------------------------------------------------------------ integrità dei file

    private fun entry(id: String, file: String = "$id.mcpack", size: Long = 0): InstalledEntry = InstalledEntry(
        id = id, title = "Contenuto $id", kind = ContentKind.RESOURCE_PACK.name, version = null, author = null,
        packs = emptyList(), fileName = file, sizeBytes = size, source = "s", sentAt = 1, state = InstallState.SENT, stateAt = 1
    )

    @Test
    fun integrityCheckFindsMissingAlteredCorruptAndOrphanFiles() {
        val prepared = File(dir, "prepared").apply { mkdirs() }
        val good = T.zip(File(prepared, "ok.mcpack"), mapOf("manifest.json" to T.bytes("{}")))
        val altered = T.zip(File(prepared, "alterato.mcpack"), mapOf("manifest.json" to T.bytes("{}")))
        File(prepared, "rotto.mcpack").writeBytes(ByteArray(300) { 7 })
        File(prepared, "orfano.mcpack").writeBytes(ByteArray(10))
        File(prepared, ".mezzo.mcpack.part").writeBytes(ByteArray(10))

        val installed = listOf(
            entry("ok", "ok.mcpack", good.length()),
            entry("alt", "alterato.mcpack", altered.length() + 5),
            entry("rotto", "rotto.mcpack", 300),
            entry("assente", "assente.mcpack", 10)
        )
        val report = IntegrityChecker.check(installed, prepared)

        assertEquals(4, report.checked)
        assertEquals(3, report.problems)
        assertEquals(2, report.warnings)
        val titles = report.issues.map { it.title }
        assertTrue(titles.any { it.contains("Contenuto assente") && it.contains("mancante") })
        assertTrue(titles.any { it.contains("Contenuto alt") && it.contains("dimensione") })
        assertTrue(titles.any { it.contains("Contenuto rotto") && it.contains("non leggibile") })
        assertTrue(titles.any { it.contains("orfano.mcpack") })
        assertTrue(titles.any { it.contains(".mezzo.mcpack.part") })
        assertFalse(titles.any { it.contains("Contenuto ok") })
    }

    @Test
    fun integrityCheckIsCleanWhenEverythingMatches() {
        val prepared = File(dir, "prepared").apply { mkdirs() }
        val f = T.zip(File(prepared, "a.mcpack"), mapOf("manifest.json" to T.bytes("{}")))
        val report = IntegrityChecker.check(listOf(entry("a", "a.mcpack", f.length())), prepared)
        assertTrue(report.ok)
        assertEquals(1, report.checked)
        assertTrue(IntegrityChecker.check(emptyList(), File(dir, "non-esiste")).ok)
    }

    // ------------------------------------------------------------------ dispositivo e rapporto

    private fun device(abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"), ramGb: Int = 8, thermal: Int? = 0) = DeviceSnapshot(
        model = "SM-S921B", androidRelease = "15", sdk = 35, abis = abis, cpuCores = 8,
        totalRamBytes = ramGb * 1024L * 1024 * 1024, availableRamBytes = 3L * 1024 * 1024 * 1024, lowMemory = false,
        storageTotalBytes = 256L * 1024 * 1024 * 1024, storageFreeBytes = 100L * 1024 * 1024 * 1024,
        batteryTempC = 31.5f, thermalStatus = thermal
    )

    @Test
    fun deviceAssessmentReportsOnlyMeasuredFacts() {
        val good = DeviceAssessment.forJava(device())
        assertTrue(good.all { it.ok })
        val bad = DeviceAssessment.forJava(device(abis = listOf("x86_64"), ramGb = 3, thermal = 4))
        assertEquals(3, bad.count { !it.ok } - 0)
        assertTrue(bad.any { !it.ok && it.text.contains("arm64") })
        assertTrue(bad.any { !it.ok && it.text.contains("caldo") })
    }

    @Test
    fun diagnosticReportContainsTheKeyFactsAndNoFileNames() {
        val input = DiagnosticReport.Input(
            appVersion = "1.0.0", device = device(), minecraft = listOf("Minecraft Bedrock 1.21.50"),
            counts = listOf("Contenuti Bedrock gestiti" to "3"), appBytes = 5L * 1024 * 1024,
            integrity = IntegrityReport(3, listOf(IntegrityIssue(IssueLevel.PROBLEM, "«X»: file preparato mancante", "scegli di nuovo"))),
            errors = listOf(ErrorRecord(0, "Analisi", "Archivio danneggiato", "riga uno\nriga due")),
            memoryPlan = "2432 MB"
        )
        val text = DiagnosticReport.build(input, 0)
        for (needle in listOf("SM-S921B", "arm64-v8a", "Minecraft Bedrock 1.21.50", "PROBLEMA: «X»", "Archivio danneggiato", "    riga due", "1970-01-01", "2432 MB")) {
            assertTrue("manca «$needle» in:\n$text", text.contains(needle))
        }
        val empty = DiagnosticReport.build(DiagnosticReport.Input("1", null, emptyList(), emptyList(), 0, null, emptyList(), null), 0)
        assertTrue(empty.contains("Bedrock non trovato"))
        assertTrue(empty.contains("Nessun errore registrato"))
    }

    @Test
    fun crashReportRoundTrips() {
        val stack = "java.lang.IllegalStateException: boom\n\tat a.B.c(B.kt:1)\n\tat a.B.d(B.kt:2)"
        val text = CrashReport.encode(1234567L, "main", stack)
        val record = CrashReport.decode(text)!!
        assertEquals(1234567L, record.at)
        assertEquals("Arresto inatteso dell'app", record.where)
        assertEquals("java.lang.IllegalStateException: boom", record.message)
        assertTrue(record.detail!!.startsWith("Thread: main\n"))
        assertTrue(record.detail!!.contains("at a.B.d(B.kt:2)"))
    }

    @Test
    fun crashReportRefusesTruncatedOrForeignText() {
        assertNull(CrashReport.decode(""))
        assertNull(CrashReport.decode("12345"))
        assertNull(CrashReport.decode("12345\nmain"))
        assertNull(CrashReport.decode("non-un-numero\nmain\nboom"))
        assertNull(CrashReport.decode("12345\nmain\n   \n  "))
    }

    @Test
    fun crashReportThreadNameCannotInjectLines() {
        val text = CrashReport.encode(1L, "evil\nfake line", "Boom")
        val record = CrashReport.decode(text)!!
        assertEquals("Boom", record.message)
        assertTrue(record.detail!!.startsWith("Thread: evil fake line\n"))
    }

    @Test
    fun crashReportEntersTheErrorLogAndSurvivesTruncation() {
        val log = ErrorLog(MemoryDocumentStore())
        val record = CrashReport.decode(CrashReport.encode(5L, "t", "Boom: " + "x".repeat(2000) + "\n" + "y".repeat(9000)))!!
        log.record(record.where, record.message, record.detail, record.at)
        val stored = log.recent(1).single()
        assertEquals(5L, stored.at)
        assertEquals(500, stored.message.length)
        assertEquals(4000, stored.detail!!.length)
    }

    @Test
    fun fileSizesAddsUpFilesInsideFolders() {
        File(dir, "a/b").mkdirs()
        File(dir, "a/one.bin").writeBytes(ByteArray(100))
        File(dir, "a/b/two.bin").writeBytes(ByteArray(250))
        File(dir, "three.bin").writeBytes(ByteArray(7))
        assertEquals(350L, FileSizes.of(File(dir, "a")))
        assertEquals(357L, FileSizes.of(dir))
        assertEquals(7L, FileSizes.of(File(dir, "three.bin")))
        assertEquals(0L, FileSizes.of(File(dir, "non-esiste")))
    }

    @Test
    fun fileSizesDoesNotFollowLinks() {
        File(dir, "real").mkdirs()
        File(dir, "real/x.bin").writeBytes(ByteArray(40))
        val link = File(dir, "link").toPath()
        try {
            java.nio.file.Files.createSymbolicLink(link, File(dir, "real").toPath())
        } catch (e: Exception) {
            return // il sistema non permette i collegamenti: niente da verificare
        }
        assertEquals(40L, FileSizes.of(dir))
    }
}
