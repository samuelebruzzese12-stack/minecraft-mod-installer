package com.mmi.modinstaller

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mmi.modinstaller.catalog.CatalogException
import com.mmi.modinstaller.catalog.CatalogPlan
import com.mmi.modinstaller.catalog.CatalogProject
import com.mmi.modinstaller.catalog.CatalogSource
import com.mmi.modinstaller.catalog.DependencyPlanner
import com.mmi.modinstaller.catalog.InstalledCheck
import com.mmi.modinstaller.catalog.InstalledState
import com.mmi.modinstaller.catalog.PlanTarget
import com.mmi.modinstaller.catalog.ProjectKind
import com.mmi.modinstaller.catalog.SearchSort
import com.mmi.modinstaller.core.CancelCheck
import com.mmi.modinstaller.data.AppContainer
import com.mmi.modinstaller.data.ErrorMessages
import com.mmi.modinstaller.download.DownloadEngine
import com.mmi.modinstaller.download.DownloadRecord
import com.mmi.modinstaller.download.DownloadRequest
import com.mmi.modinstaller.download.DownloadState
import com.mmi.modinstaller.download.EnqueueResult
import com.mmi.modinstaller.download.ExpectedHash
import com.mmi.modinstaller.jar.ModLoader
import com.mmi.modinstaller.mods.ItemCategory
import com.mmi.modinstaller.mods.ModManager
import com.mmi.modinstaller.mods.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Esito dell'accodamento: i file messi in coda, oppure il motivo per cui non si è fatto nulla. */
private class Enqueued(val records: List<DownloadRecord>?, val error: String?)

/** La scelta aperta: il progetto, e il piano di download (cosa si scarica) quando è pronto. */
class CatalogDetail(
    val project: CatalogProject,
    val loading: Boolean,
    val plan: CatalogPlan?,
    val error: String?
)

/** I file di una scelta mentre si scaricano nel DOWNLOAD MANAGER. */
data class CatalogBatch(
    val title: String,
    val downloadIds: List<String>,
    val records: List<DownloadRecord>
) {
    /** Tutti i file sono arrivati e verificati. */
    val completed: Boolean get() = records.size == downloadIds.size && records.isNotEmpty() && records.all { it.state == DownloadState.COMPLETED }

    /** Nessun file è più in lavorazione (arrivati, falliti o annullati). */
    val finished: Boolean get() = records.size == downloadIds.size && records.all { it.state.finished }

    val failed: List<DownloadRecord> get() = records.filter { it.state == DownloadState.FAILED || it.state == DownloadState.CANCELED }
}

data class CatalogState(
    val kind: ProjectKind = ProjectKind.MOD,
    val sort: SearchSort = SearchSort.RELEVANCE,
    /** Cerca solo ciò che va bene per il profilo attivo (caricatore e versione di Minecraft). */
    val onlyForProfile: Boolean = true,
    /** Nome del profilo attivo con i suoi valori («Fabric 1.20.1»), o null se non ce n'è. */
    val profileLabel: String? = null,
    val lastQuery: String = "",
    val results: List<CatalogProject> = emptyList(),
    val total: Int = 0,
    val searching: Boolean = false,
    /** C'è stata almeno una ricerca: distingue «nessun risultato» da «non hai ancora cercato». */
    val searched: Boolean = false,
    val error: String? = null,
    val detail: CatalogDetail? = null,
    val batch: CatalogBatch? = null,
    val message: String? = null
)

/**
 * Stato e azioni del catalogo online. Cerca su Modrinth ([CatalogSource]), prepara il piano con le dipendenze
 * obbligatorie ([DependencyPlanner]) e mette i file in coda nel DOWNLOAD MANAGER con l'impronta pubblicata dal servizio,
 * che il motore verifica prima di tenerli. I file scaricati passano poi dal MOD MANAGER come qualsiasi altro file: lì
 * vengono letti (senza eseguirli) e controllati sul profilo, e l'utente li installa con un tocco.
 *
 * Regola: un errore imprevisto non deve mai chiudere l'app: si registra per DIAGNOSTICA e diventa un messaggio.
 */
class CatalogViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)
    // Pigra: la prima chiamata di rete decide l'indirizzo (in debug può essere sostituito da un file di prova).
    private val source: CatalogSource by lazy { container.catalog }
    private val manager: ModManager = container.modManager
    private val engine: DownloadEngine = container.downloads

    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var detailJob: Job? = null

    private val stopListening: () -> Unit = engine.addListener { list ->
        _state.update { s ->
            val batch = s.batch ?: return@update s
            s.copy(batch = batch.copy(records = batch.downloadIds.mapNotNull { id -> list.firstOrNull { it.id == id } }))
        }
    }

    override fun onCleared() {
        stopListening()
        super.onCleared()
    }

    // ------------------------------------------------------------------ filtri

    /** Si apre la schermata: rilegge il profilo attivo, che può essere cambiato nel frattempo. */
    fun onOpened() {
        viewModelScope.launch {
            val label = withContext(Dispatchers.IO) { safely { manager.activeProfile()?.let { labelOf(it) } } }
            _state.update { it.copy(profileLabel = label) }
        }
    }

    fun setKind(kind: ProjectKind) {
        if (kind == _state.value.kind) return
        searchJob?.cancel()
        // Un altro tipo di contenuto: i risultati di prima non valgono più.
        _state.update { it.copy(kind = kind, results = emptyList(), total = 0, searched = false, searching = false, error = null, detail = null) }
    }

    fun setSort(sort: SearchSort) {
        if (sort == _state.value.sort) return
        _state.update { it.copy(sort = sort) }
        if (_state.value.searched) search(_state.value.lastQuery)
    }

    fun setOnlyForProfile(value: Boolean) {
        if (value == _state.value.onlyForProfile) return
        _state.update { it.copy(onlyForProfile = value) }
        if (_state.value.searched) search(_state.value.lastQuery)
    }

    // ------------------------------------------------------------------ ricerca

    fun search(text: String) = runSearch(text.trim(), 0)

    /** Aggiunge i risultati successivi a quelli già mostrati. */
    fun loadMore() {
        val s = _state.value
        if (s.searching || s.results.size >= s.total) return
        runSearch(s.lastQuery, s.results.size)
    }

    private fun runSearch(text: String, offset: Int) {
        val before = _state.value
        searchJob?.cancel()
        _state.update {
            it.copy(
                searching = true, error = null, message = null, lastQuery = text, detail = null,
                results = if (offset == 0) emptyList() else it.results, total = if (offset == 0) 0 else it.total
            )
        }
        searchJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    val ctx = coroutineContext
                    val cancel: CancelCheck = { ctx.ensureActive() }
                    val profile = manager.activeProfile()
                    val loaders = if (before.onlyForProfile && profile != null) loadersFor(profile) else emptyList()
                    val game = if (before.onlyForProfile) profile?.minecraft else null
                    source.search(text, before.kind, loaders, game, before.sort, offset, PAGE_SIZE, cancel)
                }
                _state.update { s ->
                    val known = s.results.map { it.id }.toSet()
                    s.copy(
                        results = s.results + page.hits.filter { it.id !in known },
                        total = page.total, searching = false, searched = true
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CatalogException) {
                _state.update { it.copy(searching = false, searched = true, error = e.message) }
            } catch (e: Throwable) {
                record("Ricerca nel catalogo", e)
                _state.update { it.copy(searching = false, searched = true, error = ErrorMessages.describe(e)) }
            }
        }
    }

    // ------------------------------------------------------------------ scelta e piano

    /** Prepara il piano per il progetto scelto: la versione adatta al profilo e le dipendenze obbligatorie. */
    fun select(project: CatalogProject) {
        detailJob?.cancel()
        _state.update { it.copy(detail = CatalogDetail(project, loading = true, plan = null, error = null), message = null) }
        detailJob = viewModelScope.launch {
            try {
                val plan = withContext(Dispatchers.IO) {
                    val ctx = coroutineContext
                    val cancel: CancelCheck = { ctx.ensureActive() }
                    val profile = manager.activeProfile() ?: return@withContext null
                    val kind = _state.value.kind
                    DependencyPlanner(source).plan(project, targetFor(kind, profile), installedCheck(profile, kind), cancel)
                }
                _state.update { s ->
                    if (s.detail?.project?.id != project.id) s
                    else s.copy(
                        detail = CatalogDetail(
                            project, loading = false, plan = plan,
                            error = if (plan == null) "Crea prima un profilo nel MOD MANAGER: l'app sceglie la versione adatta al suo caricatore e alla sua versione di Minecraft." else null
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CatalogException) {
                _state.update { s -> if (s.detail?.project?.id != project.id) s else s.copy(detail = CatalogDetail(project, false, null, e.message)) }
            } catch (e: Throwable) {
                record("Piano di download dal catalogo", e)
                _state.update { s -> if (s.detail?.project?.id != project.id) s else s.copy(detail = CatalogDetail(project, false, null, ErrorMessages.describe(e))) }
            }
        }
    }

    fun closeDetail() {
        detailJob?.cancel()
        _state.update { it.copy(detail = null) }
    }

    // ------------------------------------------------------------------ download

    /** Mette in coda nel DOWNLOAD MANAGER tutti i file del piano, ciascuno con la sua impronta da verificare. */
    fun download() {
        val s = _state.value
        val plan = s.detail?.plan ?: return
        if (plan.items.isEmpty()) return
        val active = s.batch
        if (active != null && !active.finished) {
            _state.update { it.copy(message = "C'è già uno scarico in corso: attendi che finisca o annullalo.", detail = null) }
            return
        }
        val title = plan.items.first().project.displayName
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val added = ArrayList<DownloadRecord>()
                    var refusal: String? = null
                    for (item in plan.items) {
                        val hash = item.file.expectedHash()?.let { ExpectedHash.parse(it) }
                        if (hash == null) {
                            refusal = "«" + item.file.fileName + "» non ha un'impronta da verificare: non lo scarico."
                            break
                        }
                        val result = engine.enqueue(
                            DownloadRequest(item.file.url, item.file.fileName, hash, "Modrinth: " + item.project.displayName)
                        )
                        if (result is EnqueueResult.Added) {
                            added.add(result.record)
                        } else {
                            refusal = "Non posso scaricare «" + item.file.fileName + "»: " + (result as EnqueueResult.Rejected).message
                            break
                        }
                    }
                    if (refusal != null) {
                        // Un piano a metà non serve: si tolgono i file già messi in coda.
                        for (r in added) {
                            engine.cancel(r.id)
                            engine.remove(r.id)
                        }
                        Enqueued(null, refusal)
                    } else {
                        Enqueued(added, null)
                    }
                } catch (e: Throwable) {
                    record("Scarico dal catalogo", e)
                    Enqueued(null, ErrorMessages.describe(e))
                }
            }
            val added = outcome.records
            if (added == null) {
                _state.update { it.copy(message = outcome.error, detail = null) }
            } else {
                _state.update {
                    it.copy(detail = null, message = null, batch = CatalogBatch(title, added.map { r -> r.id }, added))
                }
            }
        }
    }

    /** Ferma i file della scelta ancora in corso e li toglie dal DOWNLOAD MANAGER. */
    fun cancelBatch() {
        val batch = _state.value.batch ?: return
        _state.update { it.copy(batch = null, message = "Scarico annullato.") }
        viewModelScope.launch(Dispatchers.IO) {
            for (id in batch.downloadIds) {
                try {
                    engine.cancel(id)
                    engine.remove(id)
                } catch (e: Throwable) {
                    record("Annullamento dello scarico", e)
                }
            }
        }
    }

    /** Chiude il riquadro di uno scarico finito (i file restano nel DOWNLOAD MANAGER). */
    fun dismissBatch() = _state.update { it.copy(batch = null) }

    /**
     * Passa i file scaricati al MOD MANAGER, che li legge e li controlla sul profilo: da lì si installano con un tocco.
     * [openMods] riporta alla pagina del MOD MANAGER.
     */
    fun addToProfile(mods: ModsViewModel, openMods: () -> Unit) {
        val batch = _state.value.batch ?: return
        if (!batch.completed) return
        val uris = batch.records.mapNotNull { contentUri(it) }
        if (uris.size != batch.records.size) {
            _state.update { it.copy(message = "Non trovo più uno dei file scaricati: controllalo nel DOWNLOAD MANAGER.") }
            return
        }
        _state.update { it.copy(batch = null) }
        openMods()
        mods.addFiles(uris, "modrinth")
    }

    fun clearMessage() = _state.update { it.copy(message = null, error = null) }

    // ------------------------------------------------------------------ profilo

    private fun contentUri(record: DownloadRecord): Uri? {
        val file = engine.file(record)
        if (record.state != DownloadState.COMPLETED || !file.isFile) return null
        val app = getApplication<Application>()
        return FileProvider.getUriForFile(app, app.packageName + ".fileprovider", file)
    }

    private fun <T> safely(block: () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        record("Lettura del profilo", e)
        null
    }

    private fun record(what: String, e: Throwable) {
        try {
            container.errors.record(what, e.message ?: e.javaClass.simpleName, e.stackTraceToString())
        } catch (_: Throwable) {
        }
    }

    companion object {
        const val PAGE_SIZE = 20

        /** Tipi di contenuto che il catalogo sa portare nel MOD MANAGER (i modpack arrivano con il MODPACK MANAGER). */
        val KINDS: List<ProjectKind> = listOf(ProjectKind.MOD, ProjectKind.RESOURCE_PACK, ProjectKind.SHADER, ProjectKind.DATAPACK)

        fun labelOf(profile: Profile): String = profile.loader.label + " " + profile.minecraft

        /** I caricatori che possono leggere le mod di [profile], quello del profilo per primo (Quilt accetta anche Fabric). */
        fun loadersFor(profile: Profile): List<String> =
            ModLoader.values().filter { profile.loader.canLoadModsFor(it) }.sortedBy { if (it == profile.loader) 0 else 1 }.map { it.key }

        fun targetFor(kind: ProjectKind, profile: Profile): PlanTarget = PlanTarget(kind, loadersFor(profile), profile.minecraft)

        fun categoryOf(kind: ProjectKind): ItemCategory? = when (kind) {
            ProjectKind.MOD -> ItemCategory.MOD
            ProjectKind.RESOURCE_PACK -> ItemCategory.RESOURCE_PACK
            ProjectKind.SHADER -> ItemCategory.SHADER_PACK
            ProjectKind.DATAPACK -> ItemCategory.DATA_PACK
            ProjectKind.MODPACK -> null
        }

        /** Nome senza maiuscole né separatori, per confrontare l'identificativo di una mod con lo «slug» del catalogo. */
        private fun squash(text: String): String = text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    }

    private fun installedCheck(profile: Profile, kind: ProjectKind): InstalledCheck {
        val category = categoryOf(kind)
        val items = manager.items(profile.id).filter { category == null || it.category == category }
        return InstalledCheck { project, file ->
            val sameFile = items.any { item ->
                item.fileName == file.fileName || (file.sha1 != null && item.sha1.equals(file.sha1, ignoreCase = true))
            }
            when {
                sameFile -> InstalledState.SAME_FILE
                // Stessa mod ma altra versione: lo «slug» del catalogo coincide con un identificativo del file installato.
                // È un indizio, non una certezza: per questo la nota dice «forse».
                project.slug != null && items.any { item -> item.providedIds.any { squash(it) == squash(project.slug!!) } } ->
                    InstalledState.SAME_PROJECT
                else -> InstalledState.NO
            }
        }
    }
}
