package com.mmi.modinstaller.core

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LibraryTest {
    private lateinit var dir: File
    private lateinit var file: File
    private var now = 10_000L

    @Before
    fun setUp() {
        dir = T.tmpDir()
        file = File(dir, "state/library.json")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun store() = LibraryStore(file) { now++ }

    private fun entry(id: String, uuid: String = T.uuidOf(id), version: String = "1.0.0", state: InstallState = InstallState.SENT) =
        InstalledEntry(
            id = id, title = "Titolo $id", kind = ContentKind.ADDON.name, version = version, author = "Autore",
            packs = listOf(PackRef(uuid, "Pack $id", version)), fileName = "$id.mcaddon", sizeBytes = 1234,
            source = "$id.zip", sentAt = 1, state = state, stateAt = 2
        )

    @Test
    fun persistsEverythingAcrossRestarts() {
        val s = store()
        s.addRecent(RecentFile("content://a/1", "uno.mcpack", 100, 5))
        s.putInstalled(entry("a"))
        s.addHistory("Titolo a", "Inviato a Minecraft", "In attesa di conferma", "dettaglio")
        val s2 = store()
        assertEquals(1, s2.recents().size)
        assertEquals("uno.mcpack", s2.recents()[0].name)
        assertEquals(entry("a"), s2.installed()[0])
        assertEquals("dettaglio", s2.history()[0].detail)
        assertFalse(s2.recoveredFromCorruption)
    }

    @Test
    fun recentsAreDeduplicatedAndLimited() {
        val s = store()
        s.addRecent(RecentFile("u1", "a.mcpack", 10, 1))
        s.addRecent(RecentFile("u2", "b.mcpack", 20, 2))
        s.addRecent(RecentFile("u3", "a.mcpack", 10, 3)) // stesso nome e dimensione di u1
        assertEquals(listOf("u3", "u2"), s.recents().map { it.uri })
        for (i in 0 until 50) s.addRecent(RecentFile("x$i", "f$i", i.toLong(), i.toLong()))
        assertEquals(LibraryStore.MAX_RECENTS, s.recents().size)
        assertEquals("x49", s.recents()[0].uri)
        s.removeRecent("x49")
        assertEquals("x48", s.recents()[0].uri)
        s.clearRecents()
        assertTrue(s.recents().isEmpty())
    }

    @Test
    fun installStateChangesAreRecorded() {
        val s = store()
        s.putInstalled(entry("a"))
        val updated = s.setInstallState("a", InstallState.CONFIRMED)
        assertNotNull(updated)
        assertEquals(InstallState.CONFIRMED, store().findInstalled("a")!!.state)
        assertNull(s.setInstallState("nope", InstallState.FAILED))
        val removed = s.removeInstalled("a")
        assertEquals("a", removed!!.id)
        assertTrue(store().installed().isEmpty())
        assertNull(s.removeInstalled("a"))
    }

    @Test
    fun newestInstalledFirstAndReplacedById() {
        val s = store()
        s.putInstalled(entry("a"))
        s.putInstalled(entry("b"))
        s.putInstalled(entry("a", version = "2.0.0"))
        assertEquals(listOf("a", "b"), s.installed().map { it.id })
        assertEquals("2.0.0", s.installed()[0].version)
    }

    @Test
    fun knownPacksOnlyCountConfirmedImports() {
        val u = T.uuidOf("shared")
        val s = store()
        s.putInstalled(entry("sent", uuid = u, version = "1.0.0", state = InstallState.SENT))
        s.putInstalled(entry("failed", uuid = u, version = "1.5.0", state = InstallState.FAILED))
        assertNull(s.knownPacks().lookup(u))
        s.putInstalled(entry("ok1", uuid = u, version = "1.1.0", state = InstallState.CONFIRMED))
        s.putInstalled(entry("ok2", uuid = u, version = "1.2.0", state = InstallState.CONFIRMED))
        val k = s.knownPacks().lookup(u.uppercase())!!
        assertEquals("1.2.0", k.versionText)
        assertEquals(McVersion(listOf(1, 2, 0)), k.version)
        assertNull(s.knownPacks().lookup(T.uuidOf("other")))
    }

    @Test
    fun historyIsNewestFirstAndBounded() {
        val s = store()
        for (i in 0 until (LibraryStore.MAX_HISTORY + 20)) s.addHistory("t$i", "a", "r")
        assertEquals(LibraryStore.MAX_HISTORY, s.history().size)
        assertEquals("t${LibraryStore.MAX_HISTORY + 19}", s.history()[0].title)
        assertTrue(s.history()[0].at > s.history()[1].at)
        s.clearHistory()
        assertTrue(store().history().isEmpty())
    }

    @Test
    fun corruptFileIsSetAsideNotFatal() {
        file.parentFile.mkdirs()
        file.writeText("{ questo non è json")
        val s = store()
        assertTrue(s.recoveredFromCorruption)
        assertTrue(s.installed().isEmpty())
        assertTrue(File(file.parentFile, "library.json.corrupt").exists())
        s.addHistory("x", "a", "r")
        assertEquals(1, store().history().size)
    }

    @Test
    fun brokenEntriesAreSkipped() {
        file.parentFile.mkdirs()
        file.writeText(
            """{"version":1,"recents":[{"name":"senza uri"},{"uri":"ok","name":"valido","size":3,"at":1}],
               "installed":[{"title":"senza id"},{"id":"x","title":"ok","state":"STRANA"}],"history":[5,{"title":"h"}]}"""
        )
        val s = store()
        assertEquals(listOf("ok"), s.recents().map { it.uri })
        assertEquals(InstallState.SENT, s.installed().single().state)
        assertEquals(1, s.history().size)
    }

    @Test
    fun searchIgnoresCaseAndAccents() {
        assertTrue(TextSearch.matches("citta", "La Città Perduta"))
        assertTrue(TextSearch.matches("PERDUTA citta", "La Città Perduta"))
        assertTrue(TextSearch.matches("addon", null, "Il mio Addon"))
        assertFalse(TextSearch.matches("castello", "La Città Perduta", "mappa"))
        assertTrue(TextSearch.matches("   ", "qualsiasi"))
    }

    @Test
    fun launchPlanOrdersAttempts() {
        val plan = LaunchPlanner.plan(listOf("com.mojang.minecraftpe", "com.mojang.minecraftpe.preview")) { pkg ->
            if (pkg.endsWith("preview")) "x.Y" else null
        }
        assertEquals(
            listOf(LaunchMode.EXPLICIT_COMPONENT, LaunchMode.EXPLICIT_COMPONENT, LaunchMode.PACKAGE_ONLY, LaunchMode.PACKAGE_ONLY, LaunchMode.CHOOSER),
            plan.map { it.mode }
        )
        assertEquals(LaunchPlanner.MINECRAFT_ACTIVITY, plan[0].className)
        assertEquals("x.Y", plan[1].className)
        assertNull(plan.last().packageName)
        val none = LaunchPlanner.plan(emptyList())
        assertEquals(listOf(LaunchMode.CHOOSER), none.map { it.mode })
    }
}
