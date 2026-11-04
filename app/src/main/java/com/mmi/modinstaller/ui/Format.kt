package com.mmi.modinstaller.ui

import com.mmi.modinstaller.core.ArchiveFormat
import com.mmi.modinstaller.core.ContentItem
import com.mmi.modinstaller.core.DependencyState
import com.mmi.modinstaller.core.DependencyStatus
import com.mmi.modinstaller.core.InstallState
import com.mmi.modinstaller.core.ItemStatus
import com.mmi.modinstaller.core.PackKind
import com.mmi.modinstaller.core.PartInfo
import com.mmi.modinstaller.core.PartRole
import com.mmi.modinstaller.core.Severity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tono di uno stato: determina colore e icona. */
enum class Tone { OK, WARN, BAD, INFO }

/** Testi e stati mostrati dall'interfaccia. Nessuna dipendenza da Android o Compose: si testa sulla JVM. */
object Format {

    fun dateTime(millis: Long): String =
        if (millis <= 0L) "—" else SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(millis))

    class StatusView(val tone: Tone, val title: String, val detail: String?)

    fun status(item: ContentItem): StatusView = when (item.status) {
        ItemStatus.READY ->
            if (item.compatibilityUnverified) {
                StatusView(
                    Tone.INFO, "Pronto · compatibilità non verificata",
                    "Non conosco la versione di Minecraft installata, quindi non posso confrontarla con quella richiesta."
                )
            } else {
                StatusView(Tone.OK, "Compatibile e pronto da installare", null)
            }
        ItemStatus.WARNINGS -> StatusView(Tone.WARN, "Installabile, con avvisi", null)
        ItemStatus.OVERRIDE -> StatusView(
            Tone.WARN, "Probabilmente non compatibile",
            "Puoi forzare l'installazione, ma Minecraft potrebbe rifiutarla o il contenuto potrebbe non funzionare."
        )
        ItemStatus.BLOCKED -> StatusView(Tone.BAD, "Non installabile", "Risolvi gli errori elencati qui sotto.")
    }

    fun severityTone(severity: Severity): Tone = when (severity) {
        Severity.ERROR -> Tone.BAD
        Severity.WARNING -> Tone.WARN
        Severity.INFO -> Tone.INFO
    }

    fun dependencyName(d: DependencyStatus): String =
        d.resolvedName ?: d.moduleName ?: d.uuid ?: "Dipendenza sconosciuta"

    fun dependencyState(d: DependencyStatus): String = when (d.state) {
        DependencyState.IN_ITEM -> "incluso in questo contenuto"
        DependencyState.IN_ARCHIVE -> "incluso nel file (altro contenuto)"
        DependencyState.IN_LIBRARY -> "già importato da te"
        DependencyState.BUILTIN -> "integrato in Minecraft"
        DependencyState.SCRIPT_MODULE -> "modulo script di Minecraft"
        DependencyState.MISSING -> "MANCANTE"
    }

    fun dependencyTone(d: DependencyStatus): Tone =
        if (d.state == DependencyState.MISSING) Tone.BAD else Tone.OK

    fun packKind(kind: PackKind?): String = when (kind) {
        PackKind.RESOURCE -> "Resource Pack"
        PackKind.BEHAVIOR -> "Behavior Pack"
        PackKind.SKIN -> "Skin Pack"
        PackKind.WORLD_TEMPLATE -> "Template di mondo"
        PackKind.UNKNOWN, null -> "Pacchetto"
    }

    fun partLabel(part: PartInfo): String = when (part.role) {
        PartRole.WORLD -> "Mondo"
        PartRole.TEMPLATE -> "Template di mondo"
        PartRole.PACK -> packKind(part.packKind)
    }

    fun installState(state: InstallState): String = when (state) {
        InstallState.SENT -> "Inviato a Minecraft · da confermare"
        InstallState.CONFIRMED -> "Importazione confermata da te"
        InstallState.FAILED -> "Non importato"
    }

    fun installTone(state: InstallState): Tone = when (state) {
        InstallState.SENT -> Tone.WARN
        InstallState.CONFIRMED -> Tone.OK
        InstallState.FAILED -> Tone.BAD
    }

    fun plural(count: Int, one: String, many: String): String = if (count == 1) "$count $one" else "$count $many"

    private val FORMAT_CODES = Regex("\u00A7.")

    /** Toglie i codici di colore di Minecraft (§a, §l, ...) che compaiono nei nomi e nelle descrizioni dei pacchetti. */
    fun clean(text: String): String = text.replace(FORMAT_CODES, "").trim()

    fun archiveFormat(format: ArchiveFormat): String = when (format) {
        ArchiveFormat.ZIP -> "ZIP"
        ArchiveFormat.SEVEN_Z -> "7z"
        ArchiveFormat.RAR -> "RAR"
        ArchiveFormat.RAR5 -> "RAR5"
        ArchiveFormat.JSON -> "JSON"
        ArchiveFormat.HTML -> "pagina web"
        ArchiveFormat.GZIP -> "GZIP"
        ArchiveFormat.UNKNOWN -> "non riconosciuto"
    }
}
