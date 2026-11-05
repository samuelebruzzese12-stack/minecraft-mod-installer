package com.mmi.modinstaller

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mmi.modinstaller.core.CancelCheck
import com.mmi.modinstaller.core.ProgressCallback
import com.mmi.modinstaller.core.formatBytes
import com.mmi.modinstaller.data.AppContainer
import com.mmi.modinstaller.data.ErrorMessages
import com.mmi.modinstaller.data.ModsRepository
import com.mmi.modinstaller.jar.ContentKind as JavaKind
import com.mmi.modinstaller.jar.JarReport
import com.mmi.modinstaller.jar.ModLoader
import com.mmi.modinstaller.mods.ExportSummary
import com.mmi.modinstaller.mods.InstallPlan
import com.mmi.modinstaller.mods.InstallPlanner
import com.mmi.modinstaller.mods.InstallResult
import com.mmi.modinstaller.mods.ItemCategory
import com.mmi.modinstaller.mods.ModManager
import com.mmi.modinstaller.mods.Outcome
import com.mmi.modinstaller.mods.PlanCode
import com.mmi.modinstaller.mods.PlanDecision
import com.mmi.modinstaller.mods.Profile
import com.mmi.modinstaller.mods.ProfileCheck
import com.mmi.modinstaller.mods.ProfileDraft
import com.mmi.modinstaller.mods.ProfileItem
import com.mmi.modinstaller.mods.ProfileSuggester
import com.mmi.modinstaller.mods.Reconciliation
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

/** Esito mostrato su un file dopo il tentativo di installarlo. */
class PendingOutcome(val ok: Boolean, val text: String)

/**
 * Un file scelto per il MOD MANAGER: già copiato nella cartella temporanea, letto e confrontato con il profilo attivo,
 * ma non ancora installato.
 */
data class PendingFile(
    val key: String,
    /** Nome del file così come lo ha scelto l'utente (per i file di una raccolta, il nome del .jar al suo interno). */
    val name: String,
    /** Nome mostrato: per i file di una raccolta è «raccolta.zip › cartella/mod.jar». */
    val label: String,
    val sizeBytes: Long,
    /** Copia di lavoro nella cartella temporanea; null se il file non si è potuto leggere o è già stato installato. */
    val file: File?,
    val report: JarReport?,
    val error: String?,
    /** Controllo di compatibilità con il profilo attivo; null se non c'è nessun profilo o il file non è stato letto. */
    val plan: InstallPlan?,
    val sourceLabel: String,
    val outcome: PendingOutcome? = null
)

/** Esito di «VERIFICA PROFILO»: [names] traduce l'id di un file nel nome da mostrare. */
class CheckView(val check: ProfileCheck, val names: Map<String, String>, val notes: List<String>)

sealed class ModsDialog {
    /** Crea ([editing] = null) o modifica un profilo. I valori sono quelli di partenza dei campi della finestra. */
    class ProfileForm(
        val editing: Profile?,
        val name: String,
        val minecraft: String,
        val loader: ModLoader,
        val loaderVersion: String,
        val hint: String? = null,
        val error: String? = null
    ) : ModsDialog()

    class ChooseProfile : ModsDialog()
    class DeleteProfile(val profile: Profile, val itemCount: Int) : ModsDialog()
    class RemoveItem(val item: ProfileItem) : ModsDialog()
    class ConfirmInstall(val key: String) : ModsDialog()
    class Details(val itemId: String) : ModsDialog()
}

data class ModsState(
    val profiles: List<Profile> = emptyList(),
    val activeId: String? = null,
    val items: List<ProfileItem> = emptyList(),
    val tab: ItemCategory = ItemCategory.MOD,
    /** Descrizione dell'ultima operazione che si può annullare, o null se non ce n'è. */
    val lastOperation: String? = null,
    val sizeBytes: Long = 0L,
    val pending: List<PendingFile> = emptyList(),
    val busy: BusyState? = null,
    val message: String? = null,
    val dialog: ModsDialog? = null,
    val check: CheckView? = null,
    val loaded: Boolean = false
) {
    val active: Profile? get() = profiles.firstOrNull { it.id == activeId }
}

