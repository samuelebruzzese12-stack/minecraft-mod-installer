package com.mmi.modinstaller.mods

import com.mmi.modinstaller.core.MemoryDocumentStore
import com.mmi.modinstaller.jar.ContentKind
import com.mmi.modinstaller.jar.DependencyKind
import com.mmi.modinstaller.jar.JarReport
import com.mmi.modinstaller.jar.ModLoader
import com.mmi.modinstaller.mods.Fx.dep
import com.mmi.modinstaller.mods.Fx.fabricDeps
import com.mmi.modinstaller.mods.Fx.mod
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile

class ModManagerTest {

    private lateinit var base: File
    private lateinit var root: File
    private lateinit var src: File
    private lateinit var store: Fx.FlakyStore
    private lateinit var repo: ProfileRepository
    private lateinit var mm: ModManager
    private var counter = 0
    private lateinit var pid: String

    @Before fun setUp() {
        base = Fx.tempDir()
        root = File(base, "profiles")
        src = File(base, "src").also { it.mkdirs() }
        store = Fx.FlakyStore(MemoryDocumentStore())
        repo = ProfileRepository(store)
        counter = 0
        mm = ModManager(repo, root, clock = { 1_000L + counter }, newId = { "id" + (++counter) }, maxJournal = 5)
        val created = mm.createProfile(ProfileDraft("Survival", "1.20.1", ModLoader.FABRIC, "0.15.7"))
        pid = (created as Outcome.Ok).value.id
    }

    @After fun tearDown() {
        base.deleteRecursively()
    }

    // ------------------------------------------------------------------ strumenti

    private fun sourceFile(name: String, content: String): File = Fx.file(src, name, content.toByteArray())

    /** Un file mod vero (contenuto qualsiasi) con la sua descrizione. */
    private fun modFile(id: String, version: String, content: String = "contenuto $id $version", extra: List<com.mmi.modinstaller.jar.ModDependency> = emptyList()): Pair<File, JarReport> {
        val f = sourceFile("$id-$version.jar", content)
        val r = Fx.report(f.name, mods = listOf(mod(id, version, deps = fabricDeps(extra))), sha256 = Fx.sha256(f.readBytes()))
        return f to r
    }

    private fun install(pair: Pair<File, JarReport>, confirmed: Boolean = false): InstallResult = mm.install(pid, pair.first, pair.second, confirmed)

    private fun installed(r: InstallResult): InstallResult.Installed {
        assertTrue(r.toString(), r is InstallResult.Installed)
        return r as InstallResult.Installed
    }

    private fun modsDir() = File(root, "$pid/mods")

    private fun listing(dir: File = modsDir()): List<String> = dir.list()?.sorted().orEmpty()

    private fun trashEntries(): List<String> = File(root, "$pid/.trash").list()?.sorted().orEmpty()

    // ------------------------------------------------------------------ profili

    @Test fun createProfileBuildsTheFoldersAndBecomesActive() {
        for (c in ItemCategory.values()) assertTrue(c.folder, File(root, "$pid/${c.folder}").isDirectory)
        assertTrue(File(root, "$pid/.trash").isDirectory)
        assertEquals(pid, mm.activeProfile()!!.id)
        assertEquals("Survival", mm.profile(pid)!!.name)
    }

    @Test fun profileDraftIsValidated() {
        fun fail(draft: ProfileDraft): String = (mm.createProfile(draft) as Outcome.Fail).message
        assertTrue(fail(ProfileDraft("  ", "1.20.1", ModLoader.FABRIC)).contains("nome"))
        assertTrue(fail(ProfileDraft("survival", "1.20.1", ModLoader.FABRIC)).contains("già un profilo"))
        assertTrue(fail(ProfileDraft("A", "1.20.1; rm -rf /", ModLoader.FABRIC)).contains("Minecraft"))
        assertTrue(fail(ProfileDraft("A", "", ModLoader.FABRIC)).contains("Minecraft"))
        assertTrue(fail(ProfileDraft("A", "1.20.1", ModLoader.FABRIC, "../x")).contains("caricatore"))
        assertTrue(fail(ProfileDraft("A", "1.20.1", ModLoader.FABRIC, null, 3)).contains("Java"))
        assertTrue(fail(ProfileDraft("x".repeat(80), "1.20.1", ModLoader.FABRIC)).contains("nome"))
        assertEquals(1, mm.profiles().size)
        val ok = mm.createProfile(ProfileDraft("  Mio   profilo ", " 1.21.1 ", ModLoader.NEOFORGE, " ", 21))
        val p = (ok as Outcome.Ok).value
        assertEquals("Mio profilo", p.name)
        assertEquals("1.21.1", p.minecraft)
        assertNull(p.loaderVersion)
        assertEquals(21, p.effectiveJava)
        assertEquals(2, mm.profiles().size)
    }

