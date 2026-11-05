package com.mmi.modinstaller.worlds

import com.mmi.modinstaller.core.SafetyLimits

/** Per quale edizione di Minecraft è un mondo. */
enum class WorldEdition(val label: String) {
    JAVA("Java Edition"),
    BEDROCK("Bedrock Edition")
}

/** Una dimensione di un mondo Java con il numero dei suoi file di regione (ognuno copre 32×32 chunk). */
data class Dimension(val id: String, val label: String, val regionFiles: Int)

/** Quanto è grave un'osservazione sul mondo. */
enum class WorldNoteLevel { INFO, WARNING, ERROR }

data class WorldNote(val level: WorldNoteLevel, val text: String)

/**
 * Un mondo trovato in un archivio, letto senza estrarlo. [root] è la cartella dell'archivio che lo contiene ("" = radice).
 * Tutti i numeri vengono dall'elenco dei file e da `level.dat`: nessuna regione viene aperta.
 */
class WorldInfo(
    val root: String,
    val edition: WorldEdition,
    /** Nome scritto in `level.dat` (LevelName), o null se non c'è. */
    val levelName: String?,
    /** Versione di Minecraft con cui è stato salvato l'ultima volta (Java: Version.Name; Bedrock: lastOpenedWithVersion). */
    val version: String?,
    /** Solo Java: DataVersion. */
    val dataVersion: Int?,
    /** 0 sopravvivenza, 1 creativa, 2 avventura, 3 spettatore; null se non indicata. */
    val gameType: Int?,
    val hardcore: Boolean,
    /** File del mondo (senza cartelle e senza `session.lock`). */
    val files: Int,
    /** Byte dei file una volta estratti. */
    val bytes: Long,
    /** Solo Java: le dimensioni con le loro regioni (mondo principale, Nether, End e quelle aggiunte dai data pack). */
    val dimensions: List<Dimension>,
    /** Solo Java: giocatori salvati (playerdata). */
    val players: Int,
    /** Solo Java: data pack del mondo. */
    val datapacks: List<String>,
    val hasIcon: Boolean,
    val notes: List<WorldNote>
) {
    /** Nome da mostrare: quello del mondo, o della cartella, o «Mondo». */
    val title: String
        get() = levelName?.trim()?.takeIf { it.isNotEmpty() } ?: root.substringAfterLast('/').ifEmpty { "Mondo" }

    val gameModeLabel: String?
        get() = when (gameType) {
            0 -> if (hardcore) "Hardcore" else "Sopravvivenza"
            1 -> "Creativa"
            2 -> "Avventura"
            3 -> "Spettatore"
            else -> null
        }

    val regionFiles: Int get() = dimensions.sumOf { it.regionFiles }

    /** Un errore impedisce l'importazione (per esempio `level.dat` illeggibile). */
    val importable: Boolean get() = notes.none { it.level == WorldNoteLevel.ERROR }
}

/** Esito della lettura di un archivio di mondi. */
sealed class WorldScanResult {
    class Ok(val worlds: List<WorldInfo>, val archiveBytes: Long) : WorldScanResult()
    class Failed(val message: String, val detail: String? = null) : WorldScanResult()
}

/** Limiti per gli archivi di mondi: più larghi di quelli dei pacchetti (un mondo esplorato a lungo pesa qualche GB). */
object WorldLimits {
    val DEFAULT = SafetyLimits(
        maxArchiveBytes = 8L * 1024 * 1024 * 1024,
        maxTotalUncompressedBytes = 16L * 1024 * 1024 * 1024,
        maxEntryBytes = 2L * 1024 * 1024 * 1024,
        maxEntries = 400_000,
        maxLevelDatBytes = 8 * 1024 * 1024
    )
}
