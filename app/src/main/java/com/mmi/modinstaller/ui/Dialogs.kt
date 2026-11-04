package com.mmi.modinstaller.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mmi.modinstaller.DeliveryInfo
import com.mmi.modinstaller.DialogState
import com.mmi.modinstaller.MainViewModel
import com.mmi.modinstaller.UiState
import com.mmi.modinstaller.core.InstallState
import com.mmi.modinstaller.core.InstalledEntry

/** Finestra con testo e pulsanti incolonnati a tutta larghezza (più leggibile su telefono dei pulsanti affiancati). */
@Composable
private fun StackedDialog(
    title: String,
    onDismiss: () -> Unit,
    cancelable: Boolean,
    body: @Composable ColumnScope.() -> Unit,
    buttons: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = cancelable, dismissOnClickOutside = cancelable)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                body()
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    buttons()
                }
            }
        }
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun GreenDialogButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = InstallGreen, contentColor = Color.White)
    ) { Text(text) }
}

@Composable
private fun DangerDialogButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        )
    ) { Text(text) }
}

@Composable
private fun PlainDialogButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
}

@Composable
private fun CloseDialogButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
}

/** Tutte le finestre dell'app. La domanda sull'esito dell'importazione ha la precedenza su tutte le altre. */
@Composable
fun AppDialogs(state: UiState, vm: MainViewModel) {
    val awaiting = state.awaiting
    val dialog = state.dialog
    if (state.confirmVisible && awaiting != null) {
        ConfirmImportDialog(awaiting, state.delivery, state.queueRemaining, vm)
    } else if (dialog != null) {
        when (dialog) {
            is DialogState.MinecraftMissing -> MinecraftMissingDialog(vm)
            is DialogState.EntryActions -> EntryActionsDialog(dialog.entry, vm)
            is DialogState.ConfirmDelete -> ConfirmDeleteDialog(dialog.entry, vm)
            is DialogState.ConfirmDeleteBackup -> StackedDialog(
                title = "Eliminare il backup?",
                onDismiss = { vm.dismissDialog() },
                cancelable = true,
                body = {
                    BodyText("«" + Format.clean(dialog.backup.title) + "» (" + Format.dateTime(dialog.backup.createdAt) + ")")
                    BodyText("La copia di sicurezza viene eliminata e non potrai più ripristinarla.")
                },
                buttons = {
                    DangerDialogButton("Elimina il backup") { vm.deleteBackup(dialog.backup.id) }
                    CloseDialogButton("Annulla") { vm.dismissDialog() }
                }
            )
            is DialogState.ConfirmClearHistory -> StackedDialog(
                title = "Svuotare la cronologia?",
                onDismiss = { vm.dismissDialog() },
                cancelable = true,
                body = {
                    BodyText("Vengono cancellate solo le righe della cronologia. I contenuti gestiti dall'app restano dove sono.")
                },
                buttons = {
                    DangerDialogButton("Svuota la cronologia") { vm.clearHistory() }
                    CloseDialogButton("Annulla") { vm.dismissDialog() }
                }
            )
            is DialogState.ConfirmClearRecents -> StackedDialog(
                title = "Svuotare i file recenti?",
                onDismiss = { vm.dismissDialog() },
                cancelable = true,
                body = {
                    BodyText(
                        "L'app dimentica i file recenti e rinuncia all'accesso che aveva ottenuto. " +
                            "I file originali sul telefono non vengono toccati."
                    )
                },
                buttons = {
                    DangerDialogButton("Svuota i file recenti") { vm.clearRecents() }
                    CloseDialogButton("Annulla") { vm.dismissDialog() }
                }
            )
        }
    }
}

