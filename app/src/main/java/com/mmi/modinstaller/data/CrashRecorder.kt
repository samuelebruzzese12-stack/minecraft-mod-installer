package com.mmi.modinstaller.data

import com.mmi.modinstaller.core.CrashReport
import com.mmi.modinstaller.core.ErrorLog
import java.io.File

/**
 * Salva su disco la causa di un arresto inatteso dell'app, così che la Diagnostica possa mostrarla al riavvio.
 * Non impedisce né nasconde l'arresto: dopo aver scritto il file passa l'errore al gestore originale di Android.
 */
object CrashRecorder {

    @Volatile
    private var installed = false

    fun install(file: File) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                file.parentFile?.mkdirs()
                file.writeText(
                    CrashReport.encode(System.currentTimeMillis(), thread.name, error.stackTraceToString()),
                    Charsets.UTF_8
                )
            } catch (ignored: Throwable) {
                // Nulla può impedire che l'arresto prosegua normalmente.
            }
            if (previous != null) previous.uncaughtException(thread, error)
        }
    }

    /** Sposta nel registro errori il rapporto dell'ultimo arresto, se c'è, e lo elimina. */
    fun importPending(file: File, log: ErrorLog) {
        if (!file.isFile) return
        try {
            val record = CrashReport.decode(file.readText(Charsets.UTF_8))
            if (record != null) log.record(record.where, record.message, record.detail, record.at)
        } catch (ignored: Exception) {
            // File illeggibile: non c'è nulla da recuperare.
        }
        file.delete()
    }
}
