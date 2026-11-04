package com.mmi.modinstaller.data

import android.content.Context
import java.io.File

/** Cartelle usate dall'app. Nulla viene scritto fuori dallo spazio privato dell'app. */
class AppPaths(context: Context) {

    /**
     * Pacchetti pronti per Minecraft. Sono i "contenuti gestiti" dall'app e sono gli unici file esposti
     * a Minecraft tramite FileProvider (vedi res/xml/file_paths.xml).
     */
    val prepared: File = File(context.filesDir, "prepared")

    /** Copie di sicurezza create prima di eliminare un pacchetto. */
    val backups: File = File(context.filesDir, "backups")

    /** Elenchi persistenti: file recenti, contenuti gestiti, cronologia. */
    val libraryFile: File = File(context.filesDir, "library/library.json")

    /** Rapporto dell'ultimo arresto inatteso, scritto da CrashRecorder e letto al riavvio. */
    val crashFile: File = File(context.filesDir, "last_crash.txt")

    /** Copie temporanee dei file scelti e archivi annidati estratti. Si svuota a ogni avvio dell'app. */
    val sessions: File = File(context.cacheDir, "sessions")

    fun ensure() {
        prepared.mkdirs()
        backups.mkdirs()
        libraryFile.parentFile?.mkdirs()
        sessions.mkdirs()
    }

    fun clearSessions() {
        sessions.deleteRecursively()
        sessions.mkdirs()
    }

    /** Elimina i file temporanei di pacchetti non completati (.nome.part) lasciati da un'interruzione. */
    fun clearPartialFiles() {
        val files = prepared.listFiles() ?: return
        for (f in files) {
            if (f.isFile && f.name.startsWith(".") && f.name.endsWith(".part")) f.delete()
        }
    }
}
