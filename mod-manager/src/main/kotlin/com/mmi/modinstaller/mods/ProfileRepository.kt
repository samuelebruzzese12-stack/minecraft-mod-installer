package com.mmi.modinstaller.mods

import com.mmi.modinstaller.core.DocumentStore
import com.mmi.modinstaller.core.asJsonObject
import com.mmi.modinstaller.core.bool
import com.mmi.modinstaller.core.int
import com.mmi.modinstaller.core.list
import com.mmi.modinstaller.core.long
import com.mmi.modinstaller.core.str
import com.mmi.modinstaller.jar.ContentKind
import com.mmi.modinstaller.jar.ModJson
import com.mmi.modinstaller.jar.ModLoader

/**
 * Profili, file gestiti e cronologia delle operazioni, nella sezione `java_profiles` dell'archivio centrale dell'app.
 * Il disco è la fonte di verità per la presenza dei file; qui si tengono le informazioni lette dall'analisi.
 */
class ProfileRepository(private val store: DocumentStore) {

    private class Raw(var active: String?, val profiles: MutableList<MutableMap<String, Any?>>)

    // ------------------------------------------------------------------ lettura

    private fun load(value: Any?): Raw {
        val root = value.asJsonObject()
        val profiles = ArrayList<MutableMap<String, Any?>>()
        for (p in root?.list("profiles").orEmpty()) {
            val m = p.asJsonObject() ?: continue
            @Suppress("UNCHECKED_CAST")
            profiles.add(LinkedHashMap(m as Map<String, Any?>))
        }
        return Raw(root?.str("active"), profiles)
    }

