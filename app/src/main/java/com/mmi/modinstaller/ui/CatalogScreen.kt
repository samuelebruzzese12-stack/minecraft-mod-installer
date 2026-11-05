package com.mmi.modinstaller.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mmi.modinstaller.CatalogBatch
import com.mmi.modinstaller.CatalogDetail
import com.mmi.modinstaller.CatalogState
import com.mmi.modinstaller.CatalogViewModel
import com.mmi.modinstaller.ModsViewModel
import com.mmi.modinstaller.catalog.CatalogProject
import com.mmi.modinstaller.catalog.CatalogPlan
import com.mmi.modinstaller.catalog.ProjectKind
import com.mmi.modinstaller.catalog.ReleaseChannel
import com.mmi.modinstaller.catalog.SearchSort
import com.mmi.modinstaller.core.formatBytes
import com.mmi.modinstaller.download.DownloadRecord
import com.mmi.modinstaller.download.DownloadState

/**
 * CERCA ONLINE (dentro il MOD MANAGER): cerca su Modrinth, sceglie la versione adatta al profilo attivo con le sue
 * dipendenze obbligatorie e mette i file nel DOWNLOAD MANAGER. Poi i file passano al MOD MANAGER, che li controlla.
 */
@Composable
fun CatalogScreen(
    catalog: CatalogState,
    model: CatalogViewModel,
    mods: ModsViewModel,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    openMods: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { model.onOpened() }

    SectionPage(title = "CERCA ONLINE", onBack = onBack, onMenu = onMenu) {
        InfoCard {
            Text(text = "Catalogo Modrinth", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Bullets(
                listOf(
                    "Cerca mod, resource pack, shader e data pack su Modrinth. Il testo cercato e i filtri vengono inviati a " +
                        "api.modrinth.com; nient'altro.",
                    "L'app sceglie la versione adatta al tuo profilo e le dipendenze obbligatorie. I file passano dal " +
                        "DOWNLOAD MANAGER, che controlla l'impronta (SHA-512 o SHA-1) pubblicata da Modrinth prima di tenerli.",
                    "Poi li legge e li controlla il MOD MANAGER e li installi tu. Non viene eseguito codice delle mod. " +
                        "CurseForge non è collegato: richiede una chiave personale."
                )
            )
        }

        val message = catalog.message
        if (message != null) {
            InfoCard {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { model.clearMessage() }) { Text("OK") }
            }
        }

        val batch = catalog.batch
        if (batch != null) BatchCard(batch, model, mods, openMods)

        SectionLabel("Cosa cercare")
        InfoCard {
            val profile = catalog.profileLabel
            if (profile != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(text = "Solo file adatti al profilo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            text = "Profilo attivo: $profile",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = catalog.onlyForProfile, onCheckedChange = { model.setOnlyForProfile(it) })
                }
            } else {
                Text(
                    text = "Nessun profilo: la ricerca non è filtrata e per scaricare serve prima creare un profilo nel MOD MANAGER.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            for (row in CatalogViewModel.KINDS.chunked(2)) {
                Buttons {
                    for (k in row) {
                        if (k == catalog.kind) {
                            FilledTonalButton(onClick = { model.setKind(k) }, modifier = Modifier.weight(1f)) { Text(k.label) }
                        } else {
                            OutlinedButton(onClick = { model.setKind(k) }, modifier = Modifier.weight(1f)) { Text(k.label) }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Nome (anche vuoto: i più diffusi)") }
            )
            Buttons {
                for (sort in listOf(SearchSort.RELEVANCE, SearchSort.DOWNLOADS, SearchSort.NEWEST)) {
                    if (sort == catalog.sort) {
                        FilledTonalButton(onClick = { model.setSort(sort) }, modifier = Modifier.weight(1f)) { Text(sort.label) }
                    } else {
                        OutlinedButton(onClick = { model.setSort(sort) }, modifier = Modifier.weight(1f)) { Text(sort.label) }
                    }
                }
            }
            GreenButton(text = "CERCA", onClick = { model.search(text) }, enabled = !catalog.searching, height = 52.dp)
        }

        if (catalog.searching) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(text = "Cerco su Modrinth…", style = MaterialTheme.typography.bodyMedium)
        }
        val error = catalog.error
        if (error != null) {
            InfoCard {
                ToneRow(Tone.BAD, error)
                TextButton(onClick = { model.clearMessage() }) { Text("OK") }
            }
        }

        if (catalog.searched && !catalog.searching && catalog.error == null) {
            if (catalog.results.isEmpty()) {
                Text(
                    text = "Nessun risultato su Modrinth" + if (catalog.onlyForProfile && catalog.profileLabel != null) " per " + catalog.profileLabel + "." else ".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                SectionLabel("Risultati (" + catalog.results.size + " di " + catalog.total + ")")
            }
        }
        for (project in catalog.results) ProjectCard(project, model)
        if (catalog.results.isNotEmpty() && catalog.results.size < catalog.total && !catalog.searching) {
            FullButton("MOSTRA ALTRI RISULTATI") { model.loadMore() }
        }
    }

    val detail = catalog.detail
    if (detail != null) DetailDialog(detail, catalog, model)
}

// ---------------------------------------------------------------------------------------------------- risultati

@Composable
private fun ProjectCard(project: CatalogProject, model: CatalogViewModel) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = project.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }
        val by = project.author?.let { "di $it · " } ?: ""
        Text(
            text = by + Format.count(project.downloads) + " download",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val description = Format.clean(project.description)
        if (description.isNotBlank()) {
            Text(text = description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (project.gameVersions.isNotEmpty()) {
            Text(
                text = "Minecraft: " + versionsSummary(project.gameVersions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FullButton("VEDI VERSIONE E SCARICA") { model.select(project) }
    }
}

/** «1.20.1» · «1.19.4 … 1.21» (la prima e l'ultima, perché l'elenco intero può essere lunghissimo). */
private fun versionsSummary(versions: List<String>): String =
    if (versions.size <= 3) versions.joinToString(", ") else versions.first() + " … " + versions.last() + " (" + versions.size + " versioni)"

// ---------------------------------------------------------------------------------------------------- scelta e piano

@Composable
private fun DetailDialog(detail: CatalogDetail, catalog: CatalogState, model: CatalogViewModel) {
    val plan = detail.plan
    StackedDialog(
        title = detail.project.displayName,
        onDismiss = { model.closeDetail() },
        cancelable = true,
        body = {
            when {
                detail.loading -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    BodyText("Cerco la versione adatta" + (catalog.profileLabel?.let { " a $it" } ?: "") + " e le sue dipendenze…")
                }
                detail.error != null -> ToneRow(Tone.BAD, detail.error)
                plan != null -> PlanBody(plan)
            }
        },
        buttons = {
            if (plan != null && plan.items.isNotEmpty() && !plan.blocked) {
                GreenDialogButton("SCARICA " + plan.items.size + " FILE") { model.download() }
            }
            CloseDialogButton(if (plan != null && plan.items.isNotEmpty()) "ANNULLA" else "CHIUDI") { model.closeDetail() }
        }
    )
}

@Composable
private fun PlanBody(plan: CatalogPlan) {
    for (problem in plan.problems.filter { it.blocking }) ToneRow(Tone.BAD, problem.text)
    if (plan.items.isNotEmpty()) {
        BodyText("Verranno scaricati:")
        for (item in plan.items) {
            val channel = if (item.version.channel == ReleaseChannel.RELEASE) "" else " (" + item.version.channel.label + ")"
            Text(
                text = item.project.displayName + " " + item.version.versionNumber + channel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            val sizeText = if (item.file.size > 0) " · " + formatBytes(item.file.size) else ""
            Text(
                text = item.file.fileName + sizeText + if (item.requiredBy != null) " · richiesta da «" + item.requiredBy + "»" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "Totale: " + Format.plural(plan.items.size, "file", "file") + " · " + formatBytes(plan.totalBytes),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    } else if (plan.alreadyInstalled.isNotEmpty() && !plan.blocked) {
        ToneRow(Tone.OK, "È già nel profilo: non c'è niente da scaricare.")
    }
    if (plan.alreadyInstalled.isNotEmpty()) {
        BodyText("Già nel profilo (non si scaricano):")
        Bullets(plan.alreadyInstalled.map { "«" + it.title + "»: " + it.note })
    }
    for (problem in plan.problems.filter { !it.blocking }) ToneRow(Tone.WARN, problem.text)
    if (plan.incompatible.isNotEmpty()) {
        ToneRow(Tone.WARN, "Dichiarata incompatibile con: " + plan.incompatible.joinToString(", ") { "«" + it.title + "»" })
    }
    if (plan.optional.isNotEmpty()) {
        Text(
            text = "Facoltative, non scaricate: " + plan.optional.joinToString(", ") { it.title },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (plan.items.isNotEmpty()) {
        Text(
            text = "Ogni file viene verificato con l'impronta pubblicata da Modrinth prima di essere tenuto. Poi lo " +
                "controlla il MOD MANAGER e lo installi tu.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------------------------------- scarico in corso

@Composable
private fun BatchCard(batch: CatalogBatch, model: CatalogViewModel, mods: ModsViewModel, openMods: () -> Unit) {
    SectionLabel("Scarico")
    InfoCard {
        Text(text = "«" + batch.title + "»", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        for (record in batch.records) RecordRow(record)
        if (batch.records.size < batch.downloadIds.size) {
            Text(text = "Preparazione dello scarico…", style = MaterialTheme.typography.bodySmall)
        }
        when {
            batch.completed -> {
                ToneRow(Tone.OK, "Scaricato e verificato", "Ora li legge il MOD MANAGER, che li controlla sul profilo prima che tu li installi.")
                GreenButton(
                    text = "AGGIUNGI AL PROFILO (" + Format.plural(batch.records.size, "file", "file") + ")",
                    onClick = { model.addToProfile(mods, openMods) },
                    height = 52.dp
                )
                FullButton("CHIUDI") { model.dismissBatch() }
            }
            batch.finished -> {
                ToneRow(Tone.BAD, "Non tutti i file sono arrivati", "I file riusciti restano nel DOWNLOAD MANAGER; quelli falliti si possono riprovare da lì o da qui.")
                FullButton("CHIUDI") { model.dismissBatch() }
            }
            else -> FullButton("ANNULLA LO SCARICO") { model.cancelBatch() }
        }
    }
}

@Composable
private fun RecordRow(record: DownloadRecord) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = record.fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        val fraction = record.fraction
        if (record.state == DownloadState.RUNNING && fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else if (record.state == DownloadState.RUNNING || record.state == DownloadState.QUEUED) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        val detail = when {
            record.state == DownloadState.FAILED && record.error != null -> record.state.label + ": " + record.error
            record.state == DownloadState.RUNNING && record.totalBytes > 0 ->
                record.state.label + " · " + formatBytes(record.doneBytes) + " di " + formatBytes(record.totalBytes)
            record.state == DownloadState.COMPLETED -> record.state.label + " · " + formatBytes(record.doneBytes) + " · impronta verificata"
            else -> record.state.label
        }
        Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------------------------------------------- pulsanti

@Composable
private fun Buttons(content: @Composable RowScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun FullButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
}
