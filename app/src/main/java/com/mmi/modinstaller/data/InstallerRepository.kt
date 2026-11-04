package com.mmi.modinstaller.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mmi.modinstaller.core.AnalysisReport
import com.mmi.modinstaller.core.AnalyzeContext
import com.mmi.modinstaller.core.BackupInfo
import com.mmi.modinstaller.core.BackupManager
import com.mmi.modinstaller.core.CancelCheck
import com.mmi.modinstaller.core.ContentAnalyzer
import com.mmi.modinstaller.core.ContentItem
import com.mmi.modinstaller.core.InstallState
import com.mmi.modinstaller.core.InstalledEntry
import com.mmi.modinstaller.core.LibraryStore
import com.mmi.modinstaller.core.PackRef
import com.mmi.modinstaller.core.PackagePreparer
import com.mmi.modinstaller.core.PreparedPackage
import com.mmi.modinstaller.core.ProgressCallback
import com.mmi.modinstaller.core.RecentFile
import com.mmi.modinstaller.core.SafetyLimits
import java.io.File
import java.io.IOException
import java.util.UUID

/** Un pacchetto preparato e la sua voce nell'elenco dei contenuti gestiti (non ancora salvata). */
class PreparedEntry(val entry: InstalledEntry, val prepared: PreparedPackage)

/** Esito di un'eliminazione: serve per poterla annullare. */
class DeleteResult(val entry: InstalledEntry, val backup: BackupInfo?)

/**
 * Unico punto di accesso ai dati e alle operazioni sui file. Contiene la logica che non riguarda l'interfaccia:
 * copia, analisi, preparazione, elenchi, backup ed eliminazione.
 */
class InstallerRepository(context: Context) {

    private val appContext: Context = context.applicationContext
    private val limits = SafetyLimits()

    val paths: AppPaths = AppPaths(appContext).also {
        it.ensure()
        it.clearSessions()
        it.clearPartialFiles()
    }
    val store: LibraryStore = LibraryStore(paths.libraryFile)
    val backups: BackupManager = BackupManager(paths.backups)
    val bridge: MinecraftBridge = MinecraftBridge(appContext, appContext.packageName + ".fileprovider")

    private val inbox = FileInbox(appContext, limits)
    private val preparer = PackagePreparer(limits)

    // ------------------------------------------------------------------ file scelti

    fun describe(uri: Uri): PickedFile = inbox.describe(uri)

