package com.mmi.modinstaller.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mmi.modinstaller.InstalledWorld
import com.mmi.modinstaller.InterruptedImport
import com.mmi.modinstaller.MainViewModel
import com.mmi.modinstaller.PendingWorlds
import com.mmi.modinstaller.PickPurpose
import com.mmi.modinstaller.Section
import com.mmi.modinstaller.WorldOutcome
import com.mmi.modinstaller.WorldsDialog
import com.mmi.modinstaller.WorldsState
import com.mmi.modinstaller.WorldsViewModel
import com.mmi.modinstaller.core.formatBytes
import com.mmi.modinstaller.worlds.WorldEdition
import com.mmi.modinstaller.worlds.WorldInfo
import com.mmi.modinstaller.worlds.WorldNoteLevel

/**
 * WORLD MANAGER: mondi Java e Bedrock. Legge l'archivio senza estrarlo e senza modificarlo; un mondo Java va nella cartella
 * `saves` di un profilo del MOD MANAGER (un file alla volta, con ripresa), un mondo Bedrock all'INSTALLER BEDROCK. Non converte
 * i mondi tra le due edizioni e non apre il gioco.
 */
@Composable
fun WorldsScreen(state: WorldsState, model: WorldsViewModel, vm: MainViewModel, onMenu: () -> Unit, onOpenMods: () -> Unit) {
    // «*/*»: molti gestori di file non associano un tipo ai .mcworld. Un mondo alla volta: si usa il primo file scelto.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val first = uris.firstOrNull()
        if (first != null) model.openFile(first)
    }
    var toExport by remember { mutableStateOf<InstalledWorld?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val w = toExport
        if (uri != null && w != null) model.export(w, uri)
        toExport = null
    }

    SectionPage(title = Section.WORLDS.title, onBack = { vm.back() }, onMenu = onMenu) {
        InfoCard {
            Text(text = "Mondi Java e Bedrock", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Bullets(
                listOf(
                    "Scegli un mondo (.zip o .mcworld): l'app legge l'elenco dei file e level.dat, senza estrarre nulla e senza modificare il tuo file.",
                    "Un mondo Java va nella cartella saves di un profilo del MOD MANAGER, un file alla volta: anche un mondo enorme non passa per la memoria. Se l'app si chiude a metà, RIPRENDI continua da dove era arrivata.",
                    "Un mondo Bedrock si importa in Minecraft con l'INSTALLER BEDROCK, come sempre."
                )
            )
        }

        val message = state.message
        if (message != null) {
            InfoCard {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { model.clearMessage() }) { Text("OK") }
            }
        }

        GreenButton(
            text = "SCEGLI UN MONDO (.zip, .mcworld)",
            onClick = { picker.launch(arrayOf("*/*")) },
            enabled = state.busy == null,
            height = 56.dp
        )

        val pending = state.pending
        if (pending != null) {
            SectionLabel("Mondo scelto")
            PendingCard(pending, state, model, vm, onOpenMods)
        }

        val outcome = state.outcome
        if (outcome != null) {
            SectionLabel("Esito dell'importazione")
            OutcomeCard(outcome, model)
        }

        if (state.interrupted.isNotEmpty()) {
            SectionLabel("Importazioni interrotte")
            for (item in state.interrupted) InterruptedCard(item, model)
        }

        SectionLabel("Mondi nei profili")
        if (!state.loaded) {
            Text(text = "Lettura dei mondi…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (state.installed.isEmpty()) {
            Text(
                text = if (state.profiles.isEmpty()) "Nessun profilo Java: i mondi Java si importano in un profilo del MOD MANAGER."
                else "Nessun mondo nei profili.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            for (w in state.installed) {
                InstalledCard(w, model) {
                    toExport = w
                    exporter.launch(w.dir.name + ".zip")
                }
            }
        }

        SectionLabel("Cosa fa e cosa no")
        InfoCard {
            Bullets(
                listOf(
                    "Non converte i mondi tra Java e Bedrock: la conversione (WORLD CONVERTER) non c'è ancora.",
                    "Non apre il gioco e non ripara i mondi danneggiati: dice cosa c'è nell'archivio e lo copia così com'è, controllando ogni file.",
                    "Aprire un mondo con una versione di Minecraft più vecchia di quella con cui è stato salvato può rovinarlo: l'app te lo segnala prima di importarlo.",
                    "Per usare un mondo Java esportalo in uno ZIP (o esporta il profilo dal MOD MANAGER, che include i mondi) e mettilo nella cartella saves del tuo programma di avvio."
                )
            )
        }
    }

    WorldsDialogs(state, model)
}

