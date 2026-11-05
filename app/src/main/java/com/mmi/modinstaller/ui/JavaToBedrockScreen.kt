package com.mmi.modinstaller.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mmi.modinstaller.ConvertDialog
import com.mmi.modinstaller.ConvertOutcome
import com.mmi.modinstaller.ConvertState
import com.mmi.modinstaller.ConvertViewModel
import com.mmi.modinstaller.MainViewModel
import com.mmi.modinstaller.PendingConversion
import com.mmi.modinstaller.PickPurpose
import com.mmi.modinstaller.SavedAddon
import com.mmi.modinstaller.Section
import com.mmi.modinstaller.convert.ModReport
import com.mmi.modinstaller.core.formatBytes

/**
 * JAVA TO BEDROCK: porta in un add-on Bedrock il contenuto delle mod Java (oggetti, blocchi, texture, nomi, ricette, bottini,
 * minerali, armature, creature GeckoLib). Il comportamento scritto in codice Java non si converte e il rapporto lo dice.
 */
@Composable
fun JavaToBedrockScreen(state: ConvertState, model: ConvertViewModel, vm: MainViewModel, minecraftVersion: String?, onMenu: () -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.openFiles(uris)
    }
    var toSave by remember { mutableStateOf<SavedAddon?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val a = toSave
        if (uri != null && a != null) model.saveCopy(a, uri)
        toSave = null
    }
    val install: (SavedAddon) -> Unit = { a -> model.install(a) { uris -> vm.openFiles(uris, PickPurpose.ADDON) } }
    val save: (SavedAddon) -> Unit = { a ->
        toSave = a
        saver.launch(a.name + ".mcaddon")
    }

    SectionPage(title = Section.JAVA_TO_BEDROCK.title, onBack = { vm.back() }, onMenu = onMenu) {
        InfoCard {
            Text(text = "Mod Java in Minecraft Bedrock", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Bullets(
                listOf(
                    "Scegli una o più mod (.jar), oppure tutte le mod di un profilo del MOD MANAGER: l'app le legge senza eseguirle.",
                    "Diventa un add-on Bedrock tutto il contenuto: oggetti, blocchi, texture, nomi (anche in italiano), ricette, cosa lasciano i blocchi, minerali nel mondo, armi, attrezzi, armature, cibo e le creature con modello GeckoLib.",
                    "Bedrock non esegue codice Java: macchine, menu, nuove meccaniche e l'intelligenza dei mob non passano. Il rapporto te lo dice mod per mod."
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
            text = "SCEGLI MOD JAVA (.jar)",
            onClick = { picker.launch(arrayOf("*/*")) },
            enabled = state.busy == null,
            height = 56.dp
        )
        FullButton("CONVERTI LE MOD DI UN PROFILO", enabled = state.busy == null) { model.askProfile() }

        val outcome = state.outcome
        if (outcome != null) {
            SectionLabel("Add-on creato")
            OutcomeCard(outcome, model, install, save, versionWarning(minecraftVersion)) { shareText(context, outcome.report) }
        }

        val pending = state.pending
        if (pending != null) {
            SectionLabel("Mod lette")
            PendingCard(pending, model) { shareText(context, com.mmi.modinstaller.convert.ConversionText.of(pending.reports, pending.addonName)) }
            for (r in pending.reports) ModCard(r)
        }

        SectionLabel("Add-on creati")
        if (state.addons.isEmpty()) {
            Text(
                text = "Nessun add-on creato finora.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            for (a in state.addons) AddonCard(a, model, install, save)
        }

        SectionLabel("Cosa fa e cosa no")
        InfoCard {
            Bullets(
                listOf(
                    "Le forme dei blocchi, le texture (anche trasparenti o animate) e i nomi restano quelli della mod; le ricette usano i nomi Bedrock degli oggetti di Minecraft.",
                    "Danni e durata di armi e attrezzi, protezione delle armature, durezza dei blocchi e valori del cibo in Java stanno nel codice: l'app li stima dal materiale e lo scrive nel rapporto.",
                    "Le creature hanno l'aspetto e le animazioni della mod ma un comportamento semplice (camminano, volano o nuotano, scappano se colpite); si fanno nascere con l'uovo.",
                    "Le ricette delle macchine della mod, i menu, i suoni, le dimensioni e le strutture non si convertono.",
                    "L'app controlla l'add-on con gli schemi ufficiali di Minecraft Bedrock prima di ogni versione, ma non può aprire il gioco: provalo in un mondo di prova prima di usarlo in quello vero."
                )
            )
        }
    }

    ConvertDialogs(state, model)
}

@Composable
private fun PendingCard(p: PendingConversion, model: ConvertViewModel, onShare: () -> Unit) {
    val plan = p.plan
    InfoCard {
        Text(text = "Da: " + p.from, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val withContent = p.reports.count { it.contentCount > 0 }
        Text(
            text = p.reports.size.toString() + (if (p.reports.size == 1) " mod letta" else " mod lette") + ", " +
                withContent + " con contenuto da portare in Bedrock",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        StatRow("Oggetti", plan.itemCount.toString())
        StatRow("Blocchi", plan.blockCount.toString())
        StatRow("Creature", plan.entityCount.toString())
        StatRow("Ricette", plan.recipeCount.toString())
        if (plan.oreCount > 0) StatRow("Minerali nel mondo", plan.oreCount.toString())
        if (plan.hasContent) {
            var name by remember(p.plan) { mutableStateOf(p.addonName) }
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    model.setAddonName(it)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Nome dell'add-on") }
            )
            GreenButton(text = "CREA L'ADD-ON BEDROCK", onClick = { model.convert() }, enabled = p.addonName.isNotBlank(), height = 52.dp)
        } else {
            ToneRow(Tone.WARN, "In queste mod non c'è niente che Bedrock possa usare: sono mod di solo codice (o librerie).")
        }
        ButtonsRow {
            RowButton("CONDIVIDI IL RAPPORTO", onClick = onShare)
            RowButton("SCARTA") { model.discardPending() }
        }
    }
}

@Composable
private fun ModCard(r: ModReport) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = r.title + (r.version?.let { " $it" } ?: ""),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            when {
                r.error != null -> Tag("Non letta", toneColor(Tone.BAD))
                r.contentCount == 0 -> Tag("Solo codice", toneColor(Tone.WARN))
                else -> Tag("Convertibile in parte", toneColor(Tone.OK))
            }
        }
        Text(
            text = listOfNotNull(r.loader, r.label.takeIf { it != r.title }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = r.verdict, style = MaterialTheme.typography.bodyMedium)
        if (r.error != null) return@InfoCard
        if (r.items.isNotEmpty()) ToneRow(Tone.OK, "Oggetti: " + r.items.size, sample(r.items.map { it.name + " (" + it.detail + ")" }))
        if (r.blocks.isNotEmpty()) ToneRow(Tone.OK, "Blocchi: " + r.blocks.size, sample(r.blocks.map { it.name }))
        if (r.entities.isNotEmpty()) ToneRow(Tone.OK, "Creature: " + r.entities.size, sample(r.entities.map { it.name + " (" + it.detail + ")" }))
        if (r.recipesTotal > 0) {
            ToneRow(
                if (r.recipesConverted == r.recipesTotal) Tone.OK else Tone.WARN,
                "Ricette: " + r.recipesConverted + " su " + r.recipesTotal,
                r.recipeSkips.entries.sortedByDescending { it.value }.take(4).joinToString("\n") { "• " + it.value + ": " + it.key }.ifEmpty { null }
            )
        }
        if (r.lootTables > 0) ToneRow(Tone.OK, "Cosa lasciano i blocchi: " + r.lootTables)
        if (r.ores > 0) ToneRow(Tone.OK, "Minerali generati nel mondo: " + r.ores)
        if (r.armorModels > 0) ToneRow(Tone.OK, "Armature con il disegno sul corpo: " + r.armorModels)
        for (s in r.simplified) ToneRow(Tone.INFO, s)
        for (s in r.estimates) ToneRow(Tone.INFO, s)
        for (s in r.notConverted) ToneRow(Tone.BAD, "Non convertito: $s")
    }
}

/** L'add-on usa il formato di Bedrock 1.21.80: con un Minecraft più vecchio non si carica bene. */
internal fun versionWarning(installed: String?): String? {
    val parts = installed?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return null
    if (parts.size < 3) return null
    val need = com.mmi.modinstaller.convert.ModConverter.MIN_ENGINE.map { it.toInt() }
    for (i in 0..2) {
        if (parts[i] > need[i]) return null
        if (parts[i] < need[i]) {
            return "Il tuo Minecraft è la " + parts.take(3).joinToString(".") + ": per questo add-on serve la " +
                need.joinToString(".") + " o successiva. Aggiorna Minecraft prima di usarlo."
        }
    }
    return null
}

private fun sample(names: List<String>): String =
    names.take(6).joinToString(", ") + if (names.size > 6) " e altri " + (names.size - 6) else ""

@Composable
private fun OutcomeCard(
    o: ConvertOutcome,
    model: ConvertViewModel,
    install: (SavedAddon) -> Unit,
    save: (SavedAddon) -> Unit,
    warning: String?,
    onShare: () -> Unit
) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = o.addon.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Tag("Creato", toneColor(Tone.OK))
        }
        ToneRow(
            Tone.OK,
            "Add-on Bedrock pronto (" + formatBytes(o.addon.sizeBytes) + ")",
            o.items.toString() + " oggetti, " + o.blocks + " blocchi, " + o.entities + " creature, " + o.recipes + " ricette" +
                (if (o.ores > 0) ", " + o.ores + " minerali" else "") + "."
        )
        Text(
            text = "Con INSTALLA IN MINECRAFT l'INSTALLER BEDROCK lo controlla e lo consegna al gioco. Poi attivalo nelle impostazioni del mondo (pacchetti di comportamento e di risorse).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (warning != null) ToneRow(Tone.WARN, warning)
        GreenButton(text = "INSTALLA IN MINECRAFT", onClick = { install(o.addon) }, height = 52.dp)
        ButtonsRow {
            RowButton("SALVA UNA COPIA") { save(o.addon) }
            RowButton("RAPPORTO", onClick = onShare)
        }
        FullButton("CHIUDI") { model.clearOutcome() }
    }
}

