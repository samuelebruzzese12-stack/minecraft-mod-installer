package com.mmi.modinstaller.modpack

import com.mmi.modinstaller.core.DocumentStore
import com.mmi.modinstaller.core.asJsonObject
import com.mmi.modinstaller.core.bool
import com.mmi.modinstaller.core.int
import com.mmi.modinstaller.core.list
import com.mmi.modinstaller.core.long
import com.mmi.modinstaller.core.str
import com.mmi.modinstaller.jar.ModLoader

/**
 * Un modpack che l'app ha installato (o iniziato a installare). Serve a ritrovare il suo profilo e a riprenderlo se è rimasto
 * incompleto: per questo l'app ne tiene una copia del file `.mrpack` ([archiveName], nella cartella dei modpack).
 */
data class ModpackRecord(
    val id: String,
    val name: String,
    val versionId: String?,
    val minecraft: String,
    val loader: ModLoader,
    val loaderVersion: String?,
    /** Il profilo del MOD MANAGER in cui è stato installato. */
    val profileId: String,
    /** Nome del file con la copia del modpack, dentro la cartella dei modpack dell'app. */
    val archiveName: String,
    val totalFiles: Int,
    val placedFiles: Int,
    /** Ultimo esito: tutto quello che il modpack prevede è al suo posto. */
    val complete: Boolean,
    /** Che cosa manca ancora (percorso e motivo), per mostrarlo anche dopo aver chiuso l'app. */
    val problems: List<String>,
    val createdAt: Long,
    val updatedAt: Long
) {
    val title: String get() = name + (versionId?.let { " $it" } ?: "")
}

/** Elenco dei modpack installati, nella sezione `modpacks` dell'archivio centrale dell'app. */
class ModpackRepository(private val store: DocumentStore) {

    fun list(): List<ModpackRecord> = rawList(store.read(SECTION)).mapNotNull { recordOf(it) }

    fun get(id: String): ModpackRecord? = list().firstOrNull { it.id == id }

    fun forProfile(profileId: String): ModpackRecord? = list().firstOrNull { it.profileId == profileId }

    fun add(record: ModpackRecord): Boolean = transact { current ->
        val items = rawList(current)
        items.removeAll { it.str("id") == record.id }
        items.add(toMap(record))
        toValue(items)
    }

    fun update(id: String, change: (ModpackRecord) -> ModpackRecord): Boolean = transact { current ->
        val items = rawList(current)
        val i = items.indexOfFirst { it.str("id") == id }
        val old = if (i >= 0) recordOf(items[i]) else null
        if (old != null) items[i] = toMap(change(old).copy(id = old.id))
        toValue(items)
    }

    fun remove(id: String): Boolean = transact { current ->
        val items = rawList(current)
        items.removeAll { it.str("id") == id }
        toValue(items)
    }

    // ------------------------------------------------------------------ conversioni

    /** Come [DocumentStore.update], ma se il salvataggio non riesce riporta anche la copia in memoria allo stato precedente. */
    private fun transact(transform: (Any?) -> Any?): Boolean {
        val before = store.read(SECTION)
        val ok = store.update(SECTION, transform)
        if (!ok) store.write(SECTION, before)
        return ok
    }

    private fun rawList(value: Any?): MutableList<MutableMap<String, Any?>> {
        val out = ArrayList<MutableMap<String, Any?>>()
        for (item in value.asJsonObject()?.list("items").orEmpty()) {
            val m = item.asJsonObject() ?: continue
            @Suppress("UNCHECKED_CAST")
            out.add(LinkedHashMap(m as Map<String, Any?>))
        }
        return out
    }

    private fun toValue(items: List<Map<String, Any?>>): Map<String, Any?> = linkedMapOf("version" to FORMAT_VERSION, "items" to items)

    private fun recordOf(m: Map<String, Any?>): ModpackRecord? {
        val id = m.str("id") ?: return null
        val loader = ModLoader.fromKey(m.str("loader")) ?: return null
        return ModpackRecord(
            id = id,
            name = m.str("name") ?: id,
            versionId = m.str("versionId"),
            minecraft = m.str("minecraft") ?: return null,
            loader = loader,
            loaderVersion = m.str("loaderVersion"),
            profileId = m.str("profileId") ?: return null,
            archiveName = m.str("archiveName") ?: return null,
            totalFiles = m.int("totalFiles") ?: 0,
            placedFiles = m.int("placedFiles") ?: 0,
            complete = m.bool("complete") ?: false,
            problems = m.list("problems").orEmpty().filterIsInstance<String>(),
            createdAt = m.long("createdAt") ?: 0L,
            updatedAt = m.long("updatedAt") ?: 0L
        )
    }

    private fun toMap(r: ModpackRecord): MutableMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["id"] = r.id
        m["name"] = r.name
        if (r.versionId != null) m["versionId"] = r.versionId
        m["minecraft"] = r.minecraft
        m["loader"] = r.loader.key
        if (r.loaderVersion != null) m["loaderVersion"] = r.loaderVersion
        m["profileId"] = r.profileId
        m["archiveName"] = r.archiveName
        m["totalFiles"] = r.totalFiles
        m["placedFiles"] = r.placedFiles
        m["complete"] = r.complete
        m["problems"] = r.problems.take(MAX_PROBLEMS).map { it.take(MAX_PROBLEM_CHARS) }
        m["createdAt"] = r.createdAt
        m["updatedAt"] = r.updatedAt
        return m
    }

    companion object {
        const val SECTION = "modpacks"
        private const val FORMAT_VERSION = 1

        /** Quante voci di «che cosa manca» si conservano per modpack (le altre si contano e basta). */
        const val MAX_PROBLEMS = 100
        private const val MAX_PROBLEM_CHARS = 400
    }
}
