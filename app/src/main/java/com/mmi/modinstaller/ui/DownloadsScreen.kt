package com.mmi.modinstaller.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mmi.modinstaller.DownloadsState
import com.mmi.modinstaller.DownloadsViewModel
import com.mmi.modinstaller.MainViewModel
import com.mmi.modinstaller.ModpackViewModel
import com.mmi.modinstaller.ModsViewModel
import com.mmi.modinstaller.PickPurpose
import com.mmi.modinstaller.Section
import com.mmi.modinstaller.core.formatBytes
import com.mmi.modinstaller.download.DownloadRecord
import com.mmi.modinstaller.download.DownloadState

/** DOWNLOAD MANAGER: scarica un file da un sito consentito, con ripresa e verifica dell'impronta. */
@Composable
fun DownloadsScreen(
    dl: DownloadsState,
    model: DownloadsViewModel,
    vm: MainViewModel,
    mods: ModsViewModel,
    modpacks: ModpackViewModel,
    onMenu: () -> Unit
) {
    var toDelete by remember { mutableStateOf<DownloadRecord?>(null) }
    var toSave by remember { mutableStateOf<DownloadRecord?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val record = toSave
        if (uri != null && record != null) model.saveCopy(record, uri)
        toSave = null
    }

    SectionPage(title = Section.DOWNLOADS.title, onBack = { vm.back() }, onMenu = onMenu) {
        InfoCard {
            Text(
                text = "Scarica un file da Internet nella memoria dell'app.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Bullets(
                listOf(
                    "Solo indirizzi https:// di siti consentiti; per un sito nuovo ti viene chiesto il permesso.",
                    "Un download interrotto riprende da dove era arrivato; l'impronta (SHA) può verificare che il file sia quello giusto.",
                    "L'app fa solo richieste per ricevere il file: non invia nulla del tuo telefono.",
                    "Scarica finché l'app resta in memoria: se Android la chiude, tocca RIPRENDI."
                )
            )
        }

        // I campi tengono il testo per conto loro (il cursore non salta); si svuotano quando un indirizzo viene accettato.
        key(dl.resetCount) {
            var url by remember { mutableStateOf("") }
            var hash by remember { mutableStateOf("") }
            InfoCard {
                Text("Nuovo scaricamento", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        model.clearInputError()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Indirizzo del file (https://…)") }
                )
                OutlinedTextField(
                    value = hash,
                    onValueChange = {
                        hash = it
                        model.clearInputError()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Impronta SHA-1/256/512 (facoltativa)") }
                )
                val error = dl.inputError
                if (error != null) {
                    Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                GreenButton(
                    text = "AGGIUNGI ALLA CODA",
                    onClick = { model.add(url, hash) },
                    enabled = url.isNotBlank(),
                    height = 52.dp
                )
            }
        }

        val message = dl.message
        if (message != null) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }

        SectionLabel("Download")
        if (dl.items.isEmpty()) {
            Text(
                text = "Nessun download. Incolla qui sopra l'indirizzo di un file e tocca AGGIUNGI ALLA CODA.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        for (item in dl.items) {
            DownloadCard(
                record = item,
                model = model,
                onOpenInstaller = {
                    val uri = model.contentUri(item)
                    if (uri != null) vm.openFiles(listOf(uri), PickPurpose.ANY)
                },
                onOpenMods = {
                    val uri = model.contentUri(item)
                    if (uri != null) {
                        vm.openSection(Section.MODS)
                        mods.addFiles(listOf(uri), "download")
                    }
                },
                onOpenModpack = {
                    val uri = model.contentUri(item)
                    if (uri != null) {
                        vm.openSection(Section.MODPACKS)
                        modpacks.openFile(uri)
                    }
                },
                onSave = {
                    toSave = item
                    saver.launch(item.fileName)
                },
                onDelete = { toDelete = item }
            )
        }

        SectionLabel("Siti consentiti")
        InfoCard {
            Text(
                text = DownloadsViewModel.defaultHosts.joinToString(", "),
                style = MaterialTheme.typography.bodySmall
            )
            if (dl.approvedHosts.isNotEmpty()) {
                Text(
                    text = "Aggiunti da te: " + dl.approvedHosts.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    val consent = dl.consent
    if (consent != null) {
        StackedDialog(
            title = "Consentire i download da «" + consent.host + "»?",
            onDismiss = { model.dismissConsent() },
            cancelable = true,
            body = {
                BodyText("L'app scaricherà il file da questo sito e nient'altro. Non invia nulla del tuo telefono: fa solo richieste per ricevere il file.")
                BodyText("Consenti solo i siti di cui ti fidi.")
            },
            buttons = {
                GreenDialogButton("CONSENTI E CONTINUA") { model.confirmConsent() }
                CloseDialogButton("ANNULLA") { model.dismissConsent() }
            }
        )
    }
    val deleting = toDelete
    if (deleting != null) {
        StackedDialog(
            title = "Eliminare il file?",
            onDismiss = { toDelete = null },
            cancelable = true,
            body = { BodyText("«" + deleting.fileName + "» viene tolto dall'elenco e cancellato dalla memoria dell'app.") },
            buttons = {
                DangerDialogButton("ELIMINA") {
                    model.remove(deleting)
                    toDelete = null
                }
                CloseDialogButton("ANNULLA") { toDelete = null }
            }
        )
    }
}

@Composable
private fun DownloadCard(
    record: DownloadRecord,
    model: DownloadsViewModel,
    onOpenInstaller: () -> Unit,
    onOpenMods: () -> Unit,
    onOpenModpack: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = record.fileName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Tag(record.state.label, stateColor(record.state))
        }
        val host = hostOf(record.url)
        if (host != null) {
            Text(text = host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        when (record.state) {
            DownloadState.COMPLETED -> {
                Text(text = formatBytes(record.totalBytes), style = MaterialTheme.typography.bodyMedium)
                val sha = record.sha256
                if (sha != null) {
                    Text(
                        text = "SHA-256: " + sha.take(16) + "…" + sha.takeLast(8),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val expected = record.expectedHash
                if (expected != null) {
                    ToneRow(Tone.OK, "Impronta " + expected.algorithm.label + " verificata: il file è quello atteso")
                } else {
                    Text(
                        text = "Impronta calcolata ma non confrontata (non ne avevi indicata una).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                val fraction = record.fraction
                if (record.state == DownloadState.RUNNING || record.doneBytes > 0) {
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } else if (record.state == DownloadState.RUNNING) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    val total = if (record.totalBytes > 0) " di " + formatBytes(record.totalBytes) else ""
                    val percent = if (fraction != null) " (" + (fraction * 100).toInt() + "%)" else ""
                    Text(text = formatBytes(record.doneBytes) + total + percent, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        val error = record.error
        if (error != null && record.state != DownloadState.COMPLETED) {
            Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        when (record.state) {
            DownloadState.QUEUED, DownloadState.RUNNING -> ButtonsRow {
                RowButton("PAUSA") { model.pause(record.id) }
                RowButton("ANNULLA") { model.cancel(record.id) }
            }
            DownloadState.PAUSED -> {
                val pending = record.pendingHost
                if (pending != null) {
                    FullButton("CONSENTI «" + pending + "» E RIPRENDI") { model.askConsentFor(record) }
                    FullButton("ANNULLA") { model.cancel(record.id) }
                } else {
                    ButtonsRow {
                        RowButton("RIPRENDI") { model.resume(record.id) }
                        RowButton("ANNULLA") { model.cancel(record.id) }
                    }
                }
            }
            DownloadState.FAILED, DownloadState.CANCELED -> ButtonsRow {
                RowButton("RIPROVA") { model.resume(record.id) }
                RowButton("ELIMINA", onClick = onDelete)
            }
            DownloadState.COMPLETED -> {
                val bedrock = DownloadsViewModel.isBedrockCandidate(record.fileName)
                val java = DownloadsViewModel.isJavaCandidate(record.fileName)
                if (bedrock && java) {
                    // Un .zip può essere un pacchetto Bedrock oppure un resource pack, uno shader o una raccolta di mod Java.
                    Text(
                        text = "Un file ZIP può essere per Bedrock o per Java: scegli dove aprirlo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FullButton("APRI NELL'INSTALLER BEDROCK", onOpenInstaller)
                    FullButton("AGGIUNGI AL MOD MANAGER (JAVA)", onOpenMods)
                } else if (bedrock) {
                    GreenButton(text = "APRI NELL'INSTALLER BEDROCK", onClick = onOpenInstaller, height = 52.dp)
                } else if (java) {
                    GreenButton(text = "AGGIUNGI AL MOD MANAGER", onClick = onOpenMods, height = 52.dp)
                } else if (DownloadsViewModel.isModpack(record.fileName)) {
                    GreenButton(text = "APRI NEL MODPACK MANAGER", onClick = onOpenModpack, height = 52.dp)
                } else {
                    Text(
                        text = "Tipo di file non riconosciuto dall'app. Puoi salvarne una copia.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ButtonsRow {
                    RowButton("SALVA UNA COPIA", onClick = onSave)
                    RowButton("ELIMINA", onClick = onDelete)
                }
            }
        }
    }
}

@Composable
private fun ButtonsRow(content: @Composable RowScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun RowScope.RowButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f)) { Text(text) }
}

@Composable
private fun FullButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
}

@Composable
private fun stateColor(state: DownloadState): Color = when (state) {
    DownloadState.COMPLETED -> toneColor(Tone.OK)
    DownloadState.RUNNING, DownloadState.QUEUED -> MaterialTheme.colorScheme.primary
    DownloadState.PAUSED -> toneColor(Tone.WARN)
    DownloadState.FAILED -> toneColor(Tone.BAD)
    DownloadState.CANCELED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun hostOf(url: String): String? = try {
    java.net.URI(url).host
} catch (e: Exception) {
    null
}
