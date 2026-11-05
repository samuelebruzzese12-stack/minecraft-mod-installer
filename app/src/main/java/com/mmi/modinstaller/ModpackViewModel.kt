package com.mmi.modinstaller

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mmi.modinstaller.core.CancelCheck
import com.mmi.modinstaller.core.ProgressCallback
import com.mmi.modinstaller.core.SafetyLimits
import com.mmi.modinstaller.data.AppContainer
import com.mmi.modinstaller.data.ErrorMessages
import com.mmi.modinstaller.data.ModsRepository
import com.mmi.modinstaller.download.DownloadEngine
import com.mmi.modinstaller.mods.ModManager
import com.mmi.modinstaller.mods.Outcome
import com.mmi.modinstaller.mods.ProfileDraft
import com.mmi.modinstaller.modpack.InstallProgress
import com.mmi.modinstaller.modpack.InstallRequest
import com.mmi.modinstaller.modpack.ModpackInstaller
import com.mmi.modinstaller.modpack.ModpackPlan
import com.mmi.modinstaller.modpack.ModpackPlanner
import com.mmi.modinstaller.modpack.ModpackRecord
import com.mmi.modinstaller.modpack.ModpackRepository
import com.mmi.modinstaller.modpack.ModpackResult
import com.mmi.modinstaller.modpack.ModrinthPackReader
import com.mmi.modinstaller.modpack.Pack
import com.mmi.modinstaller.modpack.PackReadResult
import com.mmi.modinstaller.modpack.Phase
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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Un modpack letto e confrontato con le regole sui siti, in attesa che l'utente decida.
 * Se [resume] non è null si completa l'installazione di quel modpack nel suo profilo, invece di crearne uno nuovo.
 */
data class PendingPack(
    /** Copia di lavoro del .mrpack (cartella temporanea) o, per una ripresa, la copia conservata dall'app. */
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val pack: Pack,
    val plan: ModpackPlan,
    /** Nome proposto per il nuovo profilo (modificabile). */
    val profileName: String,
    val resume: ModpackRecord?,
    /** Spazio libero nella memoria dell'app, quando è stato letto il modpack. */
    val freeBytes: Long,
    /** Perché il profilo non si è potuto creare (es. nome già usato), da mostrare vicino al campo. */
    val nameError: String? = null
) {
    /** Byte da avere liberi per scaricare tutto (come li calcola l'installazione). */
    val neededBytes: Long get() = plan.downloadBytes + SPACE_MARGIN

    val lowOnSpace: Boolean get() = freeBytes in 1 until neededBytes

    companion object {
        const val SPACE_MARGIN = 32L * 1024 * 1024
    }
}

/** Come è andata l'ultima installazione: resta in vista finché l'utente non la chiude. */
class PackOutcome(
    val title: String,
    val result: ModpackResult,
    val recordId: String?,
    val profileId: String?
)

/** Un modpack dell'elenco, con il nome del suo profilo (null se il profilo è stato eliminato). */
class RecordRow(val record: ModpackRecord, val profileName: String?)

sealed class ModpackDialog {
    class Consent(val host: String) : ModpackDialog()
    class RemoveRecord(val record: ModpackRecord) : ModpackDialog()
}

data class ModpackState(
    val rows: List<RecordRow> = emptyList(),
    val pending: PendingPack? = null,
    val outcome: PackOutcome? = null,
    val busy: BusyState? = null,
    val message: String? = null,
    val dialog: ModpackDialog? = null,
    val loaded: Boolean = false
)

/**
 * Stato e azioni del MODPACK MANAGER. Il lavoro vero lo fanno [ModpackInstaller] (scarica, verifica, applica le impostazioni e
 * registra i file nel MOD MANAGER), [DownloadEngine] (download con impronta) e [ModManager] (profili); qui ci sono il modpack
 * scelto in attesa della decisione dell'utente, l'avanzamento e il resoconto.
 *
 * Ordine che rende sicura una chiusura improvvisa dell'app: prima si crea il profilo e si salva una copia del modpack con la
 * sua riga nell'elenco (segnata «non terminata»), poi si scarica. Se Android chiude l'app a metà, il modpack è nell'elenco
 * e COMPLETA scarica solo ciò che manca. Se invece è l'utente ad annullare, sparisce tutto quello che l'installazione aveva creato.
 */
class ModpackViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)
    private val manager: ModManager = container.modManager
    private val engine: DownloadEngine by lazy { container.downloads }
    private val records: ModpackRepository = container.modpacks
    private val paths = container.paths
    private val repo = ModsRepository(application, container.paths)
    private val limits = SafetyLimits()
    private val installer: ModpackInstaller by lazy {
        ModpackInstaller(manager, engine, { file, name, cancel -> repo.analyze(file, name, cancel, null) }, limits)
    }

    private val _state = MutableStateFlow(ModpackState())
    val state: StateFlow<ModpackState> = _state.asStateFlow()

    private var job: Job? = null
    private var lastProgressNanos = 0L

    init {
        quick("Lettura dei modpack") { reload() }
    }

    override fun onCleared() {
        discardTemp(_state.value.pending)
        super.onCleared()
    }

    // ------------------------------------------------------------------ stato

    private fun reload() {
        val rows = records.list().sortedByDescending { it.updatedAt }.map { RecordRow(it, manager.profile(it.profileId)?.name) }
        _state.update { it.copy(rows = rows, loaded = true) }
    }

    /** Si è aperta la sezione: i profili possono essere stati cambiati o eliminati dal MOD MANAGER. */
    fun onSectionOpened() {
        if (_state.value.busy == null) quick("Lettura dei modpack") { reload() }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun clearOutcome() = _state.update { it.copy(outcome = null) }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    private fun say(text: String) = _state.update { it.copy(message = text) }

    // ------------------------------------------------------------------ esecuzione sicura

    private fun fail(what: String, e: Throwable) {
        try {
            container.errors.record(what, e.message ?: e.javaClass.simpleName, e.stackTraceToString())
        } catch (_: Throwable) {
        }
        say(ErrorMessages.describe(e))
    }

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

    // ------------------------------------------------------------------ scelta del file

    /** Il file sembra un modpack Modrinth? Si decide dal nome, senza leggerlo. */
    fun looksLikePack(uri: Uri): Boolean = try {
        repo.describe(uri).displayName.lowercase().endsWith(".mrpack")
    } catch (e: Exception) {
        false
    }

    /**
     * Divide i file ricevuti da un'altra app: il primo .mrpack apre il MODPACK MANAGER, il resto va a chi lo gestiva prima
     * ([others]). Un modpack alla volta: se ce n'è più di uno lo si dice.
     */
    fun routeIncoming(uris: List<Uri>, openSection: () -> Unit, others: (List<Uri>) -> Unit) {
        viewModelScope.launch {
            val split = withContext(Dispatchers.IO) { uris.partition { looksLikePack(it) } }
            val packs = split.first
            if (packs.isNotEmpty()) {
                openSection()
                openFile(packs.first(), if (packs.size > 1) "Un modpack alla volta: ho aperto il primo. Per gli altri ripeti la condivisione." else null)
            }
            if (split.second.isNotEmpty()) others(split.second)
        }
    }

    /** Copia il file scelto nella cartella temporanea, lo legge (senza eseguire nulla) e prepara l'anteprima. */
    fun openFile(uri: Uri, note: String? = null) {
        runBusy("Controllo del modpack") { cancel, progress ->
            val loaded = withContext(Dispatchers.IO) {
                val picked = repo.describe(uri)
                val batch = File(paths.sessions, "modpack-" + UUID.randomUUID())
                batch.mkdirs()
                val staged = File(batch, "modpack.mrpack")
                try {
                    repo.stage(picked, staged, cancel) { done, total, _ -> progress(done, total, "Copio «" + picked.displayName + "»") }
                    val l = load(staged, picked.displayName, if (picked.sizeBytes > 0) picked.sizeBytes else staged.length(), null, cancel)
                    if (l is Loaded.Failed) batch.deleteRecursively()
                    l
                } catch (e: Throwable) {
                    batch.deleteRecursively()
                    throw e
                }
            }
            when (loaded) {
                is Loaded.Ok -> {
                    val old = _state.value.pending
                    _state.update { it.copy(pending = loaded.pending, outcome = null) }
                    if (old != null && old.file != loaded.pending.file) discardTemp(old)
                    if (note != null) say(note)
                }
                is Loaded.Failed -> say(loaded.text)
            }
        }
    }

    /** Prepara il completamento di un modpack già nell'elenco, partendo dalla copia che l'app ne ha conservato. */
    fun prepareResume(record: ModpackRecord) {
        runBusy("Controllo di «" + record.name + "»") { cancel, _ ->
            val loaded = withContext(Dispatchers.IO) {
                val archive = archiveFile(record)
                when {
                    !archive.isFile -> Loaded.Failed(
                        "La copia del modpack non c'è più nella memoria dell'app. Toglilo dall'elenco e installa di nuovo il file .mrpack."
                    )
                    manager.profile(record.profileId) == null -> Loaded.Failed(
                        "Il profilo di questo modpack non esiste più (è stato eliminato dal MOD MANAGER). " +
                            "Toglilo dall'elenco e installa di nuovo il modpack."
                    )
                    else -> load(archive, archive.name, archive.length(), record, cancel)
                }
            }
            when (loaded) {
                is Loaded.Ok -> {
                    val old = _state.value.pending
                    _state.update { it.copy(pending = loaded.pending, outcome = null) }
                    if (old != null && old.file != loaded.pending.file) discardTemp(old)
                }
                is Loaded.Failed -> say(loaded.text)
            }
        }
    }

    private sealed class Loaded {
        class Ok(val pending: PendingPack) : Loaded()
        class Failed(val text: String) : Loaded()
    }

    private fun load(file: File, displayName: String, size: Long, resume: ModpackRecord?, cancel: CancelCheck): Loaded =
        when (val r = ModrinthPackReader.read(file, limits)) {
            is PackReadResult.Failed -> Loaded.Failed(r.message + (r.detail?.let { " ($it)" } ?: ""))
            is PackReadResult.Ok -> {
                val dir = resume?.let { manager.profileDir(it.profileId) }
                val plan = ModpackPlanner.plan(r.pack, engine.checker, dir, cancel)
                Loaded.Ok(
                    PendingPack(
                        file = file, displayName = displayName, sizeBytes = size, pack = r.pack, plan = plan,
                        profileName = resume?.let { manager.profile(it.profileId)?.name } ?: suggestName(r.pack.name),
                        resume = resume, freeBytes = paths.javaProfiles.usableSpace
                    )
                )
            }
        }

    /** Un nome libero per il profilo: quello del modpack (accorciato se serve), o con un numero se esiste già. */
    private fun suggestName(base: String): String {
        var name = base.filter { !it.isISOControl() }.trim().replace(Regex("\\s+"), " ").take(MAX_PROFILE_NAME).trim()
        if (name.isEmpty()) name = "Modpack"
        val taken = manager.profiles().map { it.name.lowercase() }.toSet()
        var candidate = name
        var n = 2
        while (candidate.lowercase() in taken) {
            val suffix = " $n"
            candidate = name.take(MAX_PROFILE_NAME - suffix.length).trimEnd() + suffix
            n++
        }
        return candidate
    }

    fun setProfileName(text: String) {
        _state.update { s -> s.pending?.let { s.copy(pending = it.copy(profileName = text, nameError = null)) } ?: s }
    }

    fun discardPending() {
        val p = _state.value.pending
        _state.update { it.copy(pending = null) }
        discardTemp(p)
    }

    private fun discardTemp(p: PendingPack?) {
        if (p != null && p.resume == null) p.file.parentFile?.deleteRecursively()
    }

    // ------------------------------------------------------------------ siti consentiti

    fun askConsent(host: String) = _state.update { it.copy(dialog = ModpackDialog.Consent(host)) }

    /** L'utente consente i download da un sito: il piano si ricalcola e i file di quel sito diventano scaricabili. */
    fun confirmConsent() {
        val dialog = _state.value.dialog as? ModpackDialog.Consent ?: return
        _state.update { it.copy(dialog = null) }
        quick("Consenso a un sito") {
            if (!engine.approveHost(dialog.host)) {
                say("Il nome del sito «" + dialog.host + "» non è valido.")
                return@quick
            }
            val p = _state.value.pending ?: return@quick
            val dir = p.resume?.let { manager.profileDir(it.profileId) }
            val plan = ModpackPlanner.plan(p.pack, engine.checker, dir)
            _state.update { s -> if (s.pending?.file == p.file) s.copy(pending = p.copy(plan = plan)) else s }
        }
    }

    // ------------------------------------------------------------------ installazione

    private sealed class Step {
        class Done(val outcome: PackOutcome) : Step()
        class Failed(val text: String, val nameProblem: Boolean = false) : Step()
    }

    /** Installa il modpack in anteprima: nuovo profilo, oppure completamento di quello della riga da riprendere. */
    fun install() {
        val pending = _state.value.pending ?: return
        val resuming = pending.resume != null
        val title = (if (resuming) "Completo «" else "Installo «") + pending.pack.name + "»"
        runBusy(title, if (resuming) "Operazione annullata: i file già scaricati restano al loro posto." else "Installazione annullata: non è cambiato nulla.") { cancel, progress ->
            val step = withContext(Dispatchers.IO) { runInstall(pending, cancel, progress) }
            when (step) {
                is Step.Done -> {
                    _state.update { it.copy(pending = null, outcome = step.outcome) }
                    discardTemp(pending)
                    withContext(Dispatchers.IO) { reload() }
                }
                is Step.Failed -> {
                    if (step.nameProblem) _state.update { s -> s.pending?.let { s.copy(pending = it.copy(nameError = step.text)) } ?: s }
                    else say(step.text)
                    withContext(Dispatchers.IO) { reload() }
                }
            }
        }
    }

    private fun runInstall(p: PendingPack, cancel: CancelCheck, progress: ProgressCallback): Step {
        val pack = p.pack
        val loader = pack.loader ?: return Step.Failed("Il modpack non indica il caricatore (Fabric, Quilt, Forge o NeoForge).")
        val resume = p.resume
        val record: ModpackRecord
        val archive: File
        val createdHere: Boolean
        if (resume != null) {
            record = resume
            archive = p.file
            createdHere = false
        } else {
            // 1. il profilo e la copia del modpack con la sua riga nell'elenco: così una chiusura improvvisa dell'app lascia un modpack
            //    da completare e non file sparsi senza spiegazione.
            val draft = ProfileDraft(p.profileName, pack.minecraft.orEmpty(), loader, pack.loaderVersion)
            val profile = when (val r = manager.createProfile(draft)) {
                is Outcome.Ok -> r.value
                is Outcome.Fail -> return Step.Failed(r.message + (r.detail?.let { " ($it)" } ?: ""), nameProblem = true)
            }
            val id = UUID.randomUUID().toString().replace("-", "").take(12)
            val saved = File(paths.modpacks, "$id.mrpack")
            try {
                paths.modpacks.mkdirs()
                Files.copy(p.file.toPath(), saved.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                saved.delete()
                manager.deleteProfile(profile.id)
                return Step.Failed("Non riesco a salvare una copia del modpack nella memoria dell'app: " + (e.message ?: e.javaClass.simpleName))
            }
            val now = System.currentTimeMillis()
            val fresh = ModpackRecord(
                id = id, name = pack.name, versionId = pack.versionId, minecraft = pack.minecraft.orEmpty(), loader = loader,
                loaderVersion = pack.loaderVersion, profileId = profile.id, archiveName = saved.name,
                totalFiles = p.plan.toDownload.size + p.plan.present.size, placedFiles = 0, complete = false,
                problems = listOf("Installazione non terminata: tocca COMPLETA per scaricare ciò che manca."), createdAt = now, updatedAt = now
            )
            if (!records.add(fresh)) {
                saved.delete()
                manager.deleteProfile(profile.id)
                return Step.Failed("Non sono riuscito a salvare l'elenco dei modpack. Controlla lo spazio libero sul telefono.")
            }
            record = fresh
            archive = saved
            createdHere = true
        }

        val result = try {
            installer.install(InstallRequest(pack, archive, record.name, record.profileId), cancel) { ip -> report(progress, ip) }
        } catch (e: CancellationException) {
            if (createdHere) forget(record, deleteProfile = true)
            throw e
        } catch (e: Throwable) {
            if (createdHere) forget(record, deleteProfile = true)
            throw e
        }

        val fatal = result.fatal
        if (fatal != null) {
            if (createdHere) forget(record, deleteProfile = true)
            return Step.Failed(fatal)
        }
        val problems = problemsOf(result)
        records.update(record.id) {
            it.copy(
                totalFiles = result.totalFiles, placedFiles = result.placedFiles, complete = result.complete, problems = problems,
                updatedAt = System.currentTimeMillis()
            )
        }
        return Step.Done(PackOutcome(record.title, result, record.id, record.profileId))
    }

    /** Toglie dall'elenco quanto un'installazione annullata o fallita aveva creato (riga, copia del modpack e, se serve, profilo). */
    private fun forget(record: ModpackRecord, deleteProfile: Boolean) {
        if (deleteProfile) manager.deleteProfile(record.profileId)
        records.remove(record.id)
        archiveFile(record).delete()
    }

    private fun report(progress: ProgressCallback, p: InstallProgress) {
        val byBytes = p.phase == Phase.DOWNLOADING && p.totalBytes > 0
        val done = if (byBytes) p.doneBytes else p.doneFiles.toLong()
        val total = if (byBytes) p.totalBytes else p.totalFiles.toLong()
        val sb = StringBuilder(p.phase.label)
        if (p.phase == Phase.DOWNLOADING || p.phase == Phase.CHECKING) sb.append(": ").append(p.doneFiles).append(" di ").append(p.totalFiles).append(" file")
        if (p.paused > 0) sb.append(" · ").append(p.paused).append(" in pausa")
        if (p.note != null) sb.append(" · ").append(p.note)
        progress(done, total, sb.toString())
    }

    private fun problemsOf(r: ModpackResult): List<String> {
        val out = ArrayList<String>()
        for (f in r.failures) out.add(f.path + ": " + f.reason)
        for (s in r.overridesSkipped) out.add("Impostazione non applicata: $s")
        for (p in r.problems) out.add("Voce scartata dal modpack: $p")
        return out
    }

    // ------------------------------------------------------------------ elenco

    fun askRemove(record: ModpackRecord) = _state.update { it.copy(dialog = ModpackDialog.RemoveRecord(record)) }

    /** Toglie il modpack dall'elenco e cancella la copia conservata; il profilo e i suoi file restano nel MOD MANAGER. */
    fun removeRecord(record: ModpackRecord) {
        quick("Rimozione di un modpack") {
            records.remove(record.id)
            archiveFile(record).delete()
            _state.update { s ->
                s.copy(
                    dialog = null,
                    outcome = if (s.outcome?.recordId == record.id) null else s.outcome,
                    pending = if (s.pending?.resume?.id == record.id) null else s.pending
                )
            }
            reload()
            say("«" + record.name + "» tolto dall'elenco. Il profilo e i suoi file restano nel MOD MANAGER.")
        }
    }

    /** Rende attivo il profilo del modpack e apre il MOD MANAGER. */
    fun openProfile(profileId: String, openMods: () -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { manager.profile(profileId) != null && manager.setActive(profileId) is Outcome.Ok }
            if (ok) openMods() else say("Il profilo di questo modpack non esiste più.")
        }
    }

    /** La copia conservata del modpack. Il nome viene dall'archivio dati: si usa solo l'ultima parte, mai un percorso. */
    private fun archiveFile(record: ModpackRecord): File = File(paths.modpacks, File(record.archiveName).name)

    private companion object {
        /** Lunghezza massima del nome di un profilo (la stessa che accetta il MOD MANAGER). */
        const val MAX_PROFILE_NAME = 60
    }
}