    /** Chiede ad Android di ricordare l'accesso al file, così "File recenti" funziona anche dopo il riavvio. */
    fun rememberRecent(picked: PickedFile) {
        try {
            if (picked.uri.scheme == "content") {
                appContext.contentResolver.takePersistableUriPermission(picked.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            // Non tutti i provider permettono l'accesso persistente: il file resta nei recenti ma potrebbe non riaprirsi.
        }
        store.addRecent(RecentFile(picked.uri.toString(), picked.displayName, picked.sizeBytes, System.currentTimeMillis()))
    }

    fun forgetRecent(uri: String) {
        store.removeRecent(uri)
        try {
            appContext.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // nessun permesso da rilasciare
        }
    }

    // ------------------------------------------------------------------ analisi

    fun newSessionDir(): File {
        val dir = File(paths.sessions, UUID.randomUUID().toString())
        dir.mkdirs()
        return dir
    }

    /** Copia il file scelto nella cartella di lavoro [sessionDir] e lo analizza. */
    fun analyze(picked: PickedFile, sessionDir: File, cancel: CancelCheck, progress: ProgressCallback?): AnalysisReport {
        val source = File(sessionDir, "source.bin")
        inbox.stage(picked.uri, source, picked.sizeBytes, cancel, progress)
        return analyzeStaged(source, picked.displayName, sessionDir, cancel, progress)
    }

    /** Ripristina un backup nella cartella di lavoro e lo analizza come se fosse stato appena scelto. */
    fun analyzeBackup(info: BackupInfo, sessionDir: File, cancel: CancelCheck, progress: ProgressCallback?): AnalysisReport {
        val restored = backups.restore(info.id, File(sessionDir, "backup"))
        return analyzeStaged(restored, info.fileName, sessionDir, cancel, progress)
    }

    private fun analyzeStaged(
        source: File,
        displayName: String,
        sessionDir: File,
        cancel: CancelCheck,
        progress: ProgressCallback?
    ): AnalysisReport {
        val work = File(sessionDir, "work")
        work.mkdirs()
        val context = AnalyzeContext(
            workDir = work,
            opener = AndroidArchiveOpener(limits, File(sessionDir, "rar")),
            limits = limits,
            minecraft = bridge.primaryInfo(),
            known = store.knownPacks(),
            cancel = cancel,
            progress = progress
        )
        return ContentAnalyzer(context).analyze(source, displayName)
    }

    // ------------------------------------------------------------------ preparazione

    /** Costruisce il pacchetto verificato per [item] nella cartella dei contenuti gestiti. */
    fun prepare(
        item: ContentItem,
        sourceName: String,
        sessionDir: File,
        cancel: CancelCheck,
        progress: ProgressCallback?
    ): PreparedEntry {
        val opener = AndroidArchiveOpener(limits, File(sessionDir, "rar"))
        val prepared = preparer.prepare(item, paths.prepared, opener, cancel, progress)
        val now = System.currentTimeMillis()
        val entry = InstalledEntry(
            id = UUID.randomUUID().toString(),
            title = item.title,
            kind = item.kind.name,
            version = item.version,
            author = item.author,
            packs = packRefs(item),
            fileName = prepared.fileName,
            sizeBytes = prepared.sizeBytes,
            source = sourceName,
            sentAt = now,
            state = InstallState.SENT,
            stateAt = now
        )
        return PreparedEntry(entry, prepared)
    }

    private fun packRefs(item: ContentItem): List<PackRef> {
        val refs = ArrayList<PackRef>()
        for (part in item.parts) {
            val manifest = part.manifest ?: continue
            val uuid = manifest.uuid ?: continue
            refs.add(PackRef(uuid.lowercase(), manifest.name ?: part.displayName, manifest.versionText))
        }
        return refs
    }

    fun preparedFile(entry: InstalledEntry): File = File(paths.prepared, entry.fileName)

    // ------------------------------------------------------------------ eliminazione e backup

    /**
     * Elimina dall'app un contenuto gestito, dopo aver salvato una copia di sicurezza del file.
     * Se la copia non riesce, il file NON viene eliminato.
     */
    fun delete(id: String): DeleteResult? {
        val entry = store.findInstalled(id) ?: return null
        val file = preparedFile(entry)
        var backup: BackupInfo? = null
        if (file.isFile) {
            backup = backups.create(file, entry.title, "Eliminato dall'app")
            bridge.revoke(file)
            if (!file.delete()) throw IOException("Impossibile eliminare il file «${entry.fileName}»")
        }
        store.removeInstalled(id)
        store.addHistory(
            entry.title, "Eliminato dall'app",
            if (backup != null) "Copia di sicurezza creata" else "Il file non era più presente"
        )
        backups.prune(MAX_BACKUPS, MAX_BACKUP_BYTES)
        return DeleteResult(entry, backup)
    }

    fun undoDelete(result: DeleteResult): InstalledEntry {
        var fileName = result.entry.fileName
        val backup = result.backup
        if (backup != null) {
            val restored = backups.restore(backup.id, paths.prepared)
            fileName = restored.name
            backups.delete(backup.id)
        }
        val entry = result.entry.copy(fileName = fileName)
        store.putInstalled(entry)
        store.addHistory(entry.title, "Eliminazione annullata", "Contenuto ripristinato dalla copia di sicurezza")
        return entry
    }

    private companion object {
        const val MAX_BACKUPS = 15
        const val MAX_BACKUP_BYTES = 2L * 1024 * 1024 * 1024
    }
}
