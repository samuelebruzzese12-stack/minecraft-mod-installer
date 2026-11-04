package com.mmi.modinstaller.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mmi.modinstaller.MainViewModel
import com.mmi.modinstaller.UiState
import com.mmi.modinstaller.core.TextSearch
import com.mmi.modinstaller.core.formatBytes

private val ListPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)

/** Scheda di una riga di elenco; se [onClick] è null non è toccabile. */
@Composable
private fun ListCard(onClick: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val base = Modifier.fillMaxWidth()
    Card(
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content
        )
    }
}

@Composable
private fun NoResults(query: String) {
    EmptyState("Nessun risultato per «$query».")
}

// --------------------------------------------------------------------------------------------- recenti

@Composable
fun RecentScreen(state: UiState, vm: MainViewModel) {
    val filtered = remember(state.recents, state.query) {
        state.recents.filter { TextSearch.matches(state.query, it.name) }
    }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = "File recenti",
            onBack = { vm.back() },
            actions = {
                if (state.recents.isNotEmpty()) {
                    TextButton(onClick = { vm.askClearRecents() }) { Text("Svuota") }
                }
            }
        )
        if (state.recents.isEmpty()) {
            EmptyState(
                "Nessun file recente.\n\nI file che scegli compaiono qui, così puoi riaprirli senza cercarli di nuovo " +
                    "nella memoria del telefono."
            )
        } else {
            SearchField(state.query, { vm.setQuery(it) }, "Cerca tra i file recenti")
            if (filtered.isEmpty()) {
                NoResults(state.query)
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = ListPadding,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.uri }) { recent ->
                        ListCard(onClick = { vm.openRecent(recent) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = recent.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = formatBytes(recent.sizeBytes) + " · aperto il " + Format.dateTime(recent.openedAt),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { vm.removeRecent(recent.uri) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Togli dai file recenti")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------------------------------- installati

@Composable
fun InstalledScreen(state: UiState, vm: MainViewModel) {
    val filtered = remember(state.installed, state.query) {
        state.installed.filter {
            TextSearch.matches(
                state.query,
                it.title, it.contentKind.label, it.source, it.author, it.fileName, Format.installState(it.state)
            )
        }
    }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Mod installati", onBack = { vm.back() })
        if (state.installed.isEmpty()) {
            EmptyState(
                "Nessun contenuto ancora.\n\nQui compaiono i contenuti che hai inviato a Minecraft con questa app, " +
                    "con lo stato che hai confermato tu."
            )
        } else {
            Text(
                text = "Tocca un contenuto per inviarlo di nuovo, segnare se Minecraft l'ha importato o eliminarlo dall'app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            SearchField(state.query, { vm.setQuery(it) }, "Cerca per nome, tipo o autore")
            if (filtered.isEmpty()) {
                NoResults(state.query)
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = ListPadding,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        ListCard(onClick = { vm.showEntryActions(entry) }) {
                            Text(
                                text = Format.clean(entry.title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            val version = entry.version
                            val subtitle = entry.contentKind.label +
                                (if (version.isNullOrBlank()) "" else " · v$version") +
                                " · " + formatBytes(entry.sizeBytes)
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val author = entry.author
                            if (!author.isNullOrBlank()) {
                                Text(
                                    text = "Autore: " + Format.clean(author),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            ToneRow(
                                tone = Format.installTone(entry.state),
                                title = Format.installState(entry.state),
                                detail = "Da «${entry.source}» · inviato il " + Format.dateTime(entry.sentAt)
                            )
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------------------------------- cronologia

@Composable
fun HistoryScreen(state: UiState, vm: MainViewModel) {
    val filtered = remember(state.history, state.query) {
        state.history.filter { TextSearch.matches(state.query, it.title, it.action, it.result, it.detail) }
    }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = "Cronologia",
            onBack = { vm.back() },
            actions = {
                if (state.history.isNotEmpty()) {
                    TextButton(onClick = { vm.askClearHistory() }) { Text("Svuota") }
                }
            }
        )
        if (state.history.isEmpty()) {
            EmptyState("La cronologia è vuota.\n\nOgni analisi, invio a Minecraft ed eliminazione viene registrata qui.")
        } else {
            SearchField(state.query, { vm.setQuery(it) }, "Cerca nella cronologia")
            if (filtered.isEmpty()) {
                NoResults(state.query)
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = ListPadding,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered) { entry ->
                        ListCard(onClick = null) {
                            Text(
                                text = Format.clean(entry.title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = entry.action,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(text = entry.result, style = MaterialTheme.typography.bodySmall)
                            val detail = entry.detail
                            if (!detail.isNullOrBlank()) {
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = Format.dateTime(entry.at),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------------------------------- backup

@Composable
fun BackupsScreen(state: UiState, vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Backup", onBack = { vm.back() })
        if (state.backups.isEmpty()) {
            EmptyState(
                "Nessun backup.\n\nPrima di eliminare un contenuto dall'app ne salvo una copia qui, " +
                    "così puoi ripristinarla in seguito."
            )
        } else {
            Text(
                text = "Prima di eliminare un contenuto salvo una copia. «Ripristina» la riapre nel rapporto di analisi, " +
                    "da dove puoi inviarla di nuovo a Minecraft. Tengo al massimo le ultime 15 copie.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = ListPadding,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.backups, key = { it.id }) { backup ->
                    ListCard(onClick = null) {
                        Text(
                            text = Format.clean(backup.title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = formatBytes(backup.sizeBytes) + " · " + Format.dateTime(backup.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(text = backup.reason, style = MaterialTheme.typography.bodySmall)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { vm.restoreBackup(backup) },
                                enabled = state.busy == null,
                                modifier = Modifier.weight(1f)
                            ) { Text("Ripristina") }
                            OutlinedButton(
                                onClick = { vm.askDeleteBackup(backup) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Elimina") }
                        }
                    }
                }
            }
        }
    }
}
