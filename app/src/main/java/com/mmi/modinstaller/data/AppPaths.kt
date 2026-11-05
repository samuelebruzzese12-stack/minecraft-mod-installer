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

    /** File scaricati dal DOWNLOAD MANAGER (e i loro parziali «.part»). Restano nello spazio privato dell'app. */
    val downloads: File = File(context.filesDir, "downloads")

    /** Profili Java del MOD MANAGER: ognuno ha le sue cartelle mods, resourcepacks, shaderpacks, datapacks e il cestino. */
    val javaProfiles: File = File(context.filesDir, "java/profiles")

    /** Copie dei modpack installati (.mrpack): servono a completare un'installazione rimasta a metà. */
    val modpacks: File = File(context.filesDir, "modpacks")

    /**
     * Copie degli archivi di mondi scelti nel WORLD MANAGER: l'importazione legge queste, mai il file dell'utente, e se l'app si
     * chiude a metà servono a riprendere. Si cancellano a importazione finita o annullata.
     */
    val worldsIncoming: File = File(context.filesDir, "worlds/incoming")

    /** JAVA TO BEDROCK: copie delle mod scelte (si cancellano dopo la conversione) e add-on Bedrock creati. */
    val convertIncoming: File = File(context.filesDir, "convert/incoming")
    val convertedAddons: File = File(context.filesDir, "convert/addons")

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
        downloads.mkdirs()
        javaProfiles.mkdirs()
        modpacks.mkdirs()
        worldsIncoming.mkdirs()
        convertIncoming.mkdirs()
        convertedAddons.mkdirs()
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
