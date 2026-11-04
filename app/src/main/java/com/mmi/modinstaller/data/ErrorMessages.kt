package com.mmi.modinstaller.data

import com.mmi.modinstaller.core.ArchiveCorruptException
import com.mmi.modinstaller.core.ArchiveException
import com.mmi.modinstaller.core.ArchivePasswordException
import com.mmi.modinstaller.core.ArchiveUnsafeException
import com.mmi.modinstaller.core.ArchiveUnsupportedException
import com.mmi.modinstaller.core.LimitExceededException
import com.mmi.modinstaller.core.PreparationException
import java.io.FileNotFoundException
import java.io.IOException

/** Messaggi comprensibili per l'utente a partire dalle eccezioni. Nessuna dipendenza da Android: si testa sulla JVM. */
object ErrorMessages {

    fun describe(e: Throwable): String {
        val text = e.message.orEmpty()
        return when (e) {
            is LimitExceededException -> text.ifEmpty { "Il file supera i limiti di sicurezza dell'app" }
            is ArchivePasswordException ->
                "L'archivio è protetto da password e l'app non può aprirlo. Estrai i file con la password e riprova."
            is ArchiveUnsafeException ->
                "L'archivio contiene percorsi pericolosi (tentativo di scrivere fuori dalla cartella di destinazione) " +
                    "ed è stato rifiutato per sicurezza."
            is ArchiveUnsupportedException -> text.ifEmpty { "Formato di archivio non supportato" }
            is ArchiveCorruptException -> text.ifEmpty { "L'archivio è danneggiato o incompleto" }
            is PreparationException -> text.ifEmpty { "Preparazione del pacchetto non riuscita" }
            is ArchiveException -> text.ifEmpty { "Archivio non valido" }
            is FileNotFoundException ->
                "Il file non è più disponibile (spostato, eliminato o non più accessibile). Selezionalo di nuovo."
            is SecurityException ->
                "Android non permette più di leggere questo file. Selezionalo di nuovo dal selettore dei file."
            is OutOfMemoryError -> "Memoria insufficiente per elaborare questo file."
            is IOException ->
                if (text.contains("No space left", ignoreCase = true) || text.contains("ENOSPC", ignoreCase = true)) {
                    "Spazio insufficiente sul telefono. Libera memoria e riprova."
                } else {
                    "Errore di lettura o scrittura del file" + (if (text.isNotEmpty()) " ($text)" else "")
                }
            else -> "Errore imprevisto (${e.javaClass.simpleName})" + (if (text.isNotEmpty()) ": $text" else "")
        }
    }
}
