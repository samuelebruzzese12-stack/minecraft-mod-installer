package com.mmi.modinstaller.core

import java.io.File
import java.io.IOException
import java.text.Normalizer

/**
 * Stato di un contenuto inviato a Minecraft. Minecraft non restituisce alcun risultato all'app che lo apre:
 * [CONFIRMED] e [FAILED] derivano SOLO da ciò che l'utente dichiara di aver visto nel gioco.
 */
enum class InstallState { SENT, CONFIRMED, FAILED }

data class PackRef(val uuid: String, val name: String, val versionText: String)

data class RecentFile(val uri: String, val name: String, val sizeBytes: Long, val openedAt: Long)

data class InstalledEntry(
    val id: String,
    val title: String,
    /** Nome di un [ContentKind]. */
    val kind: String,
    val version: String?,
    val author: String?,
    val packs: List<PackRef>,
    /** Nome del file preparato nella cartella gestita dall'app. */
    val fileName: String,
    val sizeBytes: Long,
    /** Nome del file scelto dall'utente da cui deriva. */
    val source: String,
    val sentAt: Long,
    val state: InstallState,
    val stateAt: Long
) {
    fun withState(newState: InstallState, at: Long): InstalledEntry = copy(state = newState, stateAt = at)

    val contentKind: ContentKind
        get() = ContentKind.values().firstOrNull { it.name == kind } ?: ContentKind.UNKNOWN
}

data class HistoryEntry(
    val at: Long,
    val title: String,
    val action: String,
    val result: String,
    val detail: String? = null
)

/**
 * Elenchi persistenti dell'app (file recenti, contenuti gestiti, cronologia) salvati in un unico file JSON.
 * Le scritture sono atomiche (file temporaneo + rinomina) e un file illeggibile non fa mai crashare l'app:
 * viene messo da parte e si riparte da un elenco vuoto.
 */
