package com.mmi.modinstaller.mods

import com.mmi.modinstaller.core.MemoryDocumentStore
import com.mmi.modinstaller.jar.ContentKind
import com.mmi.modinstaller.jar.DependencyKind
import com.mmi.modinstaller.jar.ModEnvironment
import com.mmi.modinstaller.jar.ModLoader
import com.mmi.modinstaller.jar.RangeSyntax
import com.mmi.modinstaller.mods.Fx.dep
import com.mmi.modinstaller.mods.Fx.mod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRepositoryTest {

    private val p1 = Profile("p1", "Survival", "1.20.1", ModLoader.FABRIC, "0.15.7", 17, 1000L)
    private val p2 = Profile("p2", "Test", "1.21.1", ModLoader.NEOFORGE, null, null, 2000L)

    @Test fun firstProfileBecomesActiveAndCanBeChanged() {
        val repo = ProfileRepository(MemoryDocumentStore())
        assertTrue(repo.list().isEmpty())
        assertNull(repo.activeId())
        assertTrue(repo.add(p1))
        assertTrue(repo.add(p2))
        assertEquals(listOf(p1, p2), repo.list())
        assertEquals("p1", repo.activeId())
        assertTrue(repo.setActive("p2"))
        assertEquals("p2", repo.activeId())
        assertTrue(repo.setActive("nope"))
        assertEquals("p2", repo.activeId())
        assertEquals(p2, repo.get("p2"))
        assertNull(repo.get("nope"))
    }

    @Test fun removingTheActiveProfileMovesTheActiveOne() {
        val repo = ProfileRepository(MemoryDocumentStore())
        repo.add(p1)
        repo.add(p2)
        repo.remove("p1")
        assertEquals(listOf(p2), repo.list())
        assertEquals("p2", repo.activeId())
        repo.remove("p2")
        assertNull(repo.activeId())
    }

    @Test fun updateChangesOnlyTheProfileFieldsNotTheItems() {
        val repo = ProfileRepository(MemoryDocumentStore())
        repo.add(p1)
        repo.modify("p1") { items, _ -> items.add(Fx.item("a", listOf(mod("a")))) }
        repo.update("p1") { it.copy(name = "Nuovo", loaderVersion = null, javaMajor = 21, id = "x") }
        val u = repo.get("p1")!!
        assertEquals("Nuovo", u.name)
        assertNull(u.loaderVersion)
        assertEquals(21, u.javaMajor)
        assertEquals(1, repo.items("p1").size)
    }

    @Test fun itemsAndJournalSurviveARoundTripThroughTheStore() {
        val store = MemoryDocumentStore()
        val repo = ProfileRepository(store)
        repo.add(p1)
        val full = mod(
            "sodium", "0.5.3", name = "Sodium", env = ModEnvironment.CLIENT, provides = listOf("indium"),
            deps = listOf(
                dep("minecraft", "~1.20.1"),
                dep("fabric-api", ">=0.90", kind = DependencyKind.RECOMMENDED, reason = "più veloce"),
                dep("oldmod", "<1.0", kind = DependencyKind.BREAKS, syntax = RangeSyntax.MAVEN, side = "BOTH")
            )
        )
        val item = Fx.item("a", listOf(full), nested = listOf(mod("lib", "2.0.0")), javaMajor = 17).copy(sourceSha256 = "orig", note = "n")
        val pack = Fx.item("b", category = ItemCategory.RESOURCE_PACK, kind = ContentKind.RESOURCE_PACK, packFormat = 15, enabled = false)
        val entry = JournalEntry(
            "j1", 5L, "Installato",
            listOf(JournalStep.Removed(pack, "b.zip"), JournalStep.Added(item), JournalStep.Toggled("b", true))
        )
        assertTrue(repo.modify("p1") { items, journal ->
            items.add(item)
            items.add(pack)
            journal.add(entry)
        })
        val again = ProfileRepository(store)
        assertEquals(listOf(item, pack), again.items("p1"))
        assertEquals(listOf(entry), again.journal("p1"))
    }

    @Test fun nestedModsAreStoredWithoutTheirDependencies() {
        val repo = ProfileRepository(MemoryDocumentStore())
        repo.add(p1)
        val nested = mod("lib", "2.0.0", deps = listOf(dep("x")))
        repo.modify("p1") { items, _ -> items.add(Fx.item("a", listOf(mod("a")), nested = listOf(nested))) }
        val back = repo.items("p1").single().nested.single()
        assertEquals("lib", back.id)
        assertTrue(back.dependencies.isEmpty())
    }

    @Test fun garbageInTheStoreNeverCrashes() {
        for (junk in listOf<Any?>("testo", 42, true, listOf(1, 2, 3), mapOf("profiles" to "no"), mapOf("profiles" to listOf("a", 1, null)))) {
            val store = MemoryDocumentStore()
            store.write(ProfileRepository.SECTION, junk)
            val repo = ProfileRepository(store)
            assertTrue(repo.list().isEmpty())
            assertNull(repo.activeId())
            assertTrue(repo.items("p1").isEmpty())
            assertTrue(repo.journal("p1").isEmpty())
            assertTrue(repo.add(p1))
            assertEquals(listOf(p1), repo.list())
        }
    }

    @Test fun brokenEntriesAreSkippedAndTheRestIsKept() {
        val store = MemoryDocumentStore()
        store.write(
            ProfileRepository.SECTION,
            mapOf(
                "version" to 1,
                "active" to "p1",
                "profiles" to listOf(
                    mapOf("id" to "p1", "name" to "Ok", "minecraft" to "1.20.1", "loader" to "fabric", "createdAt" to 1,
                        "items" to listOf(
                            mapOf("id" to "i1", "category" to "mod", "fileName" to "a.jar"),
                            mapOf("id" to "i2", "category" to "boh", "fileName" to "b.jar"),
                            mapOf("category" to "mod", "fileName" to "c.jar"),
                            "stringa"
                        ),
                        "journal" to listOf(
                            mapOf("id" to "j1", "time" to 1, "label" to "ok", "steps" to listOf(mapOf("t" to "toggled", "id" to "i1", "was" to true))),
                            mapOf("id" to "j2", "time" to 1, "label" to "rotto", "steps" to listOf(mapOf("t" to "magia")))
                        )),
                    mapOf("id" to "p2", "name" to "Senza loader", "minecraft" to "1.20.1"),
                    mapOf("id" to "p3", "name" to "Loader strano", "minecraft" to "1.20.1", "loader" to "liteloader"),
                    mapOf("name" to "Senza id", "minecraft" to "1.20.1", "loader" to "fabric")
                )
            )
        )
        val repo = ProfileRepository(store)
        assertEquals(listOf("p1"), repo.list().map { it.id })
        assertEquals(listOf("i1"), repo.items("p1").map { it.id })
        assertEquals(listOf("j1"), repo.journal("p1").map { it.id })
        assertTrue(repo.items("p1").single().enabled)
    }

    @Test fun failedSaveLeavesTheInMemoryCopyAsBefore() {
        val store = Fx.FlakyStore()
        val repo = ProfileRepository(store)
        assertTrue(repo.add(p1))
        store.failWrites = true
        assertFalse(repo.add(p2))
        assertEquals(listOf(p1), repo.list())
        assertFalse(repo.modify("p1") { items, _ -> items.add(Fx.item("a")) })
        assertTrue(repo.items("p1").isEmpty())
        assertFalse(repo.remove("p1"))
        assertEquals(listOf(p1), repo.list())
        assertFalse(repo.setActive("p1"))
        store.failWrites = false
        assertTrue(repo.modify("p1") { items, _ -> items.add(Fx.item("a")) })
        assertEquals(1, repo.items("p1").size)
    }

    @Test fun modifyOnAnUnknownProfileDoesNothing() {
        val repo = ProfileRepository(MemoryDocumentStore())
        repo.add(p1)
        var called = false
        assertFalse(repo.modify("zzz") { _, _ -> called = true })
        assertFalse(called)
    }

    @Test fun theSectionIsVersioned() {
        val store = MemoryDocumentStore()
        ProfileRepository(store).add(p1)
        @Suppress("UNCHECKED_CAST")
        val section = store.read(ProfileRepository.SECTION) as Map<String, Any?>
        assertEquals(ProfileRepository.FORMAT_VERSION, section["version"])
    }
}
