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
import com.mmi.modinstaller.mods.ModManager
import com.mmi.modinstaller.mods.Profile
import com.mmi.modinstaller.worlds.PendingWorldImport
import com.mmi.modinstaller.worlds.WorldEdition
import com.mmi.modinstaller.worlds.WorldExporter
import com.mmi.modinstaller.worlds.WorldImporter
import com.mmi.modinstaller.worlds.WorldInfo
import com.mmi.modinstaller.worlds.WorldScanResult
import com.mmi.modinstaller.worlds.WorldScanner
import com.mmi.modinstaller.worlds.WorldVersions
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

/** Un mondo Java già nella cartella `saves` di un profilo. */
class InstalledWorld(val profileId: String, val profileName: String, val dir: File, val info: WorldInfo)

/** Un'importazione rimasta a metà (l'app è stata chiusa): si riprende se la copia dell'archivio c'è ancora. */
class InterruptedImport(val profileId: String, val profileName: String, val pending: PendingWorldImport, val archiveAvailable: Boolean)

/** Un archivio scelto e letto, con i mondi che contiene, in attesa della decisione dell'utente. */
data class PendingWorlds(
    /** Copia di lavoro nella memoria dell'app (files/worlds/incoming): l'importazione legge questa, mai il file dell'utente. */
    val file: File,
    val token: String,
    /** Il file scelto dall'utente: serve per passare un mondo Bedrock all'INSTALLER BEDROCK. */
    val sourceUri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val worlds: List<WorldInfo>,
    val selected: Int,
    val profileId: String?,
    val folderName: String
) {
    val world: WorldInfo get() = worlds[selected]
}

class WorldOutcome(val ok: Boolean, val title: String, val text: String, val profileId: String?)

sealed class WorldsDialog {
    class ChooseProfile : WorldsDialog()
    class DeleteWorld(val world: InstalledWorld) : WorldsDialog()
    class DiscardInterrupted(val item: InterruptedImport) : WorldsDialog()
}

data class WorldsState(
    val profiles: List<Profile> = emptyList(),
    val pending: PendingWorlds? = null,
    val installed: List<InstalledWorld> = emptyList(),
    val interrupted: List<InterruptedImport> = emptyList(),
    val outcome: WorldOutcome? = null,
    val busy: BusyState? = null,
    val message: String? = null,
    val dialog: WorldsDialog? = null,
    val loaded: Boolean = false
) {
    val chosenProfile: Profile? get() = profiles.firstOrNull { it.id == pending?.profileId }
}

/**
 * Stato e azioni del WORLD MANAGER. Il lavoro vero lo fanno [WorldScanner] (legge l'archivio senza estrarlo), [WorldImporter]
 * (copia un mondo Java nella cartella `saves` di un profilo un file alla volta, con ripresa) e [WorldExporter]; i mondi Bedrock
 * vanno all'INSTALLER BEDROCK, che li consegna a Minecraft come sempre. Il disco è la verità: l'elenco dei mondi si rilegge
 * dalle cartelle dei profili, non da un archivio a parte.
 */
class WorldsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)
    private val manager: ModManager = container.modManager
    private val paths = container.paths
    private val repo = ModsRepository(application, container.paths)
    private val importer = WorldImporter()

    private val _state = MutableStateFlow(WorldsState())
    val state: StateFlow<WorldsState> = _state.asStateFlow()

    private var job: Job? = null
    private var lastProgressNanos = 0L

    init {
        quick("Lettura dei mondi") {
            reload()
            cleanIncoming()
        }
    }

    override fun onCleared() {
        val p = _state.value.pending
        if (p != null && !referenced(p.file)) p.file.delete()
        super.onCleared()
    }

    // ------------------------------------------------------------------ stato

    private fun savesOf(profileId: String) = File(manager.profileDir(profileId), "saves")

    private fun reload() {
        val profiles = manager.profiles()
        val installed = ArrayList<InstalledWorld>()
        val interrupted = ArrayList<InterruptedImport>()
        for (p in profiles) {
            val saves = savesOf(p.id)
            val dirs = saves.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedBy { it.name.lowercase() }.orEmpty()
            for (d in dirs) WorldScanner.scanFolder(d)?.let { installed.add(InstalledWorld(p.id, p.name, d, it)) }
            for (pending in importer.pending(saves)) {
                val available = pending.archive.isFile && pending.archive.length() == pending.archiveSize
                interrupted.add(InterruptedImport(p.id, p.name, pending, available))
            }
        }
        _state.update { s ->
            val keep = s.pending?.let { pw -> if (profiles.any { it.id == pw.profileId }) pw else pw.copy(profileId = defaultProfile(profiles)) }
            s.copy(profiles = profiles, installed = installed, interrupted = interrupted, pending = keep, loaded = true)
        }
    }

    /** Toglie le copie di archivi che nessuna importazione interrotta usa più (anteprime scartate, app chiusa a metà). */
    private fun cleanIncoming() {
        val files = paths.worldsIncoming.listFiles() ?: return
        val current = _state.value.pending?.file?.absoluteFile
        for (f in files) {
            if (f.absoluteFile == current || referenced(f)) continue
            f.delete()
        }
    }

    /** Un'importazione interrotta legge ancora questa copia. */
    private fun referenced(f: File): Boolean = _state.value.interrupted.any { it.pending.archive.absoluteFile == f.absoluteFile }

    private fun defaultProfile(profiles: List<Profile>): String? = (manager.activeProfile() ?: profiles.firstOrNull())?.id

    fun onSectionOpened() {
        if (_state.value.busy == null) quick("Lettura dei mondi") { reload() }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
    fun clearOutcome() = _state.update { it.copy(outcome = null) }
    fun dismissDialog() = _state.update { it.copy(dialog = null) }
    private fun say(text: String) = _state.update { it.copy(message = text) }

    // ------------------------------------------------------------------ esecuzione sicura (come negli altri gestori)

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
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { reload() }
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

    // ------------------------------------------------------------------ scelta e lettura dell'archivio

    /** Copia il file scelto nella memoria dell'app e lo legge (senza estrarlo): poi mostra i mondi che contiene. */
    fun openFile(uri: Uri) {
        runBusy("Controllo del mondo", "Operazione annullata.") { cancel, progress ->
            val result = withContext(Dispatchers.IO) {
                val picked = repo.describe(uri)
                val token = UUID.randomUUID().toString().replace("-", "").take(12)
                paths.worldsIncoming.mkdirs()
                val copy = File(paths.worldsIncoming, "$token.zip")
                try {
                    repo.stage(picked, copy, cancel) { done, total, _ -> progress(done, total, "Copio «" + picked.displayName + "»") }
                    when (val r = WorldScanner.scan(copy, cancel = cancel)) {
                        is WorldScanResult.Failed -> {
                            copy.delete()
                            r.message + (r.detail?.let { " ($it)" } ?: "")
                        }
                        is WorldScanResult.Ok -> {
                            val first = r.worlds.first()
                            PendingWorlds(
                                file = copy, token = token, sourceUri = uri, displayName = picked.displayName,
                                sizeBytes = copy.length(), worlds = r.worlds, selected = 0,
                                profileId = defaultProfile(manager.profiles()),
                                folderName = WorldImporter.safeFolderName(first.title)
                            )
                        }
                    }
                } catch (e: Throwable) {
                    copy.delete()
                    throw e
                }
            }
            when (result) {
                is PendingWorlds -> {
                    val old = _state.value.pending
                    _state.update { it.copy(pending = result, outcome = null) }
                    if (old != null && old.file != result.file && !referenced(old.file)) withContext(Dispatchers.IO) { old.file.delete() }
                }
                is String -> say(result)
            }
        }
    }

    fun selectWorld(index: Int) = _state.update { s ->
        val p = s.pending ?: return@update s
        if (index !in p.worlds.indices) s else s.copy(pending = p.copy(selected = index, folderName = WorldImporter.safeFolderName(p.worlds[index].title)))
    }

    fun setFolderName(text: String) = _state.update { s -> s.pending?.let { s.copy(pending = it.copy(folderName = text)) } ?: s }

    fun askProfile() = _state.update { it.copy(dialog = WorldsDialog.ChooseProfile()) }

    fun chooseProfile(id: String) = _state.update { s -> s.copy(dialog = null, pending = s.pending?.copy(profileId = id)) }

    fun discardPending() {
        val p = _state.value.pending ?: return
        _state.update { it.copy(pending = null) }
        quick("Scarto del mondo") { if (!referenced(p.file)) p.file.delete() }
    }

    /** Un mondo Bedrock va all'INSTALLER BEDROCK, che lo controlla e lo consegna a Minecraft come ogni altro file. */
    fun openInBedrockInstaller(openFiles: (List<Uri>) -> Unit) {
        val p = _state.value.pending ?: return
        _state.update { it.copy(pending = null) }
        quick("Passaggio all'INSTALLER BEDROCK") { if (!referenced(p.file)) p.file.delete() }
        openFiles(listOf(p.sourceUri))
    }

    /** Avviso sulla versione del mondo Java rispetto al profilo scelto (vedi [WorldVersions]), o null. */
    fun versionWarning(world: WorldInfo, profile: Profile?): String? =
        if (profile == null || world.edition != WorldEdition.JAVA) null else WorldVersions.warning(world.version, profile.minecraft)

    // ------------------------------------------------------------------ importazione

    fun importSelected() {
        val p = _state.value.pending ?: return
        val world = p.world
        val profile = _state.value.profiles.firstOrNull { it.id == p.profileId }
        if (profile == null) {
            say("Per importare un mondo Java serve un profilo: crealo nel MOD MANAGER.")
            return
        }
        val token = p.token + "w" + p.selected
        runBusy("Importo «" + world.title + "»", "Importazione annullata: nel profilo non è cambiato nulla.") { cancel, progress ->
            val r = withContext(Dispatchers.IO) {
                importer.importJava(p.file, world, savesOf(profile.id), p.folderName, token, cancel, progress)
            }
            val outcome = outcomeOf(world.title, profile, r.worldDir, r.files, r.bytes, r.resumedFiles, r.fatal)
            _state.update { s ->
                // Dopo un'importazione riuscita l'anteprima si chiude se l'archivio aveva un solo mondo.
                s.copy(outcome = outcome, pending = if (r.ok && p.worlds.size == 1) null else s.pending)
            }
            withContext(Dispatchers.IO) {
                reload()
                if (r.ok && p.worlds.size == 1 && !referenced(p.file)) p.file.delete()
            }
        }
    }

    fun resume(item: InterruptedImport) {
        val pend = item.pending
        val profile = _state.value.profiles.firstOrNull { it.id == item.profileId } ?: return
        runBusy("Riprendo «" + pend.title + "»", "Ripresa annullata: i file già copiati sono stati cancellati.") { cancel, progress ->
            val r = withContext(Dispatchers.IO) {
                if (!item.archiveAvailable) return@withContext null
                val scan = WorldScanner.scan(pend.archive, cancel = cancel) as? WorldScanResult.Ok ?: return@withContext null
                val world = scan.worlds.firstOrNull { it.root == pend.root } ?: return@withContext null
                world to importer.importJava(pend.archive, world, savesOf(profile.id), pend.folderName, pend.token, cancel, progress)
            }
            if (r == null) {
                say("La copia dell'archivio non c'è più o è cambiata: annulla questa importazione e scegli di nuovo il file.")
            } else {
                val (world, res) = r
                _state.update { it.copy(outcome = outcomeOf(world.title, profile, res.worldDir, res.files, res.bytes, res.resumedFiles, res.fatal)) }
            }
            withContext(Dispatchers.IO) {
                reload()
                if (r?.second?.ok == true && !referenced(pend.archive) && _state.value.pending?.file?.absoluteFile != pend.archive.absoluteFile) {
                    pend.archive.delete()
                }
            }
        }
    }

    fun askDiscard(item: InterruptedImport) = _state.update { it.copy(dialog = WorldsDialog.DiscardInterrupted(item)) }

    fun discardInterrupted(item: InterruptedImport) {
        _state.update { it.copy(dialog = null) }
        quick("Annullamento di un'importazione") {
            importer.discard(savesOf(item.profileId), item.pending.token)
            reload()
            if (!referenced(item.pending.archive) && _state.value.pending?.file?.absoluteFile != item.pending.archive.absoluteFile) {
                item.pending.archive.delete()
            }
            say("Importazione di «" + item.pending.title + "» annullata: i file già copiati sono stati cancellati.")
        }
    }

    private fun outcomeOf(title: String, profile: Profile, dir: File?, files: Int, bytes: Long, resumed: Int, fatal: String?): WorldOutcome =
        if (fatal == null && dir != null) {
            WorldOutcome(
                true, title,
                "Il mondo è nella cartella saves/" + dir.name + " del profilo «" + profile.name + "»: " + files + " file (" + formatBytes(bytes) + ")" +
                    (if (resumed > 0) ", di cui $resumed già copiati prima dell'interruzione" else "") + ". " +
                    "L'app non apre il gioco: esporta il mondo (o il profilo dal MOD MANAGER) e mettilo nella cartella saves del tuo programma di avvio.",
                profile.id
            )
        } else {
            WorldOutcome(false, title, fatal ?: "Importazione non riuscita.", profile.id)
        }

    // ------------------------------------------------------------------ mondi installati

    fun askDelete(world: InstalledWorld) = _state.update { it.copy(dialog = WorldsDialog.DeleteWorld(world)) }

    fun deleteWorld(world: InstalledWorld) {
        _state.update { it.copy(dialog = null) }
        runBusy("Elimino «" + world.info.title + "»", "Operazione annullata.") { _, _ ->
            withContext(Dispatchers.IO) {
                // Solo cartelle dentro saves di un profilo: mai altro.
                val saves = savesOf(world.profileId).canonicalFile
                val dir = world.dir.canonicalFile
                if (dir.parentFile == saves) dir.deleteRecursively()
                reload()
            }
            say("«" + world.info.title + "» eliminato dal profilo «" + world.profileName + "».")
        }
    }

    /** Esporta il mondo in uno ZIP nel punto scelto dall'utente. Uno ZIP rimasto a metà viene cancellato. */
    fun export(world: InstalledWorld, target: Uri) {
        runBusy("Esporto «" + world.info.title + "»", "Esportazione annullata: lo ZIP incompleto è stato cancellato.") { cancel, progress ->
            val text = withContext(Dispatchers.IO) {
                var finished = false
                try {
                    val stream = repo.openOutput(target) ?: return@withContext "Non riesco a scrivere nel punto scelto."
                    val summary = stream.use { WorldExporter.zip(world.dir, it, world.dir.name, cancel, progress) }
                    finished = true
                    "Esportato «" + world.info.title + "»: " + summary.files + " file (" + formatBytes(summary.bytes) + "). " +
                        "Estrai lo ZIP nella cartella saves del tuo programma di avvio."
                } finally {
                    if (!finished) repo.deleteDocument(target)
                }
            }
            say(text)
        }
    }
}