class LibraryStore(
    private val file: File,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : DocumentStore {
    private val lock = Any()
    private val recentList = ArrayList<RecentFile>()
    private val installedList = ArrayList<InstalledEntry>()
    private val historyList = ArrayList<HistoryEntry>()

    /**
     * Sezioni dei motori aggiunti dopo (mod, modpack, mondi, download, impostazioni…). Vengono lette e riscritte
     * così come sono: una sezione che questa versione non conosce non va mai persa al salvataggio.
     */
    private val extraSections = LinkedHashMap<String, Any?>()

    /** True se all'avvio il file esisteva ma non era leggibile (è stato rinominato in .corrupt). */
    var recoveredFromCorruption: Boolean = false
        private set

    init {
        load()
    }

    // ------------------------------------------------------------------ lettura

    fun recents(): List<RecentFile> = synchronized(lock) { recentList.toList() }

    fun installed(): List<InstalledEntry> = synchronized(lock) { installedList.toList() }

    fun history(): List<HistoryEntry> = synchronized(lock) { historyList.toList() }

    fun findInstalled(id: String): InstalledEntry? = synchronized(lock) { installedList.firstOrNull { it.id == id } }

    /** Pacchetti già confermati dall'utente come importati: serve a segnalare doppioni e aggiornamenti. */
    fun knownPacks(): KnownPacks = KnownPacks { uuid ->
        synchronized(lock) {
            var best: PackRef? = null
            var bestVersion: McVersion? = null
            for (entry in installedList) {
                if (entry.state != InstallState.CONFIRMED) continue
                for (p in entry.packs) {
                    if (!p.uuid.equals(uuid, ignoreCase = true)) continue
                    val v = McVersion.parse(p.versionText)
                    val better = best == null || (v != null && (bestVersion == null || v.compareTo(bestVersion!!) > 0))
                    if (better) {
                        best = p
                        bestVersion = v
                    }
                }
            }
            best?.let { KnownPack(uuid.lowercase(), it.name, it.versionText, bestVersion) }
        }
    }

    // ------------------------------------------------------------------ modifica

    fun addRecent(recent: RecentFile) {
        synchronized(lock) {
            recentList.removeAll { it.uri == recent.uri || (it.name == recent.name && it.sizeBytes == recent.sizeBytes) }
            recentList.add(0, recent)
            while (recentList.size > MAX_RECENTS) recentList.removeAt(recentList.size - 1)
            save()
        }
    }

    fun removeRecent(uri: String) {
        synchronized(lock) {
            if (recentList.removeAll { it.uri == uri }) save()
        }
    }

    fun clearRecents() {
        synchronized(lock) {
            recentList.clear()
            save()
        }
    }

    fun putInstalled(entry: InstalledEntry) {
        synchronized(lock) {
            installedList.removeAll { it.id == entry.id }
            installedList.add(0, entry)
            while (installedList.size > MAX_INSTALLED) installedList.removeAt(installedList.size - 1)
            save()
        }
    }

    fun setInstallState(id: String, state: InstallState): InstalledEntry? {
        synchronized(lock) {
            val i = installedList.indexOfFirst { it.id == id }
            if (i < 0) return null
            val updated = installedList[i].withState(state, clock())
            installedList[i] = updated
            save()
            return updated
        }
    }

    fun removeInstalled(id: String): InstalledEntry? {
        synchronized(lock) {
            val i = installedList.indexOfFirst { it.id == id }
            if (i < 0) return null
            val removed = installedList.removeAt(i)
            save()
            return removed
        }
    }

    fun addHistory(title: String, action: String, result: String, detail: String? = null) {
        synchronized(lock) {
            historyList.add(0, HistoryEntry(clock(), title, action, result, detail))
            while (historyList.size > MAX_HISTORY) historyList.removeAt(historyList.size - 1)
            save()
        }
    }

    fun clearHistory() {
        synchronized(lock) {
            historyList.clear()
            save()
        }
    }

    // ------------------------------------------------------------------ sezioni dei motori (DocumentStore)

    override fun read(section: String): Any? = synchronized(lock) { deepCopyJson(extraSections[section]) }

    override fun write(section: String, value: Any?): Boolean = synchronized(lock) {
        requireCustomSection(section)
        if (value == null) extraSections.remove(section) else extraSections[section] = deepCopyJson(value)
        save()
    }

    override fun update(section: String, transform: (Any?) -> Any?): Boolean = synchronized(lock) {
        requireCustomSection(section)
        val next = transform(deepCopyJson(extraSections[section]))
        if (next == null) extraSections.remove(section) else extraSections[section] = deepCopyJson(next)
        save()
    }

    private fun requireCustomSection(section: String) {
        require(section.isNotBlank() && section !in RESERVED_SECTIONS) { "Nome di sezione non valido: «$section»" }
    }

    // ------------------------------------------------------------------ persistenza

    private fun load() {
        if (!file.isFile) return
        try {
            val root = Json.parseObject(file.readText(Charsets.UTF_8))
            for (r in root.list("recents").orEmpty()) {
                val o = r.asJsonObject() ?: continue
                val uri = o.str("uri") ?: continue
                recentList.add(RecentFile(uri, o.str("name") ?: uri, o.long("size") ?: 0L, o.long("at") ?: 0L))
            }
            for (e in root.list("installed").orEmpty()) {
                val o = e.asJsonObject() ?: continue
                val id = o.str("id") ?: continue
                val packs = ArrayList<PackRef>()
                for (p in o.list("packs").orEmpty()) {
                    val po = p.asJsonObject() ?: continue
                    val u = po.str("uuid") ?: continue
                    packs.add(PackRef(u, po.str("name") ?: "", po.str("version") ?: ""))
                }
                val state = InstallState.values().firstOrNull { it.name == o.str("state") } ?: InstallState.SENT
                installedList.add(
                    InstalledEntry(
                        id = id, title = o.str("title") ?: id, kind = o.str("kind") ?: ContentKind.UNKNOWN.name,
                        version = o.str("version"), author = o.str("author"), packs = packs,
                        fileName = o.str("file") ?: "", sizeBytes = o.long("size") ?: 0L,
                        source = o.str("source") ?: "", sentAt = o.long("sentAt") ?: 0L,
                        state = state, stateAt = o.long("stateAt") ?: 0L
                    )
                )
            }
            for (h in root.list("history").orEmpty()) {
                val o = h.asJsonObject() ?: continue
                historyList.add(
                    HistoryEntry(
                        o.long("at") ?: 0L, o.str("title") ?: "", o.str("action") ?: "", o.str("result") ?: "", o.str("detail")
                    )
                )
            }
            for ((key, value) in root) {
                if (key !in RESERVED_SECTIONS) extraSections[key] = deepCopyJson(value)
            }
        } catch (e: Exception) {
            recentList.clear()
            installedList.clear()
            historyList.clear()
            extraSections.clear()
            recoveredFromCorruption = true
            val aside = File(file.parentFile, file.name + ".corrupt")
            aside.delete()
            file.renameTo(aside)
        }
    }

    private fun save(): Boolean {
        val root = linkedMapOf<String, Any?>(
            "version" to FORMAT_VERSION,
            "recents" to recentList.map {
                linkedMapOf("uri" to it.uri, "name" to it.name, "size" to it.sizeBytes, "at" to it.openedAt)
            },
            "installed" to installedList.map {
                linkedMapOf(
                    "id" to it.id, "title" to it.title, "kind" to it.kind, "version" to it.version, "author" to it.author,
                    "packs" to it.packs.map { p -> linkedMapOf("uuid" to p.uuid, "name" to p.name, "version" to p.versionText) },
                    "file" to it.fileName, "size" to it.sizeBytes, "source" to it.source, "sentAt" to it.sentAt,
                    "state" to it.state.name, "stateAt" to it.stateAt
                )
            },
            "history" to historyList.map {
                linkedMapOf("at" to it.at, "title" to it.title, "action" to it.action, "result" to it.result, "detail" to it.detail)
            }
        )
        for ((key, value) in extraSections) root[key] = value
        return try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(Json.stringify(root, true), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(Json.stringify(root, true), Charsets.UTF_8)
                tmp.delete()
            }
            true
        } catch (e: IOException) {
            false
        }
    }

    companion object {
        const val MAX_RECENTS = 30
        const val MAX_INSTALLED = 500
        const val MAX_HISTORY = 500

        /** 2 = il file può contenere anche le sezioni dei motori; le versioni precedenti le ignorano. */
        const val FORMAT_VERSION = 2

        private val RESERVED_SECTIONS = setOf("version", "recents", "installed", "history")
    }
}

