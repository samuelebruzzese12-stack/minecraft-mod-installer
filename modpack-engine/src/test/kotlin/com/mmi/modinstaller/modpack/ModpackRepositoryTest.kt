package com.mmi.modinstaller.modpack

import com.mmi.modinstaller.core.DocumentStore
import com.mmi.modinstaller.core.MemoryDocumentStore
import com.mmi.modinstaller.jar.ModLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModpackRepositoryTest {

    private fun record(id: String, profile: String = "p-$id", complete: Boolean = true, problems: List<String> = emptyList()) = ModpackRecord(
        id = id, name = "Pacchetto $id", versionId = "1.0.0", minecraft = "1.20.1", loader = ModLoader.FABRIC, loaderVersion = "0.15.7",
        profileId = profile, archiveName = "$id.mrpack", totalFiles = 10, placedFiles = if (complete) 10 else 8, complete = complete,
        problems = problems, createdAt = 100, updatedAt = 200
    )

    @Test fun addsListsAndFindsRecords() {
        val repo = ModpackRepository(MemoryDocumentStore())
        assertTrue(repo.list().isEmpty())
        assertTrue(repo.add(record("a")))
        assertTrue(repo.add(record("b", complete = false, problems = listOf("mods/x.jar: 404"))))
        assertEquals(listOf("a", "b"), repo.list().map { it.id })
        assertEquals("Pacchetto a 1.0.0", repo.get("a")!!.title)
        assertEquals(listOf("mods/x.jar: 404"), repo.get("b")!!.problems)
        assertFalse(repo.get("b")!!.complete)
        assertEquals("b", repo.forProfile("p-b")!!.id)
        assertNull(repo.get("zzz"))
        assertNull(repo.forProfile("zzz"))
    }

    @Test fun everyFieldSurvivesAStoreRoundTrip() {
        val store = MemoryDocumentStore()
        val r = record("a").copy(versionId = null, loaderVersion = null, loader = ModLoader.NEOFORGE)
        ModpackRepository(store).add(r)
        assertEquals(r, ModpackRepository(store).get("a"))
    }

    @Test fun addingTheSameIdReplacesTheRecord() {
        val repo = ModpackRepository(MemoryDocumentStore())
        repo.add(record("a"))
        repo.add(record("a", complete = false))
        assertEquals(1, repo.list().size)
        assertFalse(repo.get("a")!!.complete)
    }

    @Test fun updateChangesOnlyTheNamedRecordAndKeepsItsId() {
        val repo = ModpackRepository(MemoryDocumentStore())
        repo.add(record("a", complete = false))
        repo.add(record("b"))
        assertTrue(repo.update("a") { it.copy(id = "altro", complete = true, placedFiles = 10, updatedAt = 999) })
        val a = repo.get("a")!!
        assertTrue(a.complete)
        assertEquals(999, a.updatedAt)
        assertNull(repo.get("altro"))
        assertEquals(2, repo.list().size)
        assertTrue(repo.update("non-esiste") { it.copy(complete = false) })
        assertEquals(2, repo.list().size)
    }

    @Test fun removeTakesOutOnlyThatRecord() {
        val repo = ModpackRepository(MemoryDocumentStore())
        repo.add(record("a"))
        repo.add(record("b"))
        assertTrue(repo.remove("a"))
        assertEquals(listOf("b"), repo.list().map { it.id })
        assertTrue(repo.remove("a"))
    }

    @Test fun damagedEntriesAreSkippedWithoutLosingTheGoodOnes() {
        val store = MemoryDocumentStore()
        store.write(
            ModpackRepository.SECTION,
            mapOf(
                "version" to 1,
                "items" to listOf(
                    "non è un oggetto",
                    mapOf("id" to "senza-loader", "minecraft" to "1.20.1", "profileId" to "p", "archiveName" to "x.mrpack"),
                    mapOf("id" to "senza-profilo", "loader" to "fabric", "minecraft" to "1.20.1", "archiveName" to "x.mrpack"),
                    mapOf("id" to "buono", "loader" to "fabric", "minecraft" to "1.20.1", "profileId" to "p", "archiveName" to "b.mrpack")
                )
            )
        )
        val repo = ModpackRepository(store)
        assertEquals(listOf("buono"), repo.list().map { it.id })
        // Aggiungere un record non butta via le voci che non riesce a leggere.
        assertTrue(repo.add(record("nuovo")))
        assertEquals(listOf("buono", "nuovo"), repo.list().map { it.id })
    }

    @Test fun anUnrelatedSectionIsNeverTouched() {
        val store = MemoryDocumentStore()
        store.write("java_profiles", mapOf("profiles" to listOf<Any?>()))
        val repo = ModpackRepository(store)
        repo.add(record("a"))
        repo.remove("a")
        assertEquals(mapOf("profiles" to listOf<Any?>()), store.read("java_profiles"))
    }

    @Test fun veryLongProblemListsAreCappedButTheCountOfMissingFilesIsKept() {
        val repo = ModpackRepository(MemoryDocumentStore())
        val many = (1..150).map { "mods/m$it.jar: " + "x".repeat(600) }
        repo.add(record("a", complete = false, problems = many).copy(totalFiles = 150, placedFiles = 0))
        val back = repo.get("a")!!
        assertEquals(ModpackRepository.MAX_PROBLEMS, back.problems.size)
        assertTrue(back.problems.all { it.length <= 400 })
        assertEquals(150, back.totalFiles)
        assertEquals(0, back.placedFiles)
    }

    @Test fun aFailedSaveLeavesTheStoreAsItWas() {
        val inner = MemoryDocumentStore()
        var failing = false
        val store = object : DocumentStore {
            override fun read(section: String): Any? = inner.read(section)
            override fun write(section: String, value: Any?): Boolean = inner.write(section, value)
            override fun update(section: String, transform: (Any?) -> Any?): Boolean {
                if (failing) {
                    inner.write(section, transform(inner.read(section))) // la copia in memoria cambia, il disco no
                    return false
                }
                return inner.update(section, transform)
            }
        }
        val repo = ModpackRepository(store)
        assertTrue(repo.add(record("a")))
        failing = true
        assertFalse(repo.add(record("b")))
        assertFalse(repo.remove("a"))
        failing = false
        assertEquals(listOf("a"), repo.list().map { it.id })
    }
}
