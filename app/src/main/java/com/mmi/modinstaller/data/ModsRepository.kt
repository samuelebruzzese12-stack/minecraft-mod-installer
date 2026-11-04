package com.mmi.modinstaller.data

import android.content.Context
import android.net.Uri
import com.mmi.modinstaller.core.CancelCheck
import com.mmi.modinstaller.core.ProgressCallback
import com.mmi.modinstaller.core.SafetyLimits
import com.mmi.modinstaller.jar.JarAnalyzer
import com.mmi.modinstaller.jar.JarLimits
import com.mmi.modinstaller.jar.JarReport
import java.io.File
import java.util.UUID

/**
 * Porta i file Java scelti dall'utente dentro l'app e li analizza, usando lo stesso sistema di importazione
 * dell'INSTALLER BEDROCK ([FileInbox]: copia in una cartella temporanea con controlli di dimensione e di spazio, l'originale
 * non viene toccato). L'analisi legge solo l'elenco dei file e le descrizioni, mai il codice delle mod.
 */
class ModsRepository(context: Context, private val paths: AppPaths) {

    private val appContext: Context = context.applicationContext
    private val limits = SafetyLimits()
    private val jarLimits = JarLimits(safety = limits)
    private val inbox = FileInbox(appContext, limits)

    fun describe(uri: Uri): PickedFile = inbox.describe(uri)

    /** Il file sembra un file Java per il MOD MANAGER (.jar o .mrpack)? Si decide dal nome, senza leggerlo. */
    fun looksLikeJavaFile(uri: Uri): Boolean {
        val name = describe(uri).displayName.lowercase()
        return name.endsWith(".jar") || name.endsWith(".mrpack")
    }

    /** Cartella temporanea per un gruppo di file scelti insieme; si svuota a ogni avvio dell'app. */
    fun newBatchDir(): File {
        val dir = File(paths.sessions, "mods-" + UUID.randomUUID())
        dir.mkdirs()
        return dir
    }

    fun stage(picked: PickedFile, target: File, cancel: CancelCheck, progress: ProgressCallback?) {
        inbox.stage(picked.uri, target, picked.sizeBytes, cancel, progress)
    }

    fun analyze(file: File, displayName: String, cancel: CancelCheck, progress: ProgressCallback?): JarReport =
        JarAnalyzer(jarLimits, paths.sessions).analyze(file, displayName, cancel, progress)

    /** Estrae da una raccolta il .jar che si trova a [entryPath]. Restituisce false se non c'è. */
    fun extractFromBundle(archive: File, entryPath: String, target: File, cancel: CancelCheck): Boolean =
        JarAnalyzer.extractEntry(archive, entryPath, target, jarLimits.maxFileBytes, cancel)
}