    @Test fun editProfileKeepsTheItemsAndChecksNames() {
        installed(install(modFile("sodium", "0.5.3")))
        mm.createProfile(ProfileDraft("Altro", "1.20.1", ModLoader.FABRIC))
        val edited = mm.editProfile(pid, ProfileDraft("Rinominato", "1.20.4", ModLoader.FABRIC, "0.15.11")) as Outcome.Ok
        assertEquals("Rinominato", edited.value.name)
        assertEquals("1.20.4", mm.profile(pid)!!.minecraft)
        assertEquals(1, mm.items(pid).size)
        assertTrue(mm.editProfile(pid, ProfileDraft("altro", "1.20.1", ModLoader.FABRIC)) is Outcome.Fail)
        assertTrue(mm.editProfile(pid, ProfileDraft("Rinominato", "1.20.1", ModLoader.QUILT)) is Outcome.Ok)
        assertTrue(mm.editProfile("nope", ProfileDraft("Z", "1.20.1", ModLoader.FABRIC)) is Outcome.Fail)
    }

    @Test fun deleteProfileRemovesFilesAndStoreEntry() {
        installed(install(modFile("sodium", "0.5.3")))
        val other = (mm.createProfile(ProfileDraft("Altro", "1.20.1", ModLoader.FABRIC)) as Outcome.Ok).value
        assertTrue(mm.deleteProfile(pid) is Outcome.Ok)
        assertFalse(File(root, pid).exists())
        assertNull(mm.profile(pid))
        assertEquals(other.id, mm.activeProfile()!!.id)
        assertTrue(File(root, other.id).isDirectory)
        assertTrue(mm.deleteProfile(pid) is Outcome.Fail)
    }

    @Test fun setActiveChangesTheActiveProfile() {
        val other = (mm.createProfile(ProfileDraft("Altro", "1.20.1", ModLoader.FABRIC)) as Outcome.Ok).value
        assertEquals(pid, mm.activeProfile()!!.id)
        assertTrue(mm.setActive(other.id) is Outcome.Ok)
        assertEquals(other.id, mm.activeProfile()!!.id)
        assertTrue(mm.setActive("nope") is Outcome.Fail)
    }

    // ------------------------------------------------------------------ installazione

    @Test fun installCopiesTheFileVerifiesItAndRecordsIt() {
        val pair = modFile("sodium", "0.5.3")
        val r = installed(install(pair))
        assertEquals(listOf("sodium-0.5.3.jar"), listing())
        assertEquals("contenuto sodium 0.5.3", File(modsDir(), "sodium-0.5.3.jar").readText())
        assertEquals(pair.second.sha256, r.item.sha256)
        assertEquals(File(modsDir(), "sodium-0.5.3.jar").length(), r.item.sizeBytes)
        assertNull(r.item.sourceSha256)
        assertTrue(r.item.enabled)
        assertEquals("sodium", r.item.mods.single().id)
        assertEquals(listOf(r.item), mm.items(pid))
        val j = mm.journal(pid).single()
        assertTrue(j.label.contains("Installato"))
        assertTrue(j.steps.single() is JournalStep.Added)
        assertTrue(src.resolve("sodium-0.5.3.jar").exists()) // l'originale non si tocca
    }

