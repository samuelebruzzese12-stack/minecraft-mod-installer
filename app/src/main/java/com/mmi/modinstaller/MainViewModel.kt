package com.mmi.modinstaller

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mmi.modinstaller.core.AnalysisReport
import com.mmi.modinstaller.core.BackupInfo
import com.mmi.modinstaller.core.CancelCheck
import com.mmi.modinstaller.core.ContentItem
import com.mmi.modinstaller.core.InstallState
import com.mmi.modinstaller.core.InstalledEntry
import com.mmi.modinstaller.core.ItemStatus
import com.mmi.modinstaller.core.OutputFormat
import com.mmi.modinstaller.core.ProgressCallback
import com.mmi.modinstaller.core.RecentFile
import com.mmi.modinstaller.core.Severity
import com.mmi.modinstaller.core.formatBytes
import com.mmi.modinstaller.data.DeleteResult
import com.mmi.modinstaller.data.ErrorMessages
import com.mmi.modinstaller.data.InstallerRepository
import com.mmi.modinstaller.data.SendResult
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
import java.io.File

/**
 * Stato e azioni dell'app.
 *
 * Regola fondamentale: Minecraft NON dice all'app se un'importazione è riuscita. Un contenuto consegnato resta
 * "inviato, da confermare" finché è l'utente a dichiarare cosa ha visto nel gioco. Nessun messaggio dell'app
 * afferma che un'installazione è riuscita senza quella conferma.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = InstallerRepository(application)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private class QueuedInstall(val sessionDir: File, val sourceName: String, val item: ContentItem)

    /**
     * Un invio a Minecraft: un solo file, che può unire più contenuti. Un add-on è quasi sempre fatto da più pacchetti
     * (comportamento + risorse) che da soli non funzionano, quindi vengono consegnati insieme.
     */
    private class InstallUnit(val parts: List<QueuedInstall>)

    private var job: Job? = null
    private val queue = ArrayList<InstallUnit>()
    private var queueTotal = 0
    private var batchActive = false
    private var confirmedCount = 0
    private var failedCount = 0
    private var unknownCount = 0
    private var leftForMinecraft = false
    private var lastDelete: DeleteResult? = null
    private var noticeCounter = 0L
    private var lastProgressNanos = 0L

    init {
        refreshMinecraft()
        refreshLists()
        refreshBackups()
        if (repo.store.recoveredFromCorruption) {
            notice("L'elenco salvato dall'app era danneggiato ed è stato azzerato.")
        }
    }

    // ------------------------------------------------------------------ navigazione

    fun go(screen: Screen) {
        if (screen == Screen.BACKUPS) refreshBackups()
        _state.update { it.copy(screen = screen, query = "") }
        refreshLists()
    }

    /** Gestisce il tasto indietro. Restituisce false se non c'è nulla da chiudere (l'app può uscire). */
    fun back(): Boolean {
        val s = _state.value
        if (s.busy != null) {
            cancelBusy()
            return true
        }
        if (s.dialog != null) {
            dismissDialog()
            return true
        }
        return when (s.screen) {
            Screen.HOME -> false
            Screen.REPORT -> {
                discardReports()
                _state.update { it.copy(screen = Screen.HOME, query = "") }
                true
            }
            else -> {
                _state.update { it.copy(screen = Screen.HOME, query = "") }
                true
            }
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun cancelBusy() {
        job?.cancel()
    }

    // ------------------------------------------------------------------ ciclo di vita dell'app

    fun onAppStarted() {
        refreshMinecraft()
        refreshLists()
        refreshBackups()
        val s = _state.value
        if (s.awaiting != null && leftForMinecraft) {
            leftForMinecraft = false
            _state.update { it.copy(confirmVisible = true) }
        }
    }

    fun onAppStopped() {
        if (_state.value.awaiting != null) leftForMinecraft = true
    }

    // ------------------------------------------------------------------ scelta e analisi dei file

    fun openFiles(uris: List<Uri>, purpose: PickPurpose) {
        if (uris.isEmpty()) return
        if (_state.value.busy != null) {
            notice("Attendi la fine dell'operazione in corso.")
            return
        }
        discardReports()
        runBusy("Analisi in corso") { cancel, progress ->
            val results = ArrayList<FileReport>()
            try {
                for ((index, uri) in uris.withIndex()) {
                    cancel()
                    results.add(analyzeOne(index, uri, purpose, cancel, progress))
                }
            } catch (e: CancellationException) {
                for (r in results) r.sessionDir?.deleteRecursively()
                throw e
            }
            publishReports(results)
        }
    }

    fun openRecent(recent: RecentFile) {
        openFiles(listOf(Uri.parse(recent.uri)), PickPurpose.ANY)
    }

    private suspend fun analyzeOne(
        index: Int,
        uri: Uri,
        purpose: PickPurpose,
        cancel: CancelCheck,
        progress: ProgressCallback
    ): FileReport {
        var name = "file"
        var size = 0L
        var dir: File? = null
        try {
            val picked = withContext(Dispatchers.IO) { repo.describe(uri) }
            name = picked.displayName
            size = picked.sizeBytes
            val sessionDir = withContext(Dispatchers.IO) { repo.newSessionDir() }
            dir = sessionDir
            val report = withContext(Dispatchers.IO) {
                repo.rememberRecent(picked)
                repo.analyze(picked, sessionDir, cancel, progress)
            }
            val title = name
            withContext(Dispatchers.IO) { repo.store.addHistory(title, "Analizzato", summarize(report)) }
            return FileReport(index, name, report.source.sizeBytes, report, null, sessionDir, purposeNote(report, purpose))
        } catch (e: CancellationException) {
            dir?.deleteRecursively()
            throw e
        } catch (e: Throwable) {
            dir?.deleteRecursively()
            val message = ErrorMessages.describe(e)
            val title = name
            withContext(Dispatchers.IO) { repo.store.addHistory(title, "Analisi non riuscita", message) }
            return FileReport(index, name, size, null, message, null, null)
        }
    }

    private fun summarize(report: AnalysisReport): String {
        if (report.items.isEmpty()) {
            val first = report.findings.firstOrNull { it.severity == Severity.ERROR } ?: report.findings.firstOrNull()
            return first?.message ?: "Nessun contenuto riconosciuto"
        }
        return report.items.joinToString("; ") { "${it.title} (${it.kind.label})" }
    }

    private fun purposeNote(report: AnalysisReport, purpose: PickPurpose): String? {
        if (purpose == PickPurpose.ANY || report.items.isEmpty()) return null
        if (report.items.any { it.kind in purpose.expected }) return null
        val found = report.items.map { it.kind.label }.distinct().joinToString(", ")
        return "Hai usato «IMPORTA ${purpose.label.uppercase()}», ma il file contiene: $found. Puoi comunque installarlo."
    }

    private fun publishReports(results: List<FileReport>) {
        val selected = HashSet<String>()
        for (f in results) {
            val report = f.report ?: continue
            for (item in report.items) {
                // Due contenuti con lo stesso UUID non possono convivere in Minecraft: si spunta solo il primo.
                val ready = item.status == ItemStatus.READY || item.status == ItemStatus.WARNINGS
                if (ready && item.sameUuidAs == null) selected.add(itemKey(f.index, item))
            }
        }
        _state.update {
            it.copy(files = results, selected = selected, forced = emptySet(), screen = Screen.REPORT, query = "")
        }
        refreshLists()
    }

    private fun discardReports() {
        queue.clear()
        // Un'eventuale risposta tardiva a un invio precedente non deve toccare i rapporti nuovi.
        batchActive = false
        val dirs = _state.value.files.mapNotNull { it.sessionDir }
        _state.update { it.copy(files = emptyList(), selected = emptySet(), forced = emptySet()) }
        if (dirs.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                for (d in dirs) d.deleteRecursively()
            }
        }
    }

    // ------------------------------------------------------------------ selezione nel rapporto

    fun toggleSelected(key: String) {
        _state.update { s ->
            val next = HashSet(s.selected)
            if (!next.remove(key)) next.add(key)
            s.copy(selected = next)
        }
    }

    fun setForced(key: String, on: Boolean) {
        _state.update { s ->
            val forced = HashSet(s.forced)
            val selected = HashSet(s.selected)
            if (on) {
                forced.add(key)
                selected.add(key)
            } else {
                forced.remove(key)
                selected.remove(key)
            }
            s.copy(forced = forced, selected = selected)
        }
    }

    fun selectAll(on: Boolean) {
        _state.update { s ->
            val keys = HashSet<String>()
            if (on) {
                for (f in s.files) {
                    val report = f.report ?: continue
                    for (item in report.items) {
                        val key = itemKey(f.index, item)
                        if (item.installable && (item.status != ItemStatus.OVERRIDE || key in s.forced)) keys.add(key)
                    }
                }
            }
            s.copy(selected = keys)
        }
    }

    // ------------------------------------------------------------------ installazione

    /** Prepara e consegna a Minecraft, uno alla volta, i contenuti spuntati. */
    fun install() {
        val s = _state.value
        if (s.busy != null) return
        if (s.awaiting != null) {
            // C'è già un invio senza risposta: si chiede prima l'esito, poi si prosegue.
            _state.update { it.copy(confirmVisible = true) }
            return
        }
        if (!repo.bridge.isInstalled()) {
            _state.update { it.copy(dialog = DialogState.MinecraftMissing) }
            return
        }
        val planned = plannedItems(s)
        if (planned.isEmpty()) {
            notice("Seleziona almeno un contenuto installabile.")
            return
        }
        val queued = planned.map { QueuedInstall(it.sessionDir, it.file.name, it.item) }
        // Tutti i pacchetti spuntati partono in un solo file; mappe e template (che non si possono unire) uno per volta.
        val (packs, others) = queued.partition {
            it.item.output == OutputFormat.MCPACK || it.item.output == OutputFormat.MCADDON
        }
        val units = ArrayList<InstallUnit>()
        if (packs.isNotEmpty()) units.add(InstallUnit(packs))
        for (q in others) units.add(InstallUnit(listOf(q)))
        queue.clear()
        queue.addAll(units)
        queueTotal = units.size
        batchActive = true
        confirmedCount = 0
        failedCount = 0
        unknownCount = 0
        sendNext()
    }

    private fun bundleTitle(unit: InstallUnit): String {
        val sources = unit.parts.map { it.sourceName }.distinct()
        if (sources.size == 1) {
            val base = sources[0].substringBeforeLast('.', sources[0]).trim()
            if (base.isNotEmpty()) return base
        }
        return "Contenuti (${unit.parts.size})"
    }

    private fun sendNext() {
        if (queue.isEmpty()) {
            finishBatch()
            return
        }
        val unit = queue.removeAt(0)
        val position = queueTotal - queue.size
        val title = when {
            queueTotal > 1 -> "Preparazione $position di $queueTotal"
            unit.parts.size > 1 -> "Preparazione di ${unit.parts.size} contenuti"
            else -> "Preparazione del pacchetto"
        }
        runBusy(title) { cancel, progress ->
            val prepared = withContext(Dispatchers.IO) {
                val first = unit.parts.first()
                val p = if (unit.parts.size == 1) {
                    repo.prepare(first.item, first.sourceName, first.sessionDir, cancel, progress)
                } else {
                    repo.prepareBundle(
                        unit.parts.map { it.item }, bundleTitle(unit),
                        unit.parts.map { it.sourceName }.distinct().joinToString(", "),
                        first.sessionDir, cancel, progress
                    )
                }
                repo.store.putInstalled(p.entry)
                repo.store.addHistory(
                    p.entry.title,
                    "Pacchetto preparato",
                    p.prepared.fileName + " · " + formatBytes(p.prepared.sizeBytes),
                    p.prepared.notes.joinToString("\n").ifEmpty { null }
                )
                p
            }
            refreshLists()
            val pk = prepared.prepared
            deliver(prepared.entry, DeliveryInfo(pk.packNames, pk.entryCount, pk.sourceFileCount, pk.itemCount))
        }
    }

    /** Apre Minecraft con il file. Da qui in poi lo stato è "inviato": la conferma spetta all'utente. */
    private suspend fun deliver(entry: InstalledEntry, info: DeliveryInfo? = null) {
        val delivery = info ?: DeliveryInfo(entry.packs.map { it.name }, 0, 0, 1)
        _state.update { it.copy(awaiting = entry, delivery = delivery, queueRemaining = queue.size, confirmVisible = false) }
        leftForMinecraft = false
        when (val result = repo.bridge.send(repo.preparedFile(entry))) {
            is SendResult.Started -> {
                val detail = "Metodo: " + result.attempt.mode.name
                withContext(Dispatchers.IO) {
                    repo.store.addHistory(entry.title, "Inviato a Minecraft", "In attesa della tua conferma", detail)
                }
            }
            is SendResult.NotInstalled -> failDelivery(entry, "Minecraft non è installato.")
            is SendResult.Failed -> failDelivery(entry, result.message)
        }
    }

    private suspend fun failDelivery(entry: InstalledEntry, reason: String) {
        queue.clear()
        batchActive = false
        _state.update { it.copy(awaiting = null, confirmVisible = false, queueRemaining = 0) }
        withContext(Dispatchers.IO) {
            repo.store.setInstallState(entry.id, InstallState.FAILED)
            repo.store.addHistory(entry.title, "Invio a Minecraft non riuscito", reason)
        }
        refreshLists()
        notice("Non sono riuscito a consegnare «${entry.title}» a Minecraft: $reason Il pacchetto resta in MOD INSTALLATI.")
    }

    /**
     * Risposta dell'utente alla domanda "Minecraft ha importato il contenuto?".
     * [result] = CONFIRMED (sì), FAILED (no) oppure null (non so / più tardi).
     */
    fun answerConfirmation(result: InstallState?) {
        val entry = _state.value.awaiting ?: return
        _state.update { it.copy(awaiting = null, confirmVisible = false) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { recordAnswer(entry, result) }
            refreshLists()
            if (!batchActive) {
                notice(answerText(entry, result))
                return@launch
            }
            // Anche con «Non lo so» si prosegue: l'utente può fermare gli invii rimasti con «Non inviare gli altri».
            if (queue.isNotEmpty()) sendNext() else finishBatch()
        }
    }

    /** L'utente non vuole più che partano gli invii rimasti in coda (quello in attesa di risposta resta). */
    fun dropRemaining() {
        if (queue.isEmpty()) return
        queue.clear()
        _state.update { it.copy(queueRemaining = 0) }
    }

    private fun recordAnswer(entry: InstalledEntry, result: InstallState?) {
        when (result) {
            InstallState.CONFIRMED -> {
                repo.store.setInstallState(entry.id, InstallState.CONFIRMED)
                repo.store.addHistory(entry.title, "Importazione confermata", "Hai confermato che Minecraft ha importato il contenuto")
                confirmedCount++
            }
            InstallState.FAILED -> {
                repo.store.setInstallState(entry.id, InstallState.FAILED)
                repo.store.addHistory(entry.title, "Importazione non riuscita", "Hai segnalato che Minecraft non ha importato il contenuto")
                failedCount++
            }
            else -> {
                repo.store.addHistory(entry.title, "Conferma rimandata", "Lo stato resta «inviato, da confermare»")
                unknownCount++
            }
        }
    }

    private fun answerText(entry: InstalledEntry, result: InstallState?): String = when (result) {
        InstallState.CONFIRMED -> "«${entry.title}»: importazione confermata da te."
        InstallState.FAILED -> "«${entry.title}»: segnato come non importato."
        else -> "«${entry.title}»: resta da confermare."
    }

    private fun finishBatch() {
        val summary = batchSummary()
        batchActive = false
        queue.clear()
        discardReports()
        _state.update { it.copy(screen = Screen.HOME, awaiting = null, confirmVisible = false, queueRemaining = 0) }
        notice(summary)
    }

    private fun batchSummary(): String {
        val parts = ArrayList<String>()
        if (confirmedCount > 0) parts.add("$confirmedCount importat${if (confirmedCount == 1) "o" else "i"} (confermat${if (confirmedCount == 1) "o" else "i"} da te)")
        if (failedCount > 0) parts.add("$failedCount non importat${if (failedCount == 1) "o" else "i"}")
        if (unknownCount > 0) parts.add("$unknownCount da confermare")
        if (parts.isEmpty()) return "Nessun contenuto inviato."
        return "Installazione terminata: " + parts.joinToString(", ") + "."
    }

    // ------------------------------------------------------------------ contenuti gestiti

    fun showEntryActions(entry: InstalledEntry) {
        _state.update { it.copy(dialog = DialogState.EntryActions(entry)) }
    }

    fun askDelete(entry: InstalledEntry) {
        _state.update { it.copy(dialog = DialogState.ConfirmDelete(entry)) }
    }

    /** Segna a mano l'esito di un contenuto già inviato (dalla lista o dal banner). */
    fun markEntry(id: String, result: InstallState) {
        dismissDialog()
        if (_state.value.awaiting?.id == id) {
            answerConfirmation(result)
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val entry = repo.store.setInstallState(id, result)
                if (entry != null) {
                    val action = if (result == InstallState.CONFIRMED) "Importazione confermata" else "Importazione non riuscita"
                    repo.store.addHistory(entry.title, action, "Segnalato dall'utente")
                }
            }
            refreshLists()
        }
    }

    /** Consegna di nuovo a Minecraft un pacchetto già preparato (es. se l'importazione era fallita). */
    fun resend(id: String) {
        dismissDialog()
        val s = _state.value
        if (s.busy != null || s.awaiting != null) {
            notice("Prima rispondi alla domanda sull'invio in corso.")
            return
        }
        val entry = repo.store.findInstalled(id) ?: return
        if (!repo.bridge.isInstalled()) {
            _state.update { it.copy(dialog = DialogState.MinecraftMissing) }
            return
        }
        if (!repo.preparedFile(entry).isFile) {
            notice("Il file «${entry.fileName}» non è più nell'app: importa di nuovo il contenuto.")
            return
        }
        batchActive = false
        queue.clear()
        viewModelScope.launch {
            val sent = withContext(Dispatchers.IO) {
                repo.store.setInstallState(id, InstallState.SENT)
                repo.store.addHistory(entry.title, "Nuovo invio a Minecraft", "Ripetuto dall'elenco dei contenuti gestiti")
                repo.store.findInstalled(id)
            }
            if (sent == null) return@launch
            refreshLists()
            deliver(sent)
        }
    }

    fun deleteEntry(id: String) {
        dismissDialog()
        if (_state.value.busy != null) return
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repo.delete(id) }
                if (result != null) {
                    lastDelete = result
                    if (_state.value.awaiting?.id == id) {
                        _state.update { it.copy(awaiting = null, confirmVisible = false) }
                    }
                    refreshLists()
                    refreshBackups()
                    notice("«${result.entry.title}» eliminato dall'app.", "ANNULLA")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                notice("Eliminazione non riuscita: " + ErrorMessages.describe(e))
            }
        }
    }

    fun noticeAction(id: Long) {
        val n = _state.value.notice
        if (n != null && n.id == id && n.actionLabel != null) undoDelete()
    }

    private fun undoDelete() {
        val result = lastDelete ?: return
        lastDelete = null
        viewModelScope.launch {
            try {
                val entry = withContext(Dispatchers.IO) { repo.undoDelete(result) }
                refreshLists()
                refreshBackups()
                notice("«${entry.title}» ripristinato.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                notice("Ripristino non riuscito: " + ErrorMessages.describe(e))
            }
        }
    }

    // ------------------------------------------------------------------ backup

    fun refreshBackups() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                try {
                    repo.backups.list()
                } catch (e: Exception) {
                    emptyList<BackupInfo>()
                }
            }
            _state.update { it.copy(backups = list) }
        }
    }

    /** Riapre il backup come se fosse un file appena scelto: lo si analizza e si può installare di nuovo. */
    fun restoreBackup(info: BackupInfo) {
        if (_state.value.busy != null) return
        discardReports()
        runBusy("Ripristino del backup") { cancel, progress ->
            var dir: File? = null
            try {
                val sessionDir = withContext(Dispatchers.IO) { repo.newSessionDir() }
                dir = sessionDir
                val report = withContext(Dispatchers.IO) { repo.analyzeBackup(info, sessionDir, cancel, progress) }
                withContext(Dispatchers.IO) { repo.store.addHistory(info.title, "Backup riaperto", summarize(report)) }
                publishReports(listOf(FileReport(0, info.fileName, info.sizeBytes, report, null, sessionDir, null)))
            } catch (e: Throwable) {
                dir?.deleteRecursively()
                throw e
            }
        }
    }

    fun askDeleteBackup(info: BackupInfo) {
        _state.update { it.copy(dialog = DialogState.ConfirmDeleteBackup(info)) }
    }

    fun deleteBackup(id: String) {
        dismissDialog()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    repo.backups.delete(id)
                } catch (e: Exception) {
                    // già eliminato
                }
            }
            refreshBackups()
        }
    }

    // ------------------------------------------------------------------ elenchi

    fun removeRecent(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.forgetRecent(uri)
            refreshLists()
        }
    }

    fun askClearRecents() {
        _state.update { it.copy(dialog = DialogState.ConfirmClearRecents) }
    }

    fun clearRecents() {
        dismissDialog()
        viewModelScope.launch(Dispatchers.IO) {
            for (r in repo.store.recents()) repo.forgetRecent(r.uri)
            repo.store.clearRecents()
            refreshLists()
        }
    }

    fun askClearHistory() {
        _state.update { it.copy(dialog = DialogState.ConfirmClearHistory) }
    }

    fun clearHistory() {
        dismissDialog()
        viewModelScope.launch(Dispatchers.IO) {
            repo.store.clearHistory()
            refreshLists()
        }
    }

    // ------------------------------------------------------------------ Minecraft

    fun openMinecraft() {
        if (!repo.bridge.openMinecraft()) notice("Non riesco ad aprire Minecraft: controlla che sia installato.")
    }

    fun openStore() {
        dismissDialog()
        if (!repo.bridge.openStore()) notice("Non riesco ad aprire il Play Store.")
    }

    // ------------------------------------------------------------------ dialoghi e messaggi

    fun dismissDialog() {
        _state.update { if (it.dialog != null) it.copy(dialog = null) else it }
    }

    /** Mostra un messaggio breve in basso (usato anche dall'activity). */
    fun showMessage(text: String) {
        notice(text)
    }

    fun noticeShown(id: Long) {
        _state.update { if (it.notice?.id == id) it.copy(notice = null) else it }
    }

    private fun notice(text: String, action: String? = null) {
        noticeCounter++
        val n = Notice(noticeCounter, text, action)
        _state.update { it.copy(notice = n) }
    }

    // ------------------------------------------------------------------ supporto

    private fun refreshMinecraft() {
        val app = repo.bridge.installedApps().firstOrNull()
        val status = MinecraftStatus(app != null, app?.label, app?.versionName)
        _state.update { it.copy(minecraft = status) }
    }

    private fun refreshLists() {
        _state.update {
            it.copy(
                recents = repo.store.recents(),
                installed = repo.store.installed(),
                history = repo.store.history()
            )
        }
    }

    private fun reportProgress(done: Long, total: Long, note: String) {
        val now = System.nanoTime()
        if (now - lastProgressNanos < 100_000_000L) return
        lastProgressNanos = now
        _state.update { s ->
            val b = s.busy
            if (b == null) s else s.copy(busy = BusyState(b.title, note, done, total))
        }
    }

    /** Esegue [block] mostrando la schermata di avanzamento con Annulla; gli errori diventano messaggi per l'utente. */
    private fun runBusy(title: String, block: suspend (cancel: CancelCheck, progress: ProgressCallback) -> Unit) {
        job = viewModelScope.launch {
            _state.update { it.copy(busy = BusyState(title, "", 0, 0)) }
            try {
                val scopeContext = coroutineContext
                val cancel: CancelCheck = { scopeContext.ensureActive() }
                val progress: ProgressCallback = { done, total, note -> reportProgress(done, total, note) }
                block(cancel, progress)
            } catch (e: CancellationException) {
                queue.clear()
                batchActive = false
                notice("Operazione annullata.")
            } catch (e: Throwable) {
                queue.clear()
                batchActive = false
                notice(ErrorMessages.describe(e))
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }
}
