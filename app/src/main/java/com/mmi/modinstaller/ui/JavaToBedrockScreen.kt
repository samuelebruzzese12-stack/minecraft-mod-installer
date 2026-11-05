package com.mmi.modinstaller.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
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
import com.mmi.modinstaller.ConvertTarget
import com.mmi.modinstaller.ConvertViewModel
import com.mmi.modinstaller.MainViewModel
import com.mmi.modinstaller.PendingConversion
import com.mmi.modinstaller.PickPurpose
import com.mmi.modinstaller.SavedAddon
import com.mmi.modinstaller.Section
import com.mmi.modinstaller.convert.Component
import com.mmi.modinstaller.convert.ComponentStatus
import com.mmi.modinstaller.convert.ModReport
import com.mmi.modinstaller.core.formatBytes

/** Le quattro strade di JAVA TO BEDROCK (BLOCKBRIDGE). */
private enum class J2BMode(val title: String) {
    BEDROCK("Conversione Bedrock"), ANALYSIS("Analisi avanzata"), MODPACK("Modpack combinato"), JAVA("Esecuzione Java")
}

/**
 * JAVA TO BEDROCK (BLOCKBRIDGE): legge le mod Java senza eseguirle e ricostruisce in un add-on Bedrock tutto quello che Bedrock
 * permette (contenuto, valori letti dal codice, blocchi con più stati, comportamenti con la Script API, creature con modelli
 * e animazioni). Il registro dei componenti dice voce per voce cosa è passato, come, e cosa no.
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
    val registrySaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.saveRegistry(uri)
    }
    val install: (SavedAddon) -> Unit = { a -> model.install(a) { uris -> vm.openFiles(uris, PickPurpose.ADDON) } }
    val save: (SavedAddon) -> Unit = { a ->
        toSave = a
        saver.launch(a.name + ".mcaddon")
    }

    var mode by remember { mutableStateOf(J2BMode.BEDROCK) }
    SectionPage(title = Section.JAVA_TO_BEDROCK.title, onBack = { vm.back() }, onMenu = onMenu) {
        ModeSelector(mode) { mode = it }
        TargetCard(state.target)

        when (mode) {
            J2BMode.BEDROCK -> InfoCard {
                Text(text = "Mod Java → add-on Bedrock", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Bullets(
                    listOf(
                        "Scegli una o più mod (.jar): l'app le legge senza eseguirle, anche il codice (per prendere i valori veri).",
                        "Passano oggetti, blocchi, texture, nomi in tutte le lingue, ricette, bottini, minerali nel mondo, armi, attrezzi, armature, cibo e creature (modelli GeckoLib, Citadel e scritti nel codice, con le loro animazioni).",
                        "Si ricostruiscono con Bedrock e la Script API: porte, botole e cancelli che si aprono, lastre doppie, scale, staccionate e muretti che si collegano, pulsanti e pedane con la pietrarossa, colture che crescono, effetti dei cibi, usura degli attrezzi, esperienza dei minerali.",
                        "Macchine, menu e meccaniche nuove scritte in codice non passano: il registro dei componenti le elenca una per una."
                    )
                )
            }
            J2BMode.ANALYSIS -> InfoCard {
                Text(text = "Analisi e ricostruzione avanzata", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Bullets(
                    listOf(
                        "Per ogni mod: il registro dei componenti, cioè ogni oggetto, blocco, creatura, gruppo di ricette e ogni file del JAR, con cosa faceva in Java, cosa diventa in Bedrock e le differenze.",
                        "Stati: Convertito (stesso significato), Ricostruito (con strumenti di Bedrock o Script API), Approssimato (funziona con differenze scritte), Dati conservati (non ancora ricostruito), Non ricostruibile (Bedrock non lo permette).",
                        "Le prove nel gioco vero (server Bedrock ufficiale con un giocatore simulato) si fanno a ogni versione dell'app sulle mod di prova; sul telefono l'app fa i controlli statici dei file e lo dice."
                    )
                )
            }
            J2BMode.MODPACK -> InfoCard {
                Text(text = "Modpack combinato", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Bullets(
                    listOf(
                        "Converte insieme tutte le mod attive di un profilo del MOD MANAGER (anche un modpack .mrpack installato con il MODPACK MANAGER) in un solo add-on.",
                        "Le ricette e i gruppi tra una mod e l'altra restano collegati; le librerie senza contenuto si riconoscono e si saltano.",
                        "Ogni mod ha il suo rapporto e il suo registro dei componenti."
                    )
                )
            }
            J2BMode.JAVA -> InfoCard {
                Text(text = "Esecuzione Java", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Bullets(
                    listOf(
                        "Convertire non è eseguire: un add-on Bedrock non contiene il codice Java delle mod.",
                        "Per giocare con le mod Java così come sono serve Minecraft Java Edition: su Android con un launcher Java per Android (non incluso in questa app).",
                        "Questa app prepara tutto per il launcher: nel MOD MANAGER crei un profilo con le mod (controllate, con le dipendenze) e lo esporti nella cartella di gioco del launcher; in JAVA EDITION vedi se il telefono ha memoria e versione di Java adatte."
                    )
                )
                ButtonsRow {
                    RowButton("MOD MANAGER") { vm.openSection(Section.MODS) }
                    RowButton("JAVA EDITION") { vm.openSection(Section.JAVA) }
                }
            }
        }

        val message = state.message
        if (message != null) {
            InfoCard {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { model.clearMessage() }) { Text("OK") }
            }
        }

        if (mode != J2BMode.JAVA) {
            if (mode != J2BMode.MODPACK) {
                GreenButton(
                    text = "SCEGLI MOD JAVA (.jar)",
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = state.busy == null,
                    height = 56.dp
                )
            }
            if (mode == J2BMode.MODPACK) {
                GreenButton(text = "SCEGLI UN PROFILO DEL MOD MANAGER", onClick = { model.askProfile() }, enabled = state.busy == null, height = 56.dp)
            } else {
                FullButton("CONVERTI LE MOD DI UN PROFILO", enabled = state.busy == null) { model.askProfile() }
            }
        }

        val outcome = state.outcome
        if (outcome != null) {
            SectionLabel("Add-on creato")
            OutcomeCard(outcome, model, install, save, versionWarning(minecraftVersion, outcome.minEngine)) { shareText(context, outcome.report) }
        }

        val pending = state.pending
        if (pending != null) {
            SectionLabel("Mod lette")
            PendingCard(pending, model) { shareText(context, com.mmi.modinstaller.convert.ConversionText.of(pending.reports, pending.addonName)) }
            if (mode == J2BMode.ANALYSIS) {
                FullButton("SALVA IL REGISTRO DEI COMPONENTI (JSON)", enabled = state.busy == null) {
                    registrySaver.launch(pending.addonName.ifBlank { "registro" } + " - registro componenti.json")
                }
            }
            for (r in pending.reports) ModCard(r, detailed = mode == J2BMode.ANALYSIS)
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
                    "Forme, stati e texture dei blocchi (anche trasparenti o animate) e i nomi restano quelli della mod; le ricette usano i nomi Bedrock degli oggetti di Minecraft.",
                    "Danni, durata, protezione, durezza, cibo ed effetti si leggono dal codice della mod quando si può; quando no, si stimano e il rapporto lo dice.",
                    "Le creature hanno modello e animazioni della mod e un comportamento tradotto dai loro obiettivi Java negli obiettivi di Bedrock più vicini; le azioni speciali scritte in codice no.",
                    "Macchine, menu, suoni, dimensioni, strutture e progressi non si convertono ancora: restano nel registro come «dati conservati».",
                    "Ogni versione dell'app prova gli add-on delle mod di prova nel server Bedrock ufficiale (blocchi piazzati, porte aperte, creature vive, effetti dei cibi…). Sul telefono l'app non apre il gioco: prova l'add-on in un mondo di prova prima di usarlo in quello vero."
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
private fun ModeSelector(mode: J2BMode, onPick: (J2BMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in J2BMode.values().toList().chunked(2)) {
            ButtonsRow {
                for (m in row) {
                    if (m == mode) Button(onClick = { onPick(m) }, modifier = Modifier.weight(1f)) { Text(m.title) }
                    else OutlinedButton(onClick = { onPick(m) }, modifier = Modifier.weight(1f)) { Text(m.title) }
                }
            }
        }
    }
}

/** Per quale Minecraft si converte e cosa cambia. */
@Composable
private fun TargetCard(t: ConvertTarget?) {
    if (t == null) return
    InfoCard {
        StatRow("Minecraft Bedrock", t.installed?.let { "$it (installato)" } ?: "non trovato")
        when {
            t.installed == null -> ToneRow(Tone.INFO, "Converto per l'ultima versione di Minecraft Bedrock (1.26.40 o più nuova)", "Con un Minecraft più vecchio l'add-on non si carica: aggiorna il gioco.")
            t.statefulBlocks -> ToneRow(Tone.OK, "Si usano gli strumenti di Bedrock 1.26.40+", "Porte a due parti, scale con gli angoli, staccionate e muretti che si collegano, pulsanti e pedane con la pietrarossa.")
            else -> ToneRow(Tone.WARN, "Minecraft più vecchio della 1.26.40", "Porte, lastre, scale e staccionate restano con un solo aspetto. Aggiorna Minecraft e riconverti per averle come in Java.")
        }
    }
}