@Composable
private fun ConfirmImportDialog(entry: InstalledEntry, info: DeliveryInfo?, remaining: Int, vm: MainViewModel) {
    StackedDialog(
        title = "Minecraft ha importato il contenuto?",
        // Si risponde sempre: ignorare la domanda lascerebbe lo stato incerto senza che l'utente lo scelga.
        onDismiss = {},
        cancelable = false,
        body = {
            Text(
                text = "«" + Format.clean(entry.title) + "»",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            val names = info?.packNames.orEmpty().map { Format.clean(it) }.filter { it.isNotEmpty() }
            if (names.isNotEmpty()) {
                BodyText(
                    if (names.size == 1) "Ho consegnato a Minecraft questo pacchetto:"
                    else "Ho consegnato a Minecraft questi ${names.size} pacchetti in un solo file:"
                )
                for (name in names.take(MAX_LISTED_PACKS)) BodyText("• $name")
                if (names.size > MAX_LISTED_PACKS) BodyText("…e altri ${names.size - MAX_LISTED_PACKS}")
            }
            if (info != null && info.sourceFiles > 0) {
                BodyText(
                    if (info.includedFiles == info.sourceFiles) {
                        "File inclusi: ${info.includedFiles}, nessuno escluso."
                    } else {
                        "File inclusi: ${info.includedFiles} su ${info.sourceFiles}; i " +
                            "${info.sourceFiles - info.includedFiles} esclusi sono eseguibili o doppioni."
                    }
                )
            }
            BodyText(
                "Minecraft non comunica all'app se l'importazione è riuscita, quindi devi dirmelo tu in base a ciò che " +
                    "hai visto nel gioco. Controlla che compaiano TUTTI i pacchetti elencati. Se l'importazione è andata " +
                    "a buon fine rispondi Sì; se è comparso un errore (o non è successo niente) rispondi No."
            )
            BodyText(
                "Importare non basta: nelle impostazioni del mondo (matita accanto al mondo) devi ATTIVARE sia i pacchetti " +
                    "delle risorse sia quelli del comportamento. Se ne attivi solo uno mancano gli oggetti o le texture."
            )
            if (remaining > 0) {
                BodyText(
                    "Dopo la tua risposta invierò il contenuto successivo (" +
                        Format.plural(remaining, "rimasto", "rimasti") + ")."
                )
            }
        },
        buttons = {
            GreenDialogButton("Sì, Minecraft l'ha importato") { vm.answerConfirmation(InstallState.CONFIRMED) }
            PlainDialogButton("No, ha dato errore") { vm.answerConfirmation(InstallState.FAILED) }
            CloseDialogButton("Non lo so · decido più tardi") { vm.answerConfirmation(null) }
            CloseDialogButton("Riapri Minecraft") { vm.openMinecraft() }
            if (remaining > 0) CloseDialogButton("Non inviare gli altri") { vm.dropRemaining() }
        }
    )
}

private const val MAX_LISTED_PACKS = 10

@Composable
private fun MinecraftMissingDialog(vm: MainViewModel) {
    StackedDialog(
        title = "Minecraft non è installato",
        onDismiss = { vm.dismissDialog() },
        cancelable = true,
        body = {
            BodyText(
                "Non trovo Minecraft (Bedrock) su questo telefono, quindi non posso consegnargli il file. " +
                    "Installalo dal Play Store e riprova: l'analisi dei file funziona anche senza."
            )
        },
        buttons = {
            GreenDialogButton("Apri il Play Store") { vm.openStore() }
            CloseDialogButton("Chiudi") { vm.dismissDialog() }
        }
    )
}

@Composable
private fun EntryActionsDialog(entry: InstalledEntry, vm: MainViewModel) {
    StackedDialog(
        title = Format.clean(entry.title),
        onDismiss = { vm.dismissDialog() },
        cancelable = true,
        body = {
            ToneRow(
                tone = Format.installTone(entry.state),
                title = Format.installState(entry.state),
                detail = "Inviato il " + Format.dateTime(entry.sentAt)
            )
            if (entry.packs.isNotEmpty()) {
                BodyText("Contiene " + Format.plural(entry.packs.size, "pacchetto", "pacchetti") + ":")
                for (pack in entry.packs.take(MAX_LISTED_PACKS)) {
                    BodyText("• " + Format.clean(pack.name) + " (v" + pack.versionText + ")")
                }
                if (entry.packs.size > MAX_LISTED_PACKS) BodyText("…e altri ${entry.packs.size - MAX_LISTED_PACKS}")
            }
            BodyText(
                "Minecraft non dice all'app se ha importato il contenuto: lo stato lo decidi tu, in base a ciò che " +
                    "vedi nel gioco."
            )
        },
        buttons = {
            GreenDialogButton("Invia di nuovo a Minecraft") { vm.resend(entry.id) }
            if (entry.state != InstallState.CONFIRMED) {
                PlainDialogButton("Segna come importato") { vm.markEntry(entry.id, InstallState.CONFIRMED) }
            }
            if (entry.state != InstallState.FAILED) {
                PlainDialogButton("Segna come NON importato") { vm.markEntry(entry.id, InstallState.FAILED) }
            }
            PlainDialogButton("Elimina dall'app") { vm.askDelete(entry) }
            CloseDialogButton("Chiudi") { vm.dismissDialog() }
        }
    )
}

@Composable
private fun ConfirmDeleteDialog(entry: InstalledEntry, vm: MainViewModel) {
    StackedDialog(
        title = "Eliminare dall'app?",
        onDismiss = { vm.dismissDialog() },
        cancelable = true,
        body = {
            BodyText("«" + Format.clean(entry.title) + "»")
            BodyText(
                "Elimino il pacchetto preparato che l'app conserva e lo tolgo dall'elenco. Prima ne salvo una copia " +
                    "di sicurezza e, subito dopo, potrai annullare."
            )
            BodyText(
                "Se Minecraft l'ha già importato, il contenuto resta dentro il gioco: l'app non può toglierlo da lì. " +
                    "Per rimuoverlo usa le impostazioni di Minecraft."
            )
        },
        buttons = {
            DangerDialogButton("Elimina") { vm.deleteEntry(entry.id) }
            CloseDialogButton("Annulla") { vm.dismissDialog() }
        }
    )
}
