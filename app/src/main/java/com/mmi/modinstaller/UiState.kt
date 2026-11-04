package com.mmi.modinstaller

import com.mmi.modinstaller.core.AnalysisReport
import com.mmi.modinstaller.core.BackupInfo
import com.mmi.modinstaller.core.ContentItem
import com.mmi.modinstaller.core.ContentKind
import com.mmi.modinstaller.core.HistoryEntry
import com.mmi.modinstaller.core.InstallState
import com.mmi.modinstaller.core.InstalledEntry
import com.mmi.modinstaller.core.ItemStatus
import com.mmi.modinstaller.core.RecentFile
import java.io.File

enum class Screen { HOME, REPORT, RECENT, INSTALLED, BACKUPS, HISTORY, ABOUT }

/** Quale pulsante della home ha aperto il selettore: serve solo a segnalare se il file scelto è di altro tipo. */
enum class PickPurpose(val label: String, val expected: Set<ContentKind>) {
    ANY("file", emptySet()),
    MOD("mod", setOf(ContentKind.BEHAVIOR_PACK, ContentKind.ADDON)),
    MAP("mappa", setOf(ContentKind.WORLD, ContentKind.WORLD_TEMPLATE)),
    TEXTURE("texture", setOf(ContentKind.RESOURCE_PACK, ContentKind.SKIN_PACK)),
    ADDON("add-on", setOf(ContentKind.ADDON, ContentKind.BEHAVIOR_PACK, ContentKind.RESOURCE_PACK))
}

/** Operazione lunga in corso (analisi, preparazione): mostrata a tutto schermo con il pulsante Annulla. */
class BusyState(val title: String, val note: String, val done: Long, val total: Long) {
    /** Avanzamento da 0 a 1, o null se non è noto. */
    val fraction: Float?
        get() = if (total > 0) (done.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat() else null
}

class MinecraftStatus(val installed: Boolean, val label: String?, val versionName: String?)

/** Il rapporto di un file scelto dall'utente (o l'errore, se non è stato possibile leggerlo). */
class FileReport(
    val index: Int,
    val name: String,
    val sizeBytes: Long,
    val report: AnalysisReport?,
    val error: String?,
    /** Cartella temporanea che contiene la copia di lavoro; va eliminata quando il rapporto non serve più. */
    val sessionDir: File?,
    /** Avviso quando il tipo trovato non coincide con il pulsante usato per scegliere il file. */
    val purposeNote: String?
)

/**
 * Che cosa è stato consegnato a Minecraft, per dire all'utente quali pacchetti deve ritrovare nel gioco.
 * [includedFiles] e [sourceFiles] sono 0 quando non sono noti (ad esempio per un nuovo invio dall'elenco).
 */
class DeliveryInfo(
    val packNames: List<String>,
    val includedFiles: Int,
    val sourceFiles: Int,
    val itemCount: Int
)

/** Messaggio temporaneo in basso, con un'eventuale azione (es. ANNULLA dopo un'eliminazione). */
class Notice(val id: Long, val text: String, val actionLabel: String?)

sealed class DialogState {
    object MinecraftMissing : DialogState()
    class EntryActions(val entry: InstalledEntry) : DialogState()
    class ConfirmDelete(val entry: InstalledEntry) : DialogState()
    class ConfirmDeleteBackup(val backup: BackupInfo) : DialogState()
    object ConfirmClearHistory : DialogState()
    object ConfirmClearRecents : DialogState()
}

data class UiState(
    val screen: Screen = Screen.HOME,
    val busy: BusyState? = null,
    val minecraft: MinecraftStatus = MinecraftStatus(false, null, null),
    val files: List<FileReport> = emptyList(),
    /** Chiavi (vedi [itemKey]) dei contenuti spuntati nel rapporto. */
    val selected: Set<String> = emptySet(),
    /** Chiavi dei contenuti con un errore superabile (es. versione troppo vecchia) per cui l'utente ha scelto di forzare. */
    val forced: Set<String> = emptySet(),
    val recents: List<RecentFile> = emptyList(),
    val installed: List<InstalledEntry> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val backups: List<BackupInfo> = emptyList(),
    val query: String = "",
    /** Contenuto inviato a Minecraft di cui si attende la conferma dell'utente. */
    val awaiting: InstalledEntry? = null,
    val confirmVisible: Boolean = false,
    /** Contenuti ancora da inviare dopo quello in attesa di conferma. */
    val queueRemaining: Int = 0,
    /** Che cosa contiene il file consegnato di cui si attende la conferma. */
    val delivery: DeliveryInfo? = null,
    val dialog: DialogState? = null,
    val notice: Notice? = null
) {
    /** Contenuti inviati a Minecraft che l'utente non ha ancora confermato né segnalato come falliti. */
    val unconfirmed: List<InstalledEntry>
        get() = installed.filter { it.state == InstallState.SENT }
}

/** Identificativo di un contenuto nell'elenco dei rapporti (gli id interni si ripetono tra file diversi). */
fun itemKey(fileIndex: Int, item: ContentItem): String = "$fileIndex/${item.id}"

/** Un contenuto spuntato nel rapporto, pronto per essere consegnato. */
class PlannedItem(val file: FileReport, val sessionDir: File, val item: ContentItem)

/** I contenuti che «INSTALLA» consegnerebbe adesso: spuntati, installabili e, se serve, forzati. */
fun plannedItems(state: UiState): List<PlannedItem> {
    val planned = ArrayList<PlannedItem>()
    for (file in state.files) {
        val report = file.report ?: continue
        val dir = file.sessionDir ?: continue
        for (item in report.items) {
            val key = itemKey(file.index, item)
            if (key !in state.selected || !item.installable) continue
            if (item.status == ItemStatus.OVERRIDE && key !in state.forced) continue
            planned.add(PlannedItem(file, dir, item))
        }
    }
    return planned
}

/** Contenuti spuntati che hanno bisogno di un altro contenuto dello stesso file che non è spuntato. */
fun missingCompanions(state: UiState): List<String> {
    val planned = plannedItems(state)
    val keys = planned.map { itemKey(it.file.index, it.item) }.toSet()
    val result = ArrayList<String>()
    for (p in planned) {
        val report = p.file.report ?: continue
        for (id in p.item.requires) {
            val other = report.items.firstOrNull { it.id == id } ?: continue
            if (itemKey(p.file.index, other) !in keys) {
                result.add("«${p.item.title}» ha bisogno di «${other.title}», che non è selezionato")
            }
        }
    }
    return result
}

/** Perché un contenuto non è spuntato in automatico (null se lo è, o se l'utente lo ha tolto di sua scelta). */
fun notSelectedReason(item: ContentItem, forced: Boolean): String? = when {
    !item.installable -> "non installabile"
    item.status == ItemStatus.OVERRIDE && !forced -> "serve forzare l'installazione"
    item.sameUuidAs != null -> "ha lo stesso UUID di «${item.sameUuidAs}»"
    else -> null
}