    private fun toValue(raw: Raw): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["version"] = FORMAT_VERSION
        raw.active?.let { map["active"] = it }
        map["profiles"] = raw.profiles
        return map
    }

    private fun profileOf(m: Map<String, Any?>): Profile? {
        val id = m.str("id") ?: return null
        val loader = ModLoader.fromKey(m.str("loader")) ?: return null
        return Profile(
            id = id,
            name = m.str("name") ?: id,
            minecraft = m.str("minecraft") ?: return null,
            loader = loader,
            loaderVersion = m.str("loaderVersion"),
            javaMajor = m.int("javaMajor"),
            createdAt = m.long("createdAt") ?: 0L
        )
    }

    private fun profileToMap(p: Profile, base: MutableMap<String, Any?>) {
        base["id"] = p.id
        base["name"] = p.name
        base["minecraft"] = p.minecraft
        base["loader"] = p.loader.key
        if (p.loaderVersion != null) base["loaderVersion"] = p.loaderVersion else base.remove("loaderVersion")
        if (p.javaMajor != null) base["javaMajor"] = p.javaMajor else base.remove("javaMajor")
        base["createdAt"] = p.createdAt
    }

    fun list(): List<Profile> = load(store.read(SECTION)).profiles.mapNotNull { profileOf(it) }

    fun get(id: String): Profile? = list().firstOrNull { it.id == id }

    fun activeId(): String? {
        val raw = load(store.read(SECTION))
        val active = raw.active
        return if (active != null && raw.profiles.any { it.str("id") == active }) active else null
    }

    fun items(profileId: String): List<ProfileItem> {
        val m = load(store.read(SECTION)).profiles.firstOrNull { it.str("id") == profileId } ?: return emptyList()
        return m.list("items").orEmpty().mapNotNull { itemFromMap(it.asJsonObject()) }
    }

    fun journal(profileId: String): List<JournalEntry> {
        val m = load(store.read(SECTION)).profiles.firstOrNull { it.str("id") == profileId } ?: return emptyList()
        return m.list("journal").orEmpty().mapNotNull { entryFromMap(it.asJsonObject()) }
    }

    // ------------------------------------------------------------------ scrittura

    /**
     * Come [DocumentStore.update], ma se il salvataggio su disco non riesce riporta anche la copia in memoria allo stato
     * precedente: chi chiama annulla le modifiche ai file, e archivio e disco restano d'accordo.
     */
    private fun transact(transform: (Any?) -> Any?): Boolean {
        val before = store.read(SECTION)
        val ok = store.update(SECTION, transform)
        if (!ok) store.write(SECTION, before)
        return ok
    }

    fun add(profile: Profile): Boolean = transact { current ->
        val raw = load(current)
        val m = LinkedHashMap<String, Any?>()
        profileToMap(profile, m)
        m["items"] = ArrayList<Any?>()
        m["journal"] = ArrayList<Any?>()
        raw.profiles.removeAll { it.str("id") == profile.id }
        raw.profiles.add(m)
        if (raw.active == null) raw.active = profile.id
        toValue(raw)
    }

    fun update(id: String, change: (Profile) -> Profile): Boolean = transact { current ->
        val raw = load(current)
        val m = raw.profiles.firstOrNull { it.str("id") == id }
        val old = m?.let { profileOf(it) }
        if (m != null && old != null) profileToMap(change(old).copy(id = old.id), m)
        toValue(raw)
    }

    fun remove(id: String): Boolean = transact { current ->
        val raw = load(current)
        raw.profiles.removeAll { it.str("id") == id }
        if (raw.active == id) raw.active = raw.profiles.firstOrNull()?.str("id")
        toValue(raw)
    }

    fun setActive(id: String?): Boolean = transact { current ->
        val raw = load(current)
        raw.active = if (id != null && raw.profiles.any { it.str("id") == id }) id else raw.active
        toValue(raw)
    }

    /**
     * Cambia in un colpo solo elenco dei file e cronologia di un profilo. Restituisce false se il profilo non esiste o se il
     * salvataggio non è riuscito: chi chiama deve annullare le modifiche fatte sui file.
     */
    fun modify(
        profileId: String,
        change: (items: MutableList<ProfileItem>, journal: MutableList<JournalEntry>) -> Unit
    ): Boolean {
        var found = false
        val ok = transact { current ->
            val raw = load(current)
            val m = raw.profiles.firstOrNull { it.str("id") == profileId }
            if (m != null) {
                found = true
                val items = m.list("items").orEmpty().mapNotNull { itemFromMap(it.asJsonObject()) }.toMutableList()
                val journal = m.list("journal").orEmpty().mapNotNull { entryFromMap(it.asJsonObject()) }.toMutableList()
                change(items, journal)
                m["items"] = items.map { itemToMap(it) }
                m["journal"] = journal.map { entryToMap(it) }
            }
            toValue(raw)
        }
        return ok && found
    }

    // ------------------------------------------------------------------ conversioni

    private fun itemToMap(i: ProfileItem): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["id"] = i.id
        m["category"] = i.category.key
        m["fileName"] = i.fileName
        m["enabled"] = i.enabled
        m["sha256"] = i.sha256
        m["sha1"] = i.sha1
        m["size"] = i.sizeBytes
        m["installedAt"] = i.installedAt
        m["origin"] = i.origin
        m["source"] = i.source
        m["kind"] = i.kind.name
        if (i.mods.isNotEmpty()) m["mods"] = i.mods.map { ModJson.toMap(it) }
        if (i.nested.isNotEmpty()) m["nested"] = i.nested.map { ModJson.toMap(it, withDependencies = false) }
        i.javaMajor?.let { m["javaMajor"] = it }
        i.packFormat?.let { m["packFormat"] = it }
        i.note?.let { m["note"] = it }
        i.sourceSha256?.let { m["sourceSha256"] = it }
        return m
    }

    private fun itemFromMap(m: Map<String, Any?>?): ProfileItem? {
        if (m == null) return null
        val category = ItemCategory.fromKey(m.str("category")) ?: return null
        return ProfileItem(
            id = m.str("id") ?: return null,
            category = category,
            fileName = m.str("fileName") ?: return null,
            enabled = m.bool("enabled") ?: true,
            sha256 = m.str("sha256") ?: "",
            sha1 = m.str("sha1") ?: "",
            sizeBytes = m.long("size") ?: 0L,
            installedAt = m.long("installedAt") ?: 0L,
            origin = m.str("origin") ?: (m.str("fileName") ?: ""),
            source = m.str("source") ?: "file",
            kind = ContentKind.values().firstOrNull { it.name == m.str("kind") } ?: ContentKind.UNKNOWN,
            mods = m.list("mods").orEmpty().mapNotNull { ModJson.fromMap(it.asJsonObject() ?: return@mapNotNull null) },
            nested = m.list("nested").orEmpty().mapNotNull { ModJson.fromMap(it.asJsonObject() ?: return@mapNotNull null) },
            javaMajor = m.int("javaMajor"),
            packFormat = m.int("packFormat"),
            note = m.str("note"),
            sourceSha256 = m.str("sourceSha256")
        )
    }

    private fun entryToMap(e: JournalEntry): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["id"] = e.id
        m["time"] = e.time
        m["label"] = e.label
        m["steps"] = e.steps.map { s ->
            val sm = LinkedHashMap<String, Any?>()
            when (s) {
                is JournalStep.Added -> {
                    sm["t"] = "added"
                    sm["item"] = itemToMap(s.item)
                }
                is JournalStep.Removed -> {
                    sm["t"] = "removed"
                    sm["item"] = itemToMap(s.item)
                    sm["trash"] = s.trashName
                }
                is JournalStep.Toggled -> {
                    sm["t"] = "toggled"
                    sm["id"] = s.itemId
                    sm["was"] = s.wasEnabled
                }
            }
            sm
        }
        return m
    }

    private fun entryFromMap(m: Map<String, Any?>?): JournalEntry? {
        if (m == null) return null
        val steps = ArrayList<JournalStep>()
        for (raw in m.list("steps").orEmpty()) {
            val s = raw.asJsonObject() ?: return null
            when (s.str("t")) {
                "added" -> steps.add(JournalStep.Added(itemFromMap(s["item"].asJsonObject()) ?: return null))
                "removed" -> steps.add(
                    JournalStep.Removed(itemFromMap(s["item"].asJsonObject()) ?: return null, s.str("trash") ?: return null)
                )
                "toggled" -> steps.add(JournalStep.Toggled(s.str("id") ?: return null, s.bool("was") ?: true))
                else -> return null
            }
        }
        return JournalEntry(m.str("id") ?: return null, m.long("time") ?: 0L, m.str("label") ?: "", steps)
    }

    companion object {
        const val SECTION = "java_profiles"
        const val FORMAT_VERSION = 1
    }
}