@Composable
private fun ModCard(r: ModReport, detailed: Boolean = false) {
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
        r.codeSummary?.let { ToneRow(Tone.INFO, "Codice letto (senza eseguirlo)", it) }
        for (s in r.scripted) ToneRow(Tone.OK, "Ricostruito: $s")
        for (s in r.fromCode) ToneRow(Tone.OK, "Dal codice: $s")
        for (s in r.simplified) ToneRow(Tone.INFO, s)
        for (s in r.estimates) ToneRow(Tone.INFO, s)
        for (s in r.notConverted) ToneRow(Tone.BAD, "Non convertito: $s")
        if (r.components.isNotEmpty()) {
            val by = r.components.groupBy { it.status }.mapValues { (_, l) -> l.sumOf { it.count } }
            ToneRow(
                Tone.INFO, "Registro dei componenti",
                ComponentStatus.values().filter { by[it] != null }.joinToString(" · ") { it.label + " " + by[it] }
            )
            if (detailed) ComponentList(r.components)
        }
    }
}

/** Il registro dei componenti di una mod, con il filtro per stato (BLOCKBRIDGE: niente sparisce senza essere scritto). */
@Composable
private fun ComponentList(all: List<Component>) {
    var filter by remember(all) { mutableStateOf<ComponentStatus?>(null) }
    var limit by remember(all, filter) { mutableStateOf(40) }
    val present = ComponentStatus.values().filter { s -> all.any { it.status == s } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in (listOf<ComponentStatus?>(null) + present).chunked(3)) {
            ButtonsRow {
                for (s in row) {
                    val label = s?.label ?: "Tutti"
                    if (s == filter) Button(onClick = { filter = s }, modifier = Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelSmall) }
                    else OutlinedButton(onClick = { filter = s }, modifier = Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        val shown = all.filter { filter == null || it.status == filter }
        for (c in shown.take(limit)) {
            val tone = when (c.status) {
                ComponentStatus.CONVERTED, ComponentStatus.RECONSTRUCTED -> Tone.OK
                ComponentStatus.APPROXIMATED -> Tone.INFO
                ComponentStatus.DATA_KEPT -> Tone.WARN
                ComponentStatus.NOT_RECONSTRUCTABLE -> Tone.BAD
            }
            val title = c.kind.replaceFirstChar { it.uppercase() } + ": " + c.originalId + (if (c.count > 1) " (" + c.count + ")" else "") + " — " + c.status.label
            val detail = listOfNotNull(
                "Java: " + c.originalFunction,
                "Conversione: " + c.conversion,
                c.bedrockId?.let { "Bedrock: $it — " + c.bedrockFunction },
                c.tests.takeIf { it.isNotEmpty() }?.let { "Prove: " + it.joinToString("; ") },
                c.differences.takeIf { it.isNotEmpty() }?.let { "Differenze: " + it.joinToString("; ") }
            ).joinToString("\n")
            ToneRow(tone, title, detail)
        }
        if (shown.size > limit) FullButton("MOSTRA ALTRI " + minOf(40, shown.size - limit) + " (DI " + (shown.size - limit) + ")") { limit += 40 }
    }
}

/** L'add-on chiede una versione minima di Bedrock (1.21.80, 1.21.90 con la Script API, 1.26.40 con i blocchi con più stati). */
internal fun versionWarning(installed: String?, required: List<Int> = emptyList()): String? {
    val parts = installed?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return null
    if (parts.size < 3) return null
    val need = required.takeIf { it.size >= 3 } ?: com.mmi.modinstaller.convert.ModConverter.MIN_ENGINE.map { it.toInt() }
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