/** Ricerca di testo senza distinzione di maiuscole e accenti; ogni parola cercata deve comparire in almeno un campo. */
object TextSearch {
    private val MARKS = Regex("\\p{M}+")

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase()

    fun matches(query: String, vararg fields: String?): Boolean {
        val tokens = normalize(query).split(' ', '\t').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        val haystack = fields.filterNotNull().joinToString(" ") { normalize(it) }
        return tokens.all { haystack.contains(it) }
    }
}

enum class LaunchMode { EXPLICIT_COMPONENT, PACKAGE_ONLY, CHOOSER }

/** Un tentativo di aprire un file con Minecraft; l'app Android lo traduce in un Intent. */
data class LaunchAttempt(
    val mode: LaunchMode,
    val packageName: String?,
    val className: String?,
    val mimeType: String
)

/**
 * Ordine dei tentativi per consegnare il file a Minecraft: prima il componente esplicito di Minecraft
 * (funziona anche se i filtri di intent cambiano), poi l'intent limitato al suo pacchetto, infine il selettore
 * di sistema così l'utente può scegliere un'app che apra il file.
 */
object LaunchPlanner {
    val MINECRAFT_PACKAGES: List<String> = listOf(
        "com.mojang.minecraftpe",
        "com.mojang.minecraftpe.preview",
        "com.mojang.minecrafttrialpe"
    )

    const val MINECRAFT_ACTIVITY = "com.mojang.minecraftpe.MainActivity"
    const val DEFAULT_MIME = "application/octet-stream"

    /**
     * @param installed pacchetti Minecraft presenti sul telefono, in ordine di preferenza
     * @param launcherClass classe dell'activity di avvio per il pacchetto, se nota
     */
    fun plan(installed: List<String>, launcherClass: (String) -> String? = { null }): List<LaunchAttempt> {
        val attempts = ArrayList<LaunchAttempt>()
        for (pkg in installed) {
            val cls = launcherClass(pkg) ?: MINECRAFT_ACTIVITY
            attempts.add(LaunchAttempt(LaunchMode.EXPLICIT_COMPONENT, pkg, cls, DEFAULT_MIME))
        }
        for (pkg in installed) attempts.add(LaunchAttempt(LaunchMode.PACKAGE_ONLY, pkg, null, DEFAULT_MIME))
        attempts.add(LaunchAttempt(LaunchMode.CHOOSER, null, null, DEFAULT_MIME))
        return attempts
    }
}