/**
 * Stato e azioni del MOD MANAGER. Il lavoro vero lo fa [ModManager] (profili, controlli di compatibilità, installazione,
 * annulla); qui ci sono i file scelti dall'utente in attesa di essere installati, le finestre e i messaggi.
 *
 * Regola: un errore imprevisto non deve mai chiudere l'app. Ogni operazione prende le eccezioni, le scrive nel registro
 * errori (visibile in DIAGNOSTICA) e le trasforma in un messaggio comprensibile.
 */
class ModsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)
    private val manager: ModManager = container.modManager
    private val repo = ModsRepository(application, container.paths)
    private val _state = MutableStateFlow(ModsState())
    val state: StateFlow<ModsState> = _state.asStateFlow()

    /** Operazione lunga in corso (lettura dei file, installazione): è quella che «Annulla» interrompe. */
    private var job: Job? = null
    private var counter = 0
    private var lastProgressNanos = 0L
    private val reloadLock = Any()

    init {
        refresh(reconcile = true)
    }

    override fun onCleared() {
        for (p in _state.value.pending) p.file?.parentFile?.deleteRecursively()
        super.onCleared()
    }

    // ------------------------------------------------------------------ lettura dello stato

    /** Rilegge profili e file; con [reconcile] allinea prima il profilo attivo a ciò che c'è davvero sul disco. */
    fun refresh(reconcile: Boolean = false) {
        quick("Lettura dei profili") { reload(reconcile) }
    }

    /** L'app torna in primo piano: i file del profilo possono essere stati cambiati da un gestore di file. */
    fun onAppResumed() {
        if (_state.value.loaded && _state.value.busy == null) refresh(reconcile = true)
    }

    /** Si è aperta la sezione MOD MANAGER. */
    fun onSectionOpened() {
        if (_state.value.busy == null) refresh(reconcile = true)
    }

    private fun reload(reconcile: Boolean) {
        synchronized(reloadLock) {
            val active = manager.activeProfile()
            var note: String? = null
            if (reconcile && active != null) {
                val rec = manager.reconcile(active.id)
                if (rec != null) note = describeChanges(rec)
            }
            val profiles = manager.profiles()
            val items = active?.let { manager.items(it.id) }.orEmpty()
            val last = active?.let { manager.lastOperation(it.id)?.label }
            val size = active?.let { manager.sizeOf(it.id) } ?: 0L
            _state.update {
                it.copy(
                    profiles = profiles, activeId = active?.id, items = items, lastOperation = last, sizeBytes = size,
                    loaded = true, message = note ?: it.message
                )
            }
            replan()
        }
    }

    private fun describeChanges(rec: Reconciliation): String? {
        val parts = ArrayList<String>()
        if (rec.missing.isNotEmpty()) {
            parts.add("Tolti dall'elenco perché non c'erano più sul telefono: " + rec.missing.joinToString(", ") { it.displayName })
        }
        if (rec.recovered.isNotEmpty()) {
            parts.add("Rimessi a posto dopo un'operazione interrotta: " + rec.recovered.joinToString(", ") { it.displayName })
        }
        if (rec.changedOutside.isNotEmpty()) {
            parts.add("Attivati o disattivati fuori dall'app: " + rec.changedOutside.joinToString(", ") { it.displayName })
        }
        return if (parts.isEmpty()) null else parts.joinToString(". ") + "."
    }

    /** Ricalcola il controllo di compatibilità di ogni file in attesa con lo stato attuale del profilo attivo. */
    private fun replan() {
        val profile = _state.value.active
        val items = if (profile != null) manager.items(profile.id) else emptyList()
        _state.update { s ->
            s.copy(
                pending = s.pending.map { p ->
                    val r = p.report
                    if (r == null) p else p.copy(plan = if (profile != null) InstallPlanner.plan(r, profile, items) else null)
                }
            )
        }
    }

    fun selectTab(category: ItemCategory) = _state.update { it.copy(tab = category) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun say(text: String) = _state.update { it.copy(message = text) }

    // ------------------------------------------------------------------ esecuzione sicura

    /** Errore imprevisto: registrato per DIAGNOSTICA e spiegato all'utente; l'app resta aperta. */
    private fun fail(what: String, e: Throwable) {
        try {
            container.errors.record(what, e.message ?: e.javaClass.simpleName, e.stackTraceToString())
        } catch (_: Throwable) {
        }
        say(ErrorMessages.describe(e))
    }

    /** Operazione breve fuori dal thread principale. */
    private fun quick(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(what, e)
            }
        }
    }

    /** Operazione lunga: schermata di avanzamento con Annulla, errori trasformati in messaggi. */
    private fun runBusy(
        title: String,
        cancelledText: String = "Operazione annullata.",
        block: suspend (cancel: CancelCheck, progress: ProgressCallback) -> Unit
    ) {
        if (_state.value.busy != null) {
            say("Attendi la fine dell'operazione in corso.")
            return
        }
        job = viewModelScope.launch {
            _state.update { it.copy(busy = BusyState(title, "", 0, 0), message = null) }
            try {
                val scopeContext = coroutineContext
                val cancel: CancelCheck = { scopeContext.ensureActive() }
                val progress: ProgressCallback = { done, total, note -> reportProgress(title, done, total, note) }
                block(cancel, progress)
            } catch (e: CancellationException) {
                if (_state.value.message == null) say(cancelledText)
            } catch (e: Throwable) {
                fail(title, e)
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    private fun reportProgress(title: String, done: Long, total: Long, note: String) {
        val now = System.nanoTime()
        if (done < total && now - lastProgressNanos < 100_000_000L) return
        lastProgressNanos = now
        _state.update { it.copy(busy = BusyState(title, note, done, total)) }
    }

    fun cancelBusy() {
        job?.cancel()
    }

    // ------------------------------------------------------------------ profili

    fun openProfileForm(suggestFrom: String? = null) {
        val pending = _state.value.pending.firstOrNull { it.key == suggestFrom }
        val report = pending?.report
        val suggestion = if (report != null) ProfileSuggester.fromReport(report) else null
        var name = if (suggestion != null) {
            listOfNotNull(suggestion.loader?.label, suggestion.minecraft).joinToString(" ")
        } else ""
        if (name.isBlank()) name = "Profilo"
        val taken = _state.value.profiles.map { it.name.lowercase() }.toSet()
        if (name.lowercase() in taken) {
            var n = 2
            while ("$name $n".lowercase() in taken) n++
            name = "$name $n"
        }
        val hint = if (pending != null && suggestion != null && (suggestion.loader != null || suggestion.minecraft != null)) {
            "Valori proposti dal file «" + pending.name + "»: controllali prima di creare il profilo."
        } else null
        _state.update {
            it.copy(
                dialog = ModsDialog.ProfileForm(
                    editing = null,
                    name = name,
                    minecraft = suggestion?.minecraft ?: "1.20.1",
                    loader = suggestion?.loader ?: ModLoader.FABRIC,
                    loaderVersion = "",
                    hint = hint
                )
            )
        }
    }

    fun openProfileEdit() {
        val p = _state.value.active ?: return
        _state.update { it.copy(dialog = ModsDialog.ProfileForm(p, p.name, p.minecraft, p.loader, p.loaderVersion.orEmpty())) }
    }

    fun saveProfile(editing: Profile?, draft: ProfileDraft) {
        val form = _state.value.dialog as? ModsDialog.ProfileForm
        quick("Salvataggio del profilo") {
            val result = if (editing == null) {
                val created = manager.createProfile(draft)
                if (created is Outcome.Ok) manager.setActive(created.value.id)
                created
            } else {
                manager.editProfile(editing.id, draft)
            }
            when (result) {
                is Outcome.Ok -> {
                    _state.update { it.copy(dialog = null, check = null) }
                    reload(false)
                    say(
                        if (editing == null) "Profilo «" + result.value.name + "» creato."
                        else "Profilo aggiornato. Usa VERIFICA PROFILO per ricontrollare i file."
                    )
                }
                is Outcome.Fail -> {
                    val text = result.message + (result.detail?.let { " ($it)" } ?: "")
                    _state.update {
                        it.copy(
                            dialog = ModsDialog.ProfileForm(
                                editing, draft.name, draft.minecraft, draft.loader, draft.loaderVersion.orEmpty(), form?.hint, text
                            )
                        )
                    }
                }
            }
        }
    }

    fun openChooseProfile() = _state.update { it.copy(dialog = ModsDialog.ChooseProfile()) }

    fun selectProfile(id: String) {
        quick("Cambio di profilo") {
            val r = manager.setActive(id)
            _state.update { it.copy(dialog = null, check = null) }
            reload(true)
            if (r is Outcome.Fail) say(r.message)
        }
    }

    fun askDeleteProfile() {
        val p = _state.value.active ?: return
        quick("Lettura del profilo") {
            val count = manager.items(p.id).size
            _state.update { it.copy(dialog = ModsDialog.DeleteProfile(p, count)) }
        }
    }

    fun deleteProfile(profile: Profile) {
        quick("Eliminazione del profilo") {
            val r = manager.deleteProfile(profile.id)
            _state.update { it.copy(dialog = null, check = null) }
            reload(false)
            say(if (r is Outcome.Ok) "Profilo «" + profile.name + "» eliminato." else (r as Outcome.Fail).message)
        }
    }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    // ------------------------------------------------------------------ file da installare

    /** Divide i file ricevuti da un'altra app: .jar e .mrpack sono per il MOD MANAGER, il resto per l'INSTALLER BEDROCK. */
    fun routeIncoming(uris: List<Uri>, openMods: () -> Unit, others: (List<Uri>) -> Unit) {
        viewModelScope.launch {
            val split = withContext(Dispatchers.IO) { uris.partition { looksLikeJavaFile(it) } }
            if (split.first.isNotEmpty()) {
                openMods()
                addFiles(split.first, "condiviso")
            }
            if (split.second.isNotEmpty()) others(split.second)
        }
    }

    private fun looksLikeJavaFile(uri: Uri): Boolean = try {
        repo.looksLikeJavaFile(uri)
    } catch (e: Exception) {
        false
    }

    /** Copia i file scelti nella cartella temporanea, li legge (senza eseguirli) e li confronta con il profilo attivo. */
    fun addFiles(uris: List<Uri>, sourceLabel: String = "file") {
        if (uris.isEmpty()) return
        runBusy("Controllo dei file") { cancel, progress ->
            val added = withContext(Dispatchers.IO) { analyzeAll(uris, sourceLabel, cancel, progress) }
            _state.update { it.copy(pending = it.pending + added, check = null) }
            withContext(Dispatchers.IO) { reload(false) }
            offerProfileIfNeeded(added)
        }
    }

    /** Senza nessun profilo non si può installare nulla: propone di crearne uno con i valori ricavati dal primo file. */
    private fun offerProfileIfNeeded(added: List<PendingFile>) {
        if (_state.value.profiles.isNotEmpty() || _state.value.dialog != null) return
        val first = added.firstOrNull { it.report != null && it.report.mods.isNotEmpty() } ?: return
        openProfileForm(first.key)
    }

    private fun analyzeAll(uris: List<Uri>, sourceLabel: String, cancel: CancelCheck, progress: ProgressCallback): List<PendingFile> {
        val batch = repo.newBatchDir()
        val out = ArrayList<PendingFile>()
        try {
            for ((index, uri) in uris.withIndex()) {
                cancel()
                out.addAll(analyzeOne(batch, index, uri, uris.size, sourceLabel, cancel, progress))
            }
        } catch (e: CancellationException) {
            batch.deleteRecursively()
            throw e
        }
        if (batch.list().isNullOrEmpty()) batch.delete()
        return out
    }

    private fun analyzeOne(
        batch: File,
        index: Int,
        uri: Uri,
        total: Int,
        sourceLabel: String,
        cancel: CancelCheck,
        progress: ProgressCallback
    ): List<PendingFile> {
        val picked = try {
            repo.describe(uri)
        } catch (e: Exception) {
            return listOf(failed("file", sourceLabel, ErrorMessages.describe(e)))
        }
        val dir = File(batch, "f$index")
        dir.mkdirs()
        val staged = File(dir, "source.bin")
        val prefix = if (total > 1) "(" + (index + 1) + " di " + total + ") " else ""
        try {
            repo.stage(picked, staged, cancel) { done, tot, _ -> progress(done, tot, prefix + "Copio «" + picked.displayName + "»") }
            val report = repo.analyze(staged, picked.displayName, cancel) { done, tot, _ ->
                progress(done, tot, prefix + "Leggo «" + picked.displayName + "»")
            }
            if (report.kind == JavaKind.MOD_BUNDLE && report.bundleJars.isNotEmpty()) {
                val list = expandBundle(batch, index, staged, report, picked.displayName, sourceLabel, cancel, progress)
                dir.deleteRecursively()
                return list
            }
            val size = if (picked.sizeBytes > 0) picked.sizeBytes else report.sizeBytes
            return listOf(PendingFile(nextKey(), picked.displayName, picked.displayName, size, staged, report, null, null, sourceLabel))
        } catch (e: CancellationException) {
            dir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            dir.deleteRecursively()
            container.errors.record("Lettura di un file Java", e.message ?: e.javaClass.simpleName, e.stackTraceToString())
            return listOf(failed(picked.displayName, sourceLabel, ErrorMessages.describe(e)))
        } catch (e: OutOfMemoryError) {
            dir.deleteRecursively()
            return listOf(failed(picked.displayName, sourceLabel, ErrorMessages.describe(e)))
        }
    }

    /** Una raccolta di mod in un solo archivio: ogni .jar al suo interno diventa un file da controllare per conto suo. */
    private fun expandBundle(
        batch: File,
        index: Int,
        archive: File,
        report: JarReport,
        archiveName: String,
        sourceLabel: String,
        cancel: CancelCheck,
        progress: ProgressCallback
    ): List<PendingFile> {
        val out = ArrayList<PendingFile>()
        for ((i, path) in report.bundleJars.withIndex()) {
            cancel()
            val name = path.substringAfterLast('/')
            val label = "$archiveName › $path"
            progress(i.toLong(), report.bundleJars.size.toLong(), "Apro «$name»")
            // Ogni .jar ha la sua cartella: quando uno viene installato o tolto, gli altri restano intatti.
            val dir = File(batch, "f$index-b$i")
            dir.mkdirs()
            val target = File(dir, "mod.jar")
            try {
                if (!repo.extractFromBundle(archive, path, target, cancel)) {
                    dir.deleteRecursively()
                    out.add(failed(label, sourceLabel, "Il file non si trova nell'archivio."))
                    continue
                }
                val r = repo.analyze(target, name, cancel, null)
                out.add(PendingFile(nextKey(), name, label, r.sizeBytes, target, r, null, null, sourceLabel))
            } catch (e: CancellationException) {
                dir.deleteRecursively()
                throw e
            } catch (e: Exception) {
                dir.deleteRecursively()
                out.add(failed(label, sourceLabel, ErrorMessages.describe(e)))
            }
        }
        return out
    }

    private fun failed(label: String, sourceLabel: String, message: String) =
        PendingFile(nextKey(), label, label, 0L, null, null, message, null, sourceLabel)

    private fun nextKey(): String {
        counter++
        return "p$counter"
    }

    fun dismissPending(key: String) {
        val p = _state.value.pending.firstOrNull { it.key == key } ?: return
        p.file?.parentFile?.deleteRecursively()
        _state.update { s ->
            s.copy(
                pending = s.pending.filter { it.key != key },
                dialog = if ((s.dialog as? ModsDialog.ConfirmInstall)?.key == key) null else s.dialog
            )
        }
    }

    /** Toglie dall'elenco i file già installati o che erano già nel profilo. */
    fun clearFinished() {
        val done = _state.value.pending.filter { it.outcome?.ok == true || it.plan?.decision == PlanDecision.ALREADY_INSTALLED }
        for (p in done) p.file?.parentFile?.deleteRecursively()
        _state.update { s -> s.copy(pending = s.pending - done.toSet()) }
    }

    fun clearAllPending() {
        for (p in _state.value.pending) p.file?.parentFile?.deleteRecursively()
        _state.update { it.copy(pending = emptyList(), dialog = null) }
    }

    /** Dopo un errore si può riprovare: si toglie l'esito e il file torna a essere installabile. */
    fun retry(key: String) {
        _state.update { s -> s.copy(pending = s.pending.map { if (it.key == key) it.copy(outcome = null) else it }) }
    }

    fun askConfirm(key: String) = _state.update { it.copy(dialog = ModsDialog.ConfirmInstall(key)) }

    // ------------------------------------------------------------------ installazione

    fun install(key: String, confirmed: Boolean) {
        val profile = _state.value.active
        val pending = _state.value.pending.firstOrNull { it.key == key }
        if (profile == null || pending == null) {
            say("Crea prima un profilo.")
            return
        }
        _state.update { it.copy(dialog = null) }
        runBusy("Installo «" + pending.label + "»", "Installazione annullata: non è cambiato nulla.") { cancel, progress ->
            val outcome = withContext(Dispatchers.IO) { doInstall(pending, profile.id, confirmed, cancel, progress) }
            markOutcome(key, outcome)
            withContext(Dispatchers.IO) { reload(false) }
        }
    }

    /** Installa in sequenza tutti i file pronti. Dopo ognuno rifà i controlli: una mod appena installata può sbloccarne un'altra. */
    fun installAllReady() {
        val profile = _state.value.active
        if (profile == null) {
            say("Crea prima un profilo.")
            return
        }
        runBusy("Installazione dei file pronti") { cancel, progress ->
            var installed = 0
            try {
                withContext(Dispatchers.IO) {
                    var guard = 0
                    while (guard++ < MAX_BATCH) {
                        cancel()
                        val next = _state.value.pending.firstOrNull { it.outcome == null && it.plan?.decision == PlanDecision.READY } ?: break
                        progress(0, 0, next.label)
                        val outcome = doInstall(next, profile.id, false, cancel, progress)
                        if (outcome.ok) installed++
                        markOutcome(next.key, outcome)
                        reload(false)
                    }
                }
            } catch (e: CancellationException) {
                say("Operazione annullata dopo $installed file installati: quelli sono nel profilo, gli altri no.")
                throw e
            }
            val left = _state.value.pending.count { it.outcome == null && it.plan?.decision != PlanDecision.ALREADY_INSTALLED }
            say(
                "Installati $installed file." +
                    if (left > 0) " Per gli altri $left serve la tua attenzione: leggi le note su ciascuno." else ""
            )
        }
    }

    private fun doInstall(p: PendingFile, profileId: String, confirmed: Boolean, cancel: CancelCheck, progress: ProgressCallback): PendingOutcome {
        val report = p.report ?: return PendingOutcome(false, p.error ?: "Il file non è stato letto.")
        val file = p.file
        if (file == null || !file.isFile) {
            return PendingOutcome(
                false,
                "La copia temporanea del file non c'è più (forse hai svuotato i file temporanei). Aggiungilo di nuovo."
            )
        }
        val result = manager.install(
            profileId, file, report, confirmed, origin = p.name, sourceLabel = p.sourceLabel,
            cancel = cancel, progress = { done, total -> progress(done, total, "Copio «" + p.name + "» nel profilo") }
        )
        return when (result) {
            is InstallResult.Installed -> {
                val replaced = result.replaced
                PendingOutcome(
                    true,
                    if (replaced != null) "Installato al posto di «" + replaced.displayName + "». Puoi annullare."
                    else "Installato nel profilo. Puoi annullare."
                )
            }
            is InstallResult.AlreadyInstalled -> PendingOutcome(true, "Era già nel profilo come «" + result.item.displayName + "».")
            is InstallResult.NotAllowed -> {
                val plan = result.plan
                if (plan.decision == PlanDecision.BLOCKED) {
                    PendingOutcome(false, "Non installato: " + (plan.blockingReasons.firstOrNull()?.message ?: "non è compatibile con il profilo."))
                } else {
                    PendingOutcome(false, "Serve la tua conferma per installarlo.")
                }
            }
            is InstallResult.Failed -> PendingOutcome(false, result.message + (result.detail?.let { ": $it" } ?: ""))
        }
    }

    private fun markOutcome(key: String, outcome: PendingOutcome) {
        val p = _state.value.pending.firstOrNull { it.key == key }
        if (outcome.ok) p?.file?.parentFile?.deleteRecursively()
        _state.update { s ->
            s.copy(pending = s.pending.map { if (it.key == key) it.copy(outcome = outcome, file = if (outcome.ok) null else it.file) else it })
        }
    }

    // ------------------------------------------------------------------ file del profilo

    fun setEnabled(item: ProfileItem, enabled: Boolean) {
        val profile = _state.value.active ?: return
        quick("Attivazione di un file") {
            val r = manager.setEnabled(profile.id, item.id, enabled)
            _state.update { it.copy(check = null) }
            reload(false)
            if (r is Outcome.Fail) say(r.message + (r.detail?.let { " ($it)" } ?: ""))
        }
    }

    fun askRemove(item: ProfileItem) = _state.update { it.copy(dialog = ModsDialog.RemoveItem(item)) }

    fun remove(item: ProfileItem) {
        val profile = _state.value.active ?: return
        quick("Rimozione di un file") {
            val r = manager.remove(profile.id, item.id)
            _state.update { it.copy(dialog = null, check = null) }
            reload(false)
            say(
                if (r is Outcome.Ok) "«" + item.displayName + "» tolto dal profilo. Puoi annullare con ANNULLA ULTIMA OPERAZIONE."
                else (r as Outcome.Fail).message
            )
        }
    }

    fun showDetails(item: ProfileItem) = _state.update { it.copy(dialog = ModsDialog.Details(item.id)) }

    fun undo() {
        val profile = _state.value.active ?: return
        quick("Annullamento di un'operazione") {
            val r = manager.undo(profile.id)
            _state.update { it.copy(check = null) }
            reload(false)
            say(
                if (r is Outcome.Ok) "Annullato: " + r.value + "."
                else (r as Outcome.Fail).message + (r.detail?.let { " ($it)" } ?: "")
            )
        }
    }

    fun checkProfile() {
        val profile = _state.value.active ?: return
        quick("Verifica del profilo") {
            val r = manager.check(profile.id)
            if (r == null) {
                say("Il profilo non esiste più.")
            } else {
                val names = manager.items(profile.id).associate { it.id to it.displayName }
                val notes = ArrayList<String>()
                describeChanges(r.second)?.let { notes.add(it) }
                if (r.second.strays.isNotEmpty()) {
                    notes.add("File nelle cartelle del profilo che l'app non conosce (non vengono toccati): " + r.second.strays.joinToString(", "))
                }
                _state.update { it.copy(check = CheckView(r.first, names, notes)) }
            }
            reload(false)
        }
    }

    fun closeCheck() = _state.update { it.copy(check = null) }

    // ------------------------------------------------------------------ esportazione

    /** Scrive in [target], scelto dall'utente con il selettore di sistema, uno ZIP con i file attivi del profilo. */
    fun exportZip(target: Uri) {
        val profile = _state.value.active
        if (profile == null) {
            say("Crea prima un profilo.")
            return
        }
        runBusy("Esporto «" + profile.name + "»", "Esportazione annullata: lo ZIP incompleto è stato cancellato.") { cancel, progress ->
            val outcome = withContext(Dispatchers.IO) {
                var finished = false
                try {
                    val stream = repo.openOutput(target) ?: return@withContext Outcome.Fail("Non riesco a scrivere nel punto scelto.")
                    val r = stream.use { out ->
                        manager.exportZip(profile.id, out, cancel) { done, total -> progress(done, total, "Scrivo i file nello ZIP") }
                    }
                    finished = r is Outcome.Ok
                    r
                } finally {
                    if (!finished) repo.deleteDocument(target)
                }
            }
            when (outcome) {
                is Outcome.Ok -> say(exportMessage(outcome.value))
                is Outcome.Fail -> say(outcome.message + (outcome.detail?.let { " ($it)" } ?: ""))
            }
        }
    }

    private fun exportMessage(s: ExportSummary): String {
        if (s.files == 0 && s.missing.isEmpty()) return "Il profilo non ha file attivi: lo ZIP è vuoto."
        val sb = StringBuilder("Esportati " + s.files + " file (" + formatBytes(s.bytes) + ").")
        if (s.missing.isNotEmpty()) sb.append(" Non c'erano più sul telefono e non sono nello ZIP: " + s.missing.joinToString(", ") + ".")
        if (s.changed.isNotEmpty()) {
            sb.append(" Risultano diversi da quando li hai installati (modificati fuori dall'app): " + s.changed.joinToString(", ") + ".")
        }
        sb.append(" Estrai le cartelle nella cartella di gioco del tuo programma di avvio. L'app non può verificare che il gioco li carichi.")
        return sb.toString()
    }

    companion object {
        /** Tetto di sicurezza per «INSTALLA I PRONTI», così un errore di logica non può girare all'infinito. */
        private const val MAX_BATCH = 2000

        /** Il piano indica che serve un profilo diverso (altro caricatore o altra versione di Minecraft)? */
        fun suggestsOtherProfile(plan: InstallPlan?): Boolean =
            plan != null && plan.issues.any { it.code == PlanCode.LOADER_MISMATCH || it.code == PlanCode.MC_MISMATCH }

        /** Testo breve con la dimensione, per le righe dell'elenco. */
        fun sizeText(bytes: Long): String = if (bytes > 0) formatBytes(bytes) else ""
    }
}