    @Test fun installingTheSameFileAgainDoesNothing() {
        val pair = modFile("sodium", "0.5.3")
        installed(install(pair))
        val again = install(pair)
        assertTrue(again is InstallResult.AlreadyInstalled)
        assertEquals(1, mm.items(pid).size)
        assertEquals(1, mm.journal(pid).size)
        assertEquals(1, listing().size)
    }

    @Test fun blockedFilesAreNeverCopied() {
        val f = sourceFile("forge.jar", "x")
        val r = Fx.report("forge.jar", mods = listOf(mod("m", loader = ModLoader.FORGE)), sha256 = Fx.sha256(f.readBytes()))
        val res = mm.install(pid, f, r, confirmed = true)
        assertTrue(res is InstallResult.NotAllowed)
        assertEquals(PlanDecision.BLOCKED, (res as InstallResult.NotAllowed).plan.decision)
        assertTrue(listing().isEmpty())
        assertTrue(mm.items(pid).isEmpty())
        assertTrue(mm.journal(pid).isEmpty())
    }

    @Test fun filesNeedingConfirmationAreInstalledOnlyWhenConfirmed() {
        val pair = modFile("iris", "1.7.0", extra = listOf(dep("fabric-api")))
        val first = install(pair, confirmed = false)
        assertTrue(first is InstallResult.NotAllowed)
        assertTrue(listing().isEmpty())
        val second = installed(install(pair, confirmed = true))
        assertEquals(1, mm.items(pid).size)
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, second.plan.decision)
    }

    @Test fun aFileThatChangedAfterTheAnalysisIsRefusedWithoutLeavingTraces() {
        val pair = modFile("sodium", "0.5.3")
        pair.first.writeText("contenuto diverso")
        val r = install(pair)
        assertTrue(r.toString(), r is InstallResult.Failed)
        assertTrue((r as InstallResult.Failed).message.contains("cambiato"))
        assertTrue(listing().isEmpty())
        assertTrue(mm.items(pid).isEmpty())
    }

    @Test fun aMissingSourceFileIsReported() {
        val pair = modFile("sodium", "0.5.3")
        pair.first.delete()
        assertTrue(install(pair) is InstallResult.Failed)
    }

    @Test fun installIntoAnUnknownProfileFails() {
        val pair = modFile("sodium", "0.5.3")
        assertTrue(mm.install("nope", pair.first, pair.second, true) is InstallResult.Failed)
    }

    @Test fun updatePutsTheOldFileInTheTrashAndUndoBringsItBack() {
        val v1 = installed(install(modFile("sodium", "0.5.2")))
        val up = installed(install(modFile("sodium", "0.5.3")))
        assertEquals(v1.item.id, up.replaced!!.id)
        assertEquals(listOf("sodium-0.5.3.jar"), listing())
        assertEquals(listOf("sodium"), mm.items(pid).map { it.mods.single().id })
        assertEquals("0.5.3", mm.items(pid).single().mods.single().version)
        val entry = mm.journal(pid).last()
        assertTrue(entry.label.contains("Aggiornato"))
        assertEquals(2, entry.steps.size)
        assertEquals(listOf(entry.id), trashEntries())
        assertEquals("contenuto sodium 0.5.2", File(root, "$pid/.trash/${entry.id}/sodium-0.5.2.jar").readText())

        val undone = mm.undo(pid) as Outcome.Ok
        assertTrue(undone.value.contains("Aggiornato"))
        assertEquals(listOf("sodium-0.5.2.jar"), listing())
        assertEquals("0.5.2", mm.items(pid).single().mods.single().version)
        assertEquals(v1.item, mm.items(pid).single())
        assertEquals(1, mm.journal(pid).size)
        assertTrue(trashEntries().isEmpty())
    }

    @Test fun undoIsLastInFirstOut() {
        installed(install(modFile("a", "1.0.0")))
        installed(install(modFile("b", "1.0.0")))
        assertEquals(2, mm.items(pid).size)
        assertTrue(mm.undo(pid) is Outcome.Ok)
        assertEquals(listOf("a-1.0.0.jar"), listing())
        assertTrue(mm.undo(pid) is Outcome.Ok)
        assertTrue(listing().isEmpty())
        assertTrue(mm.items(pid).isEmpty())
        val nothing = mm.undo(pid)
        assertTrue(nothing is Outcome.Fail)
    }

    @Test fun anInstallThatCannotBeSavedLeavesEverythingAsBefore() {
        val v1 = installed(install(modFile("sodium", "0.5.2")))
        store.failWrites = true
        val r = install(modFile("sodium", "0.5.3"))
        assertTrue(r.toString(), r is InstallResult.Failed)
        store.failWrites = false
        assertEquals(listOf("sodium-0.5.2.jar"), listing())
        assertEquals(listOf(v1.item), mm.items(pid))
        assertEquals(1, mm.journal(pid).size)
        assertTrue(trashEntries().isEmpty() || trashEntries().all { File(root, "$pid/.trash/$it").list().isNullOrEmpty() })
        // e dopo, a disco di nuovo sano, si può installare
        installed(install(modFile("sodium", "0.5.3")))
        assertEquals(listOf("sodium-0.5.3.jar"), listing())
    }

    @Test fun cancellingStopsWithoutLeavingFiles() {
        val big = ByteArray(300 * 1024) { (it % 251).toByte() }
        val f = Fx.file(src, "big.jar", big)
        val r = Fx.report("big.jar", mods = listOf(mod("big", deps = fabricDeps())), sha256 = Fx.sha256(big))
        var calls = 0
        try {
            mm.install(pid, f, r, true, cancel = { if (++calls >= 2) throw CancellationException("stop") })
            fail("doveva interrompersi")
        } catch (e: CancellationException) {
            // atteso
        }
        assertTrue(listing().toString(), listing().isEmpty())
        assertTrue(mm.items(pid).isEmpty())
        assertTrue(mm.journal(pid).isEmpty())
    }

    @Test fun progressIsReportedWhileCopying() {
        val big = ByteArray(200 * 1024) { 7 }
        val f = Fx.file(src, "big.jar", big)
        val r = Fx.report("big.jar", mods = listOf(mod("big", deps = fabricDeps())), sha256 = Fx.sha256(big))
        val seen = ArrayList<Long>()
        installed(mm.install(pid, f, r, true, progress = { done, total -> seen.add(done); assertEquals(big.size.toLong(), total) }))
        assertTrue(seen.isNotEmpty())
        assertEquals(big.size.toLong(), seen.last())
        assertEquals(seen.sorted(), seen)
    }

    @Test fun anUnmanagedFileWithTheSameNameIsNeverOverwritten() {
        val stray = File(modsDir(), "sodium-0.5.3.jar")
        stray.writeText("MIO FILE")
        val strayOff = File(modsDir(), "altro-1.0.0.jar.disabled")
        strayOff.writeText("MIO FILE DISATTIVATO")
        val r = installed(install(modFile("sodium", "0.5.3")))
        assertEquals("sodium-0.5.3-2.jar", r.item.fileName)
        assertEquals("MIO FILE", stray.readText())
        val r2 = installed(install(modFile("altro", "1.0.0")))
        assertEquals("altro-1.0.0-2.jar", r2.item.fileName)
        assertEquals("MIO FILE DISATTIVATO", strayOff.readText())
    }

    @Test fun nameCollisionBetweenManagedFilesGetsASuffix() {
        installed(install(modFile("a", "1.0.0", content = "uno")))
        val f = sourceFile("a-1.0.0.jar", "due")
        val other = Fx.report("a-1.0.0.jar", mods = listOf(mod("b", "1.0.0", deps = fabricDeps())), sha256 = Fx.sha256("due".toByteArray()))
        val r = installed(mm.install(pid, f, other, true))
        assertEquals("a-1.0.0-2.jar", r.item.fileName)
        assertEquals(listOf("a-1.0.0-2.jar", "a-1.0.0.jar"), listing())
    }

    // ------------------------------------------------------------------ pacchetti

    private fun wrappedPack(name: String = "Wrapped.zip"): Pair<File, JarReport> {
        val f = Fx.zip(
            File(src, name),
            linkedMapOf(
                "MyPack/pack.mcmeta" to "{\"pack\":{\"pack_format\":15,\"description\":\"x\"}}",
                "MyPack/assets/minecraft/textures/a.png" to "PNG",
                "__MACOSX/MyPack/._pack.mcmeta" to "junk",
                "MyPack/.DS_Store" to "junk"
            )
        )
        val r = Fx.report(name, kind = ContentKind.RESOURCE_PACK, pack = Fx.packInfo(15, wrapper = "MyPack"), sha256 = Fx.sha256(f.readBytes()))
        return f to r
    }

    @Test fun wrappedPackIsRepackagedWithTheRightStructure() {
        val (f, report) = wrappedPack()
        val r = installed(mm.install(pid, f, report, true))
        val dest = File(root, "$pid/resourcepacks/Wrapped.zip")
        assertTrue(dest.isFile)
        ZipFile(dest).use { z ->
            val names = z.entries().toList().map { it.name }.sorted()
            assertEquals(listOf("assets/minecraft/textures/a.png", "pack.mcmeta"), names)
        }
        assertEquals(Fx.sha256(dest.readBytes()), r.item.sha256)
        assertNotEquals(report.sha256, r.item.sha256)
        assertEquals(report.sha256, r.item.sourceSha256)
        assertEquals(dest.length(), r.item.sizeBytes)
        // lo stesso file scelto di nuovo viene riconosciuto come già installato
        assertTrue(mm.install(pid, f, report, true) is InstallResult.AlreadyInstalled)
        assertEquals(1, mm.items(pid).size)
    }

    @Test fun repackagingIsDeterministic() {
        val (f, report) = wrappedPack()
        val a = installed(mm.install(pid, f, report, true)).item.sha256
        val other = ModManager(ProfileRepository(MemoryDocumentStore()), File(base, "p2"), clock = { 5L })
        val pid2 = ((other.createProfile(ProfileDraft("B", "1.20.1", ModLoader.FABRIC)) as Outcome.Ok).value).id
        val b = (other.install(pid2, f, report, true) as InstallResult.Installed).item.sha256
        assertEquals(a, b)
    }

    @Test fun packWithTheSameNameReplacesTheOldOneAfterConfirmationAndCanBeUndone() {
        val v1 = sourceFile("Faithful.zip", "uno")
        val r1 = Fx.report("Faithful.zip", kind = ContentKind.RESOURCE_PACK, pack = Fx.packInfo(15), sha256 = Fx.sha256(v1.readBytes()))
        installed(mm.install(pid, v1, r1, false))
        v1.writeText("due")
        val r2 = Fx.report("Faithful.zip", kind = ContentKind.RESOURCE_PACK, pack = Fx.packInfo(15), sha256 = Fx.sha256("due".toByteArray()))
        assertTrue(mm.install(pid, v1, r2, false) is InstallResult.NotAllowed)
        installed(mm.install(pid, v1, r2, true))
        assertEquals("due", File(root, "$pid/resourcepacks/Faithful.zip").readText())
        assertEquals(1, mm.items(pid).size)
        mm.undo(pid)
        assertEquals("uno", File(root, "$pid/resourcepacks/Faithful.zip").readText())
    }

    @Test fun packsGoToTheirOwnFolders() {
        val shader = sourceFile("BSL.zip", "s")
        val data = sourceFile("Caves.zip", "d")
        installed(mm.install(pid, shader, Fx.report("BSL.zip", kind = ContentKind.SHADER_PACK, pack = Fx.packInfo(null, shaders = true), sha256 = Fx.sha256(shader.readBytes())), true))
        installed(mm.install(pid, data, Fx.report("Caves.zip", kind = ContentKind.DATA_PACK, pack = Fx.packInfo(null, data = true), sha256 = Fx.sha256(data.readBytes())), true))
        assertEquals(listOf("BSL.zip"), listing(File(root, "$pid/shaderpacks")))
        assertEquals(listOf("Caves.zip"), listing(File(root, "$pid/datapacks")))
    }

    // ------------------------------------------------------------------ attiva, disattiva, rimuovi

    @Test fun disablingRenamesTheFileAndUndoRestoresIt() {
        val r = installed(install(modFile("sodium", "0.5.3")))
        val off = mm.setEnabled(pid, r.item.id, false) as Outcome.Ok
        assertFalse(off.value.enabled)
        assertEquals(listOf("sodium-0.5.3.jar.disabled"), listing())
        assertFalse(mm.items(pid).single().enabled)
        assertTrue(mm.journal(pid).last().label.contains("Disattivato"))
        // ripetere non cambia nulla e non scrive nella cronologia
        val journalSize = mm.journal(pid).size
        assertTrue(mm.setEnabled(pid, r.item.id, false) is Outcome.Ok)
        assertEquals(journalSize, mm.journal(pid).size)

        val on = mm.setEnabled(pid, r.item.id, true) as Outcome.Ok
        assertTrue(on.value.enabled)
        assertEquals(listOf("sodium-0.5.3.jar"), listing())
        assertTrue(mm.undo(pid) is Outcome.Ok) // annulla l'attivazione
        assertEquals(listOf("sodium-0.5.3.jar.disabled"), listing())
        assertTrue(mm.undo(pid) is Outcome.Ok) // annulla la disattivazione
        assertEquals(listOf("sodium-0.5.3.jar"), listing())
        assertTrue(mm.items(pid).single().enabled)
    }

    @Test fun disablingWhenTheStoreFailsPutsTheFileBack() {
        val r = installed(install(modFile("sodium", "0.5.3")))
        store.failWrites = true
        assertTrue(mm.setEnabled(pid, r.item.id, false) is Outcome.Fail)
        store.failWrites = false
        assertEquals(listOf("sodium-0.5.3.jar"), listing())
        assertTrue(mm.items(pid).single().enabled)
    }

    @Test fun enablingAFileWhoseFileIsGoneFails() {
        val r = installed(install(modFile("sodium", "0.5.3")))
        mm.setEnabled(pid, r.item.id, false)
        File(modsDir(), "sodium-0.5.3.jar.disabled").delete()
        val res = mm.setEnabled(pid, r.item.id, true)
        assertTrue(res is Outcome.Fail)
        assertFalse(mm.items(pid).single().enabled)
    }

    @Test fun removeMovesTheFileToTheTrashAndUndoBringsItBack() {
        val r = installed(install(modFile("sodium", "0.5.3")))
        assertTrue(mm.remove(pid, r.item.id) is Outcome.Ok)
        assertTrue(listing().isEmpty())
        assertTrue(mm.items(pid).isEmpty())
        assertEquals(1, trashEntries().size)
        assertTrue(mm.undo(pid) is Outcome.Ok)
        assertEquals(listOf("sodium-0.5.3.jar"), listing())
        assertEquals(r.item, mm.items(pid).single())
        assertTrue(trashEntries().isEmpty())
    }

    @Test fun removingADisabledFileRestoresItDisabled() {
        val r = installed(install(modFile("sodium", "0.5.3")))
        mm.setEnabled(pid, r.item.id, false)
        mm.remove(pid, r.item.id)
        assertTrue(listing().isEmpty())
        mm.undo(pid)
        assertEquals(listOf("sodium-0.5.3.jar.disabled"), listing())
        assertFalse(mm.items(pid).single().enabled)
    }

    @Test fun removingAFileAlreadyDeletedOutsideJustDropsTheEntry() {
        val r = installed(install(modFile("sodium", "0.5.3")))
        File(modsDir(), "sodium-0.5.3.jar").delete()
        val before = mm.journal(pid).size
        assertTrue(mm.remove(pid, r.item.id) is Outcome.Ok)
        assertTrue(mm.items(pid).isEmpty())
        assertEquals(before, mm.journal(pid).size)
    }

    @Test fun removeWhenTheStoreFailsPutsTheFileBack() {
        val r = installed(install(modFile("sodium", "0.5.3")))
        store.failWrites = true
        assertTrue(mm.remove(pid, r.item.id) is Outcome.Fail)
        store.failWrites = false
        assertEquals(listOf("sodium-0.5.3.jar"), listing())
        assertEquals(1, mm.items(pid).size)
    }

    @Test fun unknownItemsAreReported() {
        assertTrue(mm.remove(pid, "zzz") is Outcome.Fail)
        assertTrue(mm.setEnabled(pid, "zzz", false) is Outcome.Fail)
        assertTrue(mm.remove("nope", "zzz") is Outcome.Fail)
        assertTrue(mm.undo("nope") is Outcome.Fail)
    }

    @Test fun undoThatCannotBeCompletedChangesNothing() {
        installed(install(modFile("sodium", "0.5.2")))
        installed(install(modFile("sodium", "0.5.3")))
        val entry = mm.journal(pid).last()
        File(root, "$pid/.trash/${entry.id}").deleteRecursively() // il file messo da parte è sparito
        val before = mm.items(pid)
        val res = mm.undo(pid)
        assertTrue(res.toString(), res is Outcome.Fail)
        assertEquals(before, mm.items(pid))
        assertEquals(listOf("sodium-0.5.3.jar"), listing())
        assertEquals(entry.id, mm.journal(pid).last().id)
    }

    @Test fun undoWhenTheStoreFailsPutsEverythingBack() {
        installed(install(modFile("sodium", "0.5.2")))
        installed(install(modFile("sodium", "0.5.3")))
        store.failWrites = true
        assertTrue(mm.undo(pid) is Outcome.Fail)
        store.failWrites = false
        assertEquals(listOf("sodium-0.5.3.jar"), listing())
        assertEquals("0.5.3", mm.items(pid).single().mods.single().version)
        assertEquals(2, mm.journal(pid).size)
        assertTrue(mm.undo(pid) is Outcome.Ok)
        assertEquals(listOf("sodium-0.5.2.jar"), listing())
    }

    @Test fun theJournalIsCappedAndTheTrashOfDroppedEntriesIsPurged() {
        val items = (1..5).map { installed(install(modFile("m$it", "1.0.0"))).item }
        assertEquals(5, mm.journal(pid).size)
        mm.remove(pid, items[0].id) // 6a operazione: la più vecchia (installazione di m1) esce dalla cronologia
        assertEquals(5, mm.journal(pid).size)
        assertEquals(1, trashEntries().size)
        mm.remove(pid, items[1].id)
        mm.remove(pid, items[2].id)
        mm.remove(pid, items[3].id)
        mm.remove(pid, items[4].id)
        // restano 5 rimozioni: 5 cestini
        assertEquals(5, mm.journal(pid).size)
        assertEquals(5, trashEntries().size)
        installed(install(modFile("n1", "1.0.0"))) // la rimozione più vecchia esce: il suo cestino viene svuotato
        assertEquals(5, mm.journal(pid).size)
        assertEquals(4, trashEntries().size)
        val journalIds = mm.journal(pid).map { it.id }.toSet()
        assertTrue(trashEntries().all { it in journalIds })
    }

    // ------------------------------------------------------------------ riallineamento col disco

    @Test fun reconcileFollowsChangesMadeOutsideTheApp() {
        val a = installed(install(modFile("a", "1.0.0"))).item
        val b = installed(install(modFile("b", "1.0.0"))).item
        val c = installed(install(modFile("c", "1.0.0"))).item
        File(modsDir(), "a-1.0.0.jar").renameTo(File(modsDir(), "a-1.0.0.jar.disabled")) // disattivata a mano
        File(modsDir(), "b-1.0.0.jar").delete() // cancellata a mano
        File(modsDir(), "zzz.jar").writeText("sconosciuto")
        File(modsDir(), ".id99.part").writeText("avanzo")
        val rec = mm.reconcile(pid)!!
        assertEquals(listOf(a.id), rec.changedOutside.map { it.id })
        assertFalse(rec.changedOutside.single().enabled)
        assertEquals(listOf(b.id), rec.missing.map { it.id })
        assertEquals(listOf("mods/zzz.jar"), rec.strays)
        assertEquals(1, rec.tempFilesRemoved)
        assertTrue(rec.hasChanges)
        assertEquals(setOf(a.id, c.id), mm.items(pid).map { it.id }.toSet())
        assertFalse(mm.items(pid).first { it.id == a.id }.enabled)
        assertTrue(File(modsDir(), "zzz.jar").exists()) // i file sconosciuti non si toccano
        assertFalse(File(modsDir(), ".id99.part").exists())
        // una seconda volta non c'è più niente da correggere (a parte il file sconosciuto)
        val again = mm.reconcile(pid)!!
        assertFalse(again.hasChanges)
        assertEquals(listOf("mods/zzz.jar"), again.strays)
    }

    @Test fun reconcileRecoversAFileLeftInTheTrashByAnInterruptedOperation() {
        val a = installed(install(modFile("a", "1.0.0"))).item
        // simula l'interruzione: file nel cestino, elenco e cronologia non aggiornati
        val orphan = File(root, "$pid/.trash/interrotto")
        orphan.mkdirs()
        File(modsDir(), "a-1.0.0.jar").renameTo(File(orphan, "a-1.0.0.jar"))
        val rec = mm.reconcile(pid)!!
        assertEquals(listOf(a.id), rec.recovered.map { it.id })
        assertTrue(rec.missing.isEmpty())
        assertEquals(listOf("a-1.0.0.jar"), listing())
        assertFalse(orphan.exists())
        assertEquals(1, mm.items(pid).size)
    }

    @Test fun reconcileDeletesOrphanTrashThatBelongsToNothing() {
        installed(install(modFile("a", "1.0.0")))
        val orphan = File(root, "$pid/.trash/vecchio")
        orphan.mkdirs()
        File(orphan, "boh.jar").writeText("x")
        mm.reconcile(pid)
        assertFalse(orphan.exists())
        val entry = mm.journal(pid).single()
        mm.remove(pid, mm.items(pid).single().id)
        mm.reconcile(pid) // il cestino di un'operazione in cronologia resta
        assertEquals(1, trashEntries().size)
        assertNotNull(entry)
    }

    @Test fun reconcileOfAnUnknownProfileIsNull() {
        assertNull(mm.reconcile("nope"))
        assertNull(mm.check("nope"))
    }

    @Test fun checkReconcilesFirstAndThenChecksTheProfile() {
        installed(install(modFile("a", "1.0.0", extra = listOf(dep("fabric-api"))), confirmed = true))
        val gone = installed(install(modFile("b", "1.0.0"))).item
        File(modsDir(), "b-1.0.0.jar").delete()
        val (check, rec) = mm.check(pid)!!
        assertEquals(listOf(gone.id), rec.missing.map { it.id })
        assertEquals(PlanCode.DEP_MISSING, check.needsAttention.single().code)
        assertFalse(check.isClean)
    }

    @Test fun sizeOfSumsTheItems() {
        installed(install(modFile("a", "1.0.0", content = "12345")))
        installed(install(modFile("b", "1.0.0", content = "123")))
        assertEquals(8L, mm.sizeOf(pid))
    }

    @Test fun planDoesNotTouchAnything() {
        val pair = modFile("a", "1.0.0")
        val p = mm.plan(pid, pair.second)!!
        assertEquals(PlanDecision.READY, p.decision)
        assertTrue(listing().isEmpty())
        assertTrue(mm.items(pid).isEmpty())
        assertNull(mm.plan("nope", pair.second))
    }

    @Test fun updateOfADependencyIsCheckedAgainstTheModsThatNeedIt() {
        installed(install(modFile("lib", "2.5.0")))
        installed(install(modFile("user", "1.0.0", extra = listOf(dep("lib", ">=2.0.0 <3.0.0")))))
        val update = modFile("lib", "3.0.0")
        val blocked = install(update)
        assertTrue(blocked is InstallResult.NotAllowed)
        assertTrue((blocked as InstallResult.NotAllowed).plan.has(PlanCode.DEPENDENT_BROKEN))
        assertEquals("2.5.0", mm.items(pid).first { it.mods.first().id == "lib" }.mods.first().version)
    }

    @Test fun breaksBetweenInstalledModsBlocksTheInstall() {
        installed(install(modFile("old", "1.0.0")))
        val pair = modFile("new", "1.0.0", extra = listOf(dep("old", kind = DependencyKind.BREAKS)))
        assertTrue(install(pair, confirmed = true) is InstallResult.NotAllowed)
        assertEquals(1, mm.items(pid).size)
    }
}