// ---------------------------------------------------------------------------------------------------- mondo scelto

@Composable
private fun PendingCard(p: PendingWorlds, state: WorldsState, model: WorldsViewModel, vm: MainViewModel, onOpenMods: () -> Unit) {
    val world = p.world
    InfoCard {
        Text(text = p.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (p.worlds.size > 1) {
            Text(text = "Nell'archivio ci sono ${p.worlds.size} mondi: scegli quale.", style = MaterialTheme.typography.bodyMedium)
            p.worlds.forEachIndexed { i, w ->
                FullButton((if (i == p.selected) "● " else "") + w.title + " (" + w.edition.label + ")") { model.selectWorld(i) }
            }
        }
        WorldDetails(world)

        if (world.edition == WorldEdition.BEDROCK) {
            GreenButton(
                text = "IMPORTA IN MINECRAFT (INSTALLER BEDROCK)",
                onClick = { model.openInBedrockInstaller { uris -> vm.openFiles(uris, PickPurpose.MAP) } },
                enabled = world.importable,
                height = 52.dp
            )
        } else {
            val profile = state.chosenProfile
            if (profile == null) {
                ToneRow(Tone.WARN, "Per importare un mondo Java serve un profilo: crealo nel MOD MANAGER.")
                FullButton("APRI IL MOD MANAGER", onClick = onOpenMods)
            } else {
                StatRow("Profilo", profile.name)
                Text(
                    text = profile.loader.label + (profile.loaderVersion?.let { " $it" } ?: "") + " · Minecraft " + profile.minecraft,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.profiles.size > 1) FullButton("CAMBIA PROFILO") { model.askProfile() }
                val warning = model.versionWarning(world, profile)
                if (warning != null) ToneRow(Tone.WARN, warning)
                var name by remember(p.file.path, p.selected) { mutableStateOf(p.folderName) }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        model.setFolderName(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Nome della cartella in saves") }
                )
                GreenButton(
                    text = "IMPORTA NEL PROFILO",
                    onClick = { model.importSelected() },
                    enabled = world.importable && p.folderName.isNotBlank(),
                    height = 52.dp
                )
            }
        }
        FullButton("SCARTA") { model.discardPending() }
    }
}

/** Le informazioni lette dall'archivio o dalla cartella: nome, edizione, versione, dimensioni, avvisi. */
@Composable
private fun WorldDetails(w: WorldInfo) {
    Text(text = w.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text(
        text = listOfNotNull(w.edition.label, w.version?.let { "Minecraft $it" }, w.gameModeLabel).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    StatRow("File", w.files.toString() + " · " + formatBytes(w.bytes))
    if (w.edition == WorldEdition.JAVA) {
        for (d in w.dimensions) StatRow(d.label, d.regionFiles.toString() + (if (d.regionFiles == 1) " regione" else " regioni"))
        if (w.players > 0) StatRow("Giocatori salvati", w.players.toString())
        if (w.datapacks.isNotEmpty()) StatRow("Data pack", w.datapacks.size.toString())
    }
    for (n in w.notes) {
        val tone = when (n.level) {
            WorldNoteLevel.ERROR -> Tone.BAD
            WorldNoteLevel.WARNING -> Tone.WARN
            WorldNoteLevel.INFO -> Tone.INFO
        }
        ToneRow(tone, n.text)
    }
}

// ---------------------------------------------------------------------------------------------------- esito, interrotte, installati

@Composable
private fun OutcomeCard(o: WorldOutcome, model: WorldsViewModel) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = o.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (o.ok) Tag("Importato", toneColor(Tone.OK)) else Tag("Non finito", toneColor(Tone.WARN))
        }
        ToneRow(if (o.ok) Tone.OK else Tone.WARN, o.text)
        FullButton("CHIUDI") { model.clearOutcome() }
    }
}

