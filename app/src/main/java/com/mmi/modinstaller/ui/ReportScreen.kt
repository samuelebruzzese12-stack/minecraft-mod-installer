package com.mmi.modinstaller.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mmi.modinstaller.FileReport
import com.mmi.modinstaller.MainViewModel
import com.mmi.modinstaller.UiState
import com.mmi.modinstaller.core.ContentItem
import com.mmi.modinstaller.core.ItemStatus
import com.mmi.modinstaller.core.Severity
import com.mmi.modinstaller.core.formatBytes
import com.mmi.modinstaller.itemKey

/** Chiavi dei contenuti che si possono installare adesso (installabili e, se serve, forzati). */
private fun selectableKeys(state: UiState): Set<String> {
    val keys = HashSet<String>()
    for (file in state.files) {
        val report = file.report ?: continue
        for (item in report.items) {
            val key = itemKey(file.index, item)
            if (item.installable && (item.status != ItemStatus.OVERRIDE || key in state.forced)) keys.add(key)
        }
    }
    return keys
}

@Composable
fun ReportScreen(state: UiState, vm: MainViewModel) {
    val selectable = remember(state.files, state.forced) { selectableKeys(state) }
    val selectedCount = remember(selectable, state.selected) { selectable.count { it in state.selected } }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = if (state.files.size > 1) "Rapporto (${state.files.size} file)" else "Rapporto di analisi",
            onBack = { vm.back() }
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            for (file in state.files) {
                item(key = "file-${file.index}") { FileHeaderCard(file) }
                val report = file.report
                if (report != null) {
                    items(report.items, key = { itemKey(file.index, it) }) { item ->
                        ItemCard(file.index, item, state, vm)
                    }
                }
            }
        }
        InstallBar(state, vm, selectedCount, selectable.size)
    }
}

@Composable
private fun FileHeaderCard(file: FileReport) {
    val report = file.report
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            val format = if (report != null) " · formato " + Format.archiveFormat(report.source.format) else ""
            Text(
                text = formatBytes(file.sizeBytes) + format,
                style = MaterialTheme.typography.bodySmall
            )
            val error = file.error
            if (error != null) ToneRow(Tone.BAD, "Impossibile leggere il file", error)
            val note = file.purposeNote
            if (note != null) ToneRow(Tone.INFO, note)
            if (report != null) {
                for (f in report.findings.sortedByDescending { it.severity.ordinal }) {
                    ToneRow(Format.severityTone(f.severity), f.message, f.detail)
                }
                if (report.items.isEmpty() && report.findings.none { it.severity == Severity.ERROR }) {
                    ToneRow(Tone.BAD, "Nessun contenuto di Minecraft riconosciuto in questo file")
                }
            }
        }
    }
}

@Composable
private fun ItemCard(fileIndex: Int, item: ContentItem, state: UiState, vm: MainViewModel) {
    val key = itemKey(fileIndex, item)
    val forced = key in state.forced
    val canSelect = item.installable && (item.status != ItemStatus.OVERRIDE || forced)
    val status = Format.status(item)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(
                    checked = key in state.selected,
                    onCheckedChange = { vm.toggleSelected(key) },
                    enabled = canSelect
                )
                val icon = item.iconBytes
                if (icon != null) {
                    BytesImage(icon, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = Format.clean(item.title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val version = item.version
                    val subtitle = item.kind.label + (if (version.isNullOrBlank()) "" else " · v$version")
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    val author = item.author
                    if (!author.isNullOrBlank()) {
                        Text(
                            text = "Autore: " + Format.clean(author),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = formatBytes(item.sizeBytes) + " · " + Format.plural(item.fileCount, "file", "file"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            ToneRow(status.tone, status.title, status.detail)

            val description = item.description
            if (!description.isNullOrBlank()) {
                Text(
                    text = Format.clean(description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5
                )
            }

            if (item.previewImages.isNotEmpty()) {
                SectionLabel("Anteprima")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (bytes in item.previewImages.take(8)) {
                        BytesImage(bytes, Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)))
                    }
                }
            }

            if (item.parts.isNotEmpty()) {
                SectionLabel("Contenuto")
                for (part in item.parts.take(8)) {
                    Text(
                        text = "• " + Format.clean(part.displayName) + " — " + Format.partLabel(part) +
                            " · " + Format.plural(part.fileCount, "file", "file") + " · " + formatBytes(part.sizeBytes),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (item.parts.size > 8) {
                    Text(
                        text = "…e altri ${item.parts.size - 8}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (item.dependencies.isNotEmpty()) {
                SectionLabel("Dipendenze")
                for (dependency in item.dependencies) {
                    ToneRow(
                        tone = Format.dependencyTone(dependency),
                        title = Format.dependencyName(dependency),
                        detail = Format.dependencyState(dependency)
                    )
                }
            }

            if (item.findings.isNotEmpty()) {
                SectionLabel("Controlli")
                for (finding in item.findings.sortedByDescending { it.severity.ordinal }) {
                    ToneRow(Format.severityTone(finding.severity), finding.message, finding.detail)
                }
            }

            if (item.status == ItemStatus.OVERRIDE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = forced, onCheckedChange = { vm.setForced(key, it) })
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Forza l'installazione, a mio rischio",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun InstallBar(state: UiState, vm: MainViewModel, selectedCount: Int, selectableCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!state.minecraft.installed) {
                ToneRow(Tone.BAD, "Minecraft non è installato su questo telefono")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (selectableCount == 0) "Nessun contenuto installabile" else "$selectedCount di $selectableCount selezionati",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                if (selectableCount > 0) {
                    val selectAll = selectedCount < selectableCount
                    TextButton(onClick = { vm.selectAll(selectAll) }) {
                        Text(if (selectAll) "Seleziona tutti" else "Deseleziona")
                    }
                }
            }
            GreenButton(
                text = if (selectedCount > 1) "INSTALLA IN MINECRAFT ($selectedCount)" else "INSTALLA IN MINECRAFT",
                onClick = { vm.install() },
                enabled = selectedCount > 0 && state.busy == null
            )
            Text(
                text = "Preparo il pacchetto, apro Minecraft e gli consegno il file: l'importazione la esegue Minecraft. " +
                    "Alla fine ti chiederò di confermare cosa hai visto nel gioco.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
