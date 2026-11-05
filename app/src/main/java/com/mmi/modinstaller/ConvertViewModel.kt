package com.mmi.modinstaller

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mmi.modinstaller.convert.ConversionPlan
import com.mmi.modinstaller.convert.ConversionText
import com.mmi.modinstaller.convert.ConvertLimits
import com.mmi.modinstaller.convert.ConvertSource
import com.mmi.modinstaller.convert.ModConverter
import com.mmi.modinstaller.convert.ModReport
import com.mmi.modinstaller.core.CancelCheck
import com.mmi.modinstaller.core.ProgressCallback
import com.mmi.modinstaller.core.SafetyLimits
import com.mmi.modinstaller.core.formatBytes
import com.mmi.modinstaller.data.AppContainer
import com.mmi.modinstaller.data.ErrorMessages
import com.mmi.modinstaller.data.FileInbox
import com.mmi.modinstaller.data.MinecraftBridge
import com.mmi.modinstaller.data.ModsRepository
import com.mmi.modinstaller.mods.ItemCategory
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
import java.io.File
import java.util.UUID

/** Il piano letto, pronto per la conversione. [copies] sono le copie delle mod scelte (da cancellare alla fine). */
class PendingConversion(
    val plan: ConversionPlan,
    val reports: List<ModReport>,
    val addonName: String,
    val from: String,
    val copies: List<File>
)

/** Un add-on già creato, nella memoria dell'app. */
class SavedAddon(val file: File, val name: String, val sizeBytes: Long, val createdAt: Long)

class ConvertOutcome(
    val addon: SavedAddon, val items: Int, val blocks: Int, val entities: Int, val recipes: Int, val ores: Int, val report: String,
    /** La versione di Minecraft Bedrock che serve per questo add-on. */
    val minEngine: List<Int> = emptyList()
)

/**
 * Per quale Minecraft Bedrock si converte: la versione installata sul telefono (se c'è) decide se si possono usare i
 * blocchi con più stati del formato 1.26.40 (porte, lastre, scale, staccionate…). Senza Minecraft si converte per l'ultima.
 */
class ConvertTarget(val installed: String?, val version: List<Int>, val statefulBlocks: Boolean)

sealed class ConvertDialog {
    class ChooseProfile : ConvertDialog()
    class DeleteAddon(val addon: SavedAddon) : ConvertDialog()
}

data class ConvertState(
    val pending: PendingConversion? = null,
    val outcome: ConvertOutcome? = null,
    val addons: List<SavedAddon> = emptyList(),
    val profiles: List<Profile> = emptyList(),
    val busy: BusyState? = null,
    val message: String? = null,
    val dialog: ConvertDialog? = null,
    val target: ConvertTarget? = null
)

/**
 * JAVA TO BEDROCK: sceglie le mod (dal telefono o da un profilo del MOD MANAGER), le fa leggere a [ModConverter] (che non esegue
 * nulla), mostra il rapporto e scrive l'add-on Bedrock in `files/convert/addons`. L'installazione in Minecraft la fa
 * l'INSTALLER BEDROCK, con gli stessi controlli di ogni altro file.
 */
class ConvertViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)
    private val paths = container.paths
    private val manager = container.modManager
    private val repo = ModsRepository(application, container.paths)
    private val inbox = FileInbox(application, SafetyLimits(maxArchiveBytes = 1024L * 1024 * 1024))
    /** Solo per i nomi dei file (non dipende dalla versione di destinazione). */
    private val names = ModConverter()
    private val bridge = MinecraftBridge(application, application.packageName + ".fileprovider")

    private fun target(): ConvertTarget {
        val info = try { bridge.primaryInfo() } catch (_: Throwable) { null }
        val parts = info?.version?.parts?.take(3)?.takeIf { it.size >= 2 }
        val version = parts ?: ConvertLimits().bedrockTarget
        return ConvertTarget(info?.versionName, version, ConvertLimits(bedrockTarget = version).statefulBlocks)
    }

    /** Il convertitore per il Minecraft di destinazione (quello installato, o l'ultima versione). */
    private fun converter(): ModConverter = ModConverter(ConvertLimits(bedrockTarget = (_state.value.target ?: target()).version))

    private val _state = MutableStateFlow(ConvertState())
    val state: StateFlow<ConvertState> = _state.asStateFlow()

    private var job: Job? = null
    private var lastProgressNanos = 0L

    init {
        quick("Lettura degli add-on") {
            cleanIncoming()
            reload()
        }
    }

    override fun onCleared() {
        _state.value.pending?.let { discard(it) }
        super.onCleared()
    }

    fun onSectionOpened() {
        if (_state.value.busy == null) quick("Lettura degli add-on") { reload() }
    }

    private fun reload() {
        val addons = paths.convertedAddons.listFiles { f -> f.isFile && f.name.endsWith(".mcaddon") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { SavedAddon(it, it.name.removeSuffix(".mcaddon"), it.length(), it.lastModified()) }
        val t = target()
        _state.update { it.copy(addons = addons, profiles = manager.profiles(), target = t) }
    }

    private fun cleanIncoming() {
        if (_state.value.pending != null) return
        paths.convertIncoming.listFiles()?.forEach { it.delete() }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
    fun clearOutcome() = _state.update { it.copy(outcome = null) }
    fun dismissDialog() = _state.update { it.copy(dialog = null) }
    private fun say(text: String) = _state.update { it.copy(message = text) }

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

    private fun runBusy(title: String, cancelledText: String, block: suspend (cancel: CancelCheck, progress: ProgressCallback) -> Unit) {
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

    // ------------------------------------------------------------------ scelta delle mod

    /** Copia le mod scelte nella memoria dell'app (il file originale non cambia) e le legge. */
    fun openFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        runBusy("Lettura delle mod", "Operazione annullata.") { cancel, progress ->
            val copies = ArrayList<File>()
            val sources = ArrayList<ConvertSource>()
            try {
                withContext(Dispatchers.IO) {
                    paths.convertIncoming.mkdirs()
                    for (uri in uris.take(MAX_FILES)) {
                        cancel()
                        val picked = inbox.describe(uri)
                        val copy = File(paths.convertIncoming, UUID.randomUUID().toString().take(12) + ".jar")
                        copies.add(copy)
                        inbox.stage(picked.uri, copy, picked.sizeBytes, cancel) { d, t, _ -> progress(d, t, "Copio «" + picked.displayName + "»") }
                        sources.add(ConvertSource(copy, picked.displayName))
                    }
                }
                val name = if (sources.size == 1) sources[0].label.substringBeforeLast('.') else "Mod Java (" + sources.size + ")"
                analyze(sources, copies, name, if (uris.size > MAX_FILES) "i primi $MAX_FILES file scelti" else "file scelti", cancel, progress)
                if (uris.size > MAX_FILES) say("Si convertono al massimo $MAX_FILES mod alla volta: ho preso le prime $MAX_FILES.")
            } catch (t: Throwable) {
                withContext(Dispatchers.IO) { copies.forEach { it.delete() } }
                throw t
            }
        }
    }

    fun askProfile() {
        if (manager.profiles().isEmpty()) {
            say("Non c'è ancora nessun profilo nel MOD MANAGER: crealo lì, oppure scegli i file .jar dal telefono.")
            return
        }
        _state.update { it.copy(dialog = ConvertDialog.ChooseProfile(), profiles = manager.profiles()) }
    }

    /** Tutte le mod attive di un profilo del MOD MANAGER: si leggono dalla cartella del profilo, senza copiarle. */
    fun convertProfile(profileId: String) {
        _state.update { it.copy(dialog = null) }
        val profile = manager.profile(profileId) ?: return
        runBusy("Lettura delle mod del profilo", "Operazione annullata.") { cancel, progress ->
            val sources = withContext(Dispatchers.IO) {
                manager.items(profileId).filter { it.category == ItemCategory.MOD && it.enabled }
                    .map { ConvertSource(manager.fileOf(profileId, it), it.displayName) }
                    .filter { it.file.isFile }
            }
            if (sources.isEmpty()) {
                say("Nel profilo «" + profile.name + "» non ci sono mod attive da convertire.")
                return@runBusy
            }
            analyze(sources.take(MAX_FILES), emptyList(), profile.name, "profilo «" + profile.name + "»", cancel, progress)
        }
    }

    private suspend fun analyze(sources: List<ConvertSource>, copies: List<File>, name: String, from: String, cancel: CancelCheck, progress: ProgressCallback) {
        val conv = converter()
        val plan = withContext(Dispatchers.IO) { conv.analyze(sources, cancel, progress) }
        val old = _state.value.pending
        val withContent = plan.reports.filter { it.contentCount > 0 }
        val addonName = names.cleanName(if (withContent.size == 1) withContent[0].title else name)
        _state.update { it.copy(pending = PendingConversion(plan, plan.reports, addonName, from, copies), outcome = null) }
        if (old != null) withContext(Dispatchers.IO) { discard(old) }
    }

    fun setAddonName(text: String) = _state.update { s -> s.pending?.let { s.copy(pending = PendingConversion(it.plan, it.reports, text, it.from, it.copies)) } ?: s }

    fun discardPending() {
        val p = _state.value.pending ?: return
        _state.update { it.copy(pending = null) }
        quick("Scarto della conversione") { discard(p) }
    }

    private fun discard(p: PendingConversion) {
        try {
            p.plan.close()
        } catch (_: Throwable) {
        }
        p.copies.forEach { it.delete() }
    }

    // ------------------------------------------------------------------ conversione

    fun convert() {
        val p = _state.value.pending ?: return
        if (!p.plan.hasContent) {
            say("In queste mod non c'è niente che Bedrock possa usare: sono mod di solo codice.")
            return
        }
        val name = names.cleanName(p.addonName)
        runBusy("Creo l'add-on «$name»", "Conversione annullata: non è stato scritto nessun add-on.") { cancel, progress ->
            val written = withContext(Dispatchers.IO) {
                paths.convertedAddons.mkdirs()
                val out = File(paths.convertedAddons, "$name.mcaddon")
                converter().write(p.plan, name, out, cancel, progress)
            }
            val report = ConversionText.of(p.reports, name)
            val addon = SavedAddon(written.file, name, written.sizeBytes, written.file.lastModified())
            val outcome = ConvertOutcome(
                addon, p.plan.itemCount, p.plan.blockCount, p.plan.entityCount, p.plan.recipeCount, p.plan.oreCount, report, written.minEngine
            )
            _state.update { it.copy(pending = null, outcome = outcome) }
            withContext(Dispatchers.IO) {
                discard(p)
                container.store.addHistory(name, "Convertito in add-on Bedrock", p.from + " · " + formatBytes(written.sizeBytes))
                reload()
            }
        }
    }

    /** Passa l'add-on all'INSTALLER BEDROCK, che lo controlla e lo consegna a Minecraft come ogni altro file. */
    fun install(addon: SavedAddon, openFiles: (List<Uri>) -> Unit) {
        if (!addon.file.isFile) {
            say("L'add-on non c'è più.")
            quick("Lettura degli add-on") { reload() }
            return
        }
        openFiles(listOf(Uri.fromFile(addon.file)))
    }

    /** Analisi avanzata: il registro dei componenti di tutte le mod lette, in un file JSON scelto dall'utente. */
    fun saveRegistry(target: Uri) {
        val p = _state.value.pending ?: return
        runBusy("Salvo il registro dei componenti", "Salvataggio annullato.") { _, _ ->
            val text = withContext(Dispatchers.IO) {
                var done = false
                try {
                    val out = repo.openOutput(target) ?: return@withContext "Non riesco a scrivere nel punto scelto."
                    out.use { it.write(com.mmi.modinstaller.convert.ComponentExport.json(p.reports, p.addonName).toByteArray(Charsets.UTF_8)) }
                    done = true
                    "Registro dei componenti salvato (JSON): ogni voce con cosa faceva in Java, cosa diventa in Bedrock e le differenze."
                } finally {
                    if (!done) repo.deleteDocument(target)
                }
            }
            say(text)
        }
    }

    fun saveCopy(addon: SavedAddon, target: Uri) {
        runBusy("Salvo «" + addon.name + "»", "Copia annullata.") { _, _ ->
            val text = withContext(Dispatchers.IO) {
                var done = false
                try {
                    val out = repo.openOutput(target) ?: return@withContext "Non riesco a scrivere nel punto scelto."
                    out.use { o -> addon.file.inputStream().use { it.copyTo(o) } }
                    done = true
                    "Copia salvata: aprila con Minecraft per importarla, o mandala a un altro telefono."
                } finally {
                    if (!done) repo.deleteDocument(target)
                }
            }
            say(text)
        }
    }

    fun askDelete(addon: SavedAddon) = _state.update { it.copy(dialog = ConvertDialog.DeleteAddon(addon)) }

    fun delete(addon: SavedAddon) {
        _state.update { it.copy(dialog = null) }
        quick("Eliminazione dell'add-on") {
            addon.file.delete()
            if (_state.value.outcome?.addon?.file == addon.file) _state.update { it.copy(outcome = null) }
            reload()
            say("«" + addon.name + "» eliminato dall'app. In Minecraft resta finché non lo togli dalle impostazioni del gioco.")
        }
    }

    private companion object {
        const val MAX_FILES = 200
    }
}