@Composable
private fun InterruptedCard(item: InterruptedImport, model: WorldsViewModel) {
    val p = item.pending
    InfoCard {
        Text(text = p.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            text = "Profilo «" + item.profileName + "» · " + p.copiedFiles + " file già copiati (" + formatBytes(p.copiedBytes) + ")",
            style = MaterialTheme.typography.bodySmall
        )
        if (!item.archiveAvailable) {
            ToneRow(Tone.WARN, "La copia dell'archivio non c'è più: annulla questa importazione e scegli di nuovo il file.")
        }
        ButtonsRow {
            RowButton("RIPRENDI", enabled = item.archiveAvailable) { model.resume(item) }
            RowButton("ANNULLA") { model.askDiscard(item) }
        }
    }
}

@Composable
private fun InstalledCard(w: InstalledWorld, model: WorldsViewModel, onExport: () -> Unit) {
    InfoCard {
        WorldDetailsShort(w)
        ButtonsRow {
            RowButton("ESPORTA (ZIP)", onClick = onExport)
            RowButton("ELIMINA") { model.askDelete(w) }
        }
    }
}

@Composable
private fun WorldDetailsShort(w: InstalledWorld) {
    val info = w.info
    Text(text = info.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text(
        text = listOfNotNull(info.version?.let { "Minecraft $it" }, info.gameModeLabel, formatBytes(info.bytes)).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(text = "Profilo «" + w.profileName + "» · saves/" + w.dir.name, style = MaterialTheme.typography.bodySmall)
    val regions = info.regionFiles
    Text(
        text = regions.toString() + (if (regions == 1) " regione" else " regioni") + " in " + info.dimensions.count { it.regionFiles > 0 } + " dimensioni",
        style = MaterialTheme.typography.bodySmall
    )
}

// ---------------------------------------------------------------------------------------------------- finestre

@Composable
private fun WorldsDialogs(state: WorldsState, model: WorldsViewModel) {
    when (val d = state.dialog) {
        is WorldsDialog.ChooseProfile -> StackedDialog(
            title = "In quale profilo?",
            onDismiss = { model.dismissDialog() },
            cancelable = true,
            body = { BodyText("Il mondo va nella cartella saves del profilo scelto.") },
            buttons = {
                for (p in state.profiles) {
                    val mark = if (p.id == state.pending?.profileId) "● " else ""
                    PlainDialogButton(mark + p.title) { model.chooseProfile(p.id) }
                }
                CloseDialogButton("CHIUDI") { model.dismissDialog() }
            }
        )
        is WorldsDialog.DeleteWorld -> StackedDialog(
            title = "Eliminare il mondo?",
            onDismiss = { model.dismissDialog() },
            cancelable = true,
            body = {
                BodyText(
                    "«" + d.world.info.title + "» viene cancellato dal profilo «" + d.world.profileName + "» (" + formatBytes(d.world.info.bytes) +
                        "). Questa operazione non si può annullare: se ti serve, esportalo prima."
                )
            },
            buttons = {
                DangerDialogButton("ELIMINA IL MONDO") { model.deleteWorld(d.world) }
                CloseDialogButton("ANNULLA") { model.dismissDialog() }
            }
        )
        is WorldsDialog.DiscardInterrupted -> StackedDialog(
            title = "Annullare l'importazione?",
            onDismiss = { model.dismissDialog() },
            cancelable = true,
            body = { BodyText("I file già copiati di «" + d.item.pending.title + "» vengono cancellati. Il tuo file originale non cambia.") },
            buttons = {
                DangerDialogButton("ANNULLA L'IMPORTAZIONE") { model.discardInterrupted(d.item) }
                CloseDialogButton("CHIUDI") { model.dismissDialog() }
            }
        )
        null -> {}
    }
}

// ---------------------------------------------------------------------------------------------------- pulsanti

@Composable
private fun ButtonsRow(content: @Composable RowScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun RowScope.RowButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f)) { Text(text) }
}

@Composable
private fun FullButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(text) }
}