@Composable
private fun AddonCard(a: SavedAddon, model: ConvertViewModel, install: (SavedAddon) -> Unit, save: (SavedAddon) -> Unit) {
    InfoCard {
        Text(text = a.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            text = formatBytes(a.sizeBytes) + " · " + Format.dateTime(a.createdAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ButtonsRow {
            RowButton("INSTALLA") { install(a) }
            RowButton("SALVA") { save(a) }
            RowButton("ELIMINA") { model.askDelete(a) }
        }
    }
}

@Composable
private fun ConvertDialogs(state: ConvertState, model: ConvertViewModel) {
    when (val d = state.dialog) {
        is ConvertDialog.ChooseProfile -> StackedDialog(
            title = "Quale profilo?",
            onDismiss = { model.dismissDialog() },
            cancelable = true,
            body = { BodyText("Si convertono tutte le mod attive del profilo, insieme: le ricette tra una mod e l'altra restano collegate.") },
            buttons = {
                for (p in state.profiles) PlainDialogButton(p.title) { model.convertProfile(p.id) }
                CloseDialogButton("CHIUDI") { model.dismissDialog() }
            }
        )
        is ConvertDialog.DeleteAddon -> StackedDialog(
            title = "Eliminare l'add-on?",
            onDismiss = { model.dismissDialog() },
            cancelable = true,
            body = { BodyText("«" + d.addon.name + "» viene cancellato dall'app. Se l'hai già installato, in Minecraft resta.") },
            buttons = {
                DangerDialogButton("ELIMINA") { model.delete(d.addon) }
                CloseDialogButton("ANNULLA") { model.dismissDialog() }
            }
        )
        null -> {}
    }
}

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

private fun shareText(context: Context, text: String) {
    try {
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_SUBJECT, "Rapporto JAVA TO BEDROCK")
        send.putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Condividi il rapporto"))
    } catch (_: Exception) {
    }
}
