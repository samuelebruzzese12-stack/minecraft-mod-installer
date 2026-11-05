package com.mmi.modinstaller.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mmi.modinstaller.CheckView
import com.mmi.modinstaller.MainViewModel
import com.mmi.modinstaller.ModsDialog
import com.mmi.modinstaller.ModsState
import com.mmi.modinstaller.ModsViewModel
import com.mmi.modinstaller.PendingFile
import com.mmi.modinstaller.Section
import com.mmi.modinstaller.core.formatBytes
import com.mmi.modinstaller.jar.DependencyKind
import com.mmi.modinstaller.jar.JarReport
import com.mmi.modinstaller.jar.ModLoader
import com.mmi.modinstaller.mods.IssueLevel
import com.mmi.modinstaller.mods.ItemCategory
import com.mmi.modinstaller.mods.PlanDecision
import com.mmi.modinstaller.mods.PlanIssue
import com.mmi.modinstaller.mods.Profile
import com.mmi.modinstaller.mods.ProfileDraft
import com.mmi.modinstaller.mods.ProfileItem

/**
 * MOD MANAGER: mod, resource pack, shader e data pack di Minecraft Java Edition in profili indipendenti.
 *
 * Quello che mostra è quello che l'app sa davvero: i file vengono letti senza eseguirli e confrontati con il profilo
 * (caricatore, versione di Minecraft, dipendenze). Non avvia il gioco. Da «CERCA ONLINE» si scarica da Modrinth (non da CurseForge).
 */
@Composable
fun ModsScreen(mods: ModsState, model: ModsViewModel, vm: MainViewModel, onMenu: () -> Unit, onOpenCatalog: () -> Unit) {
    // «*/*»: molti gestori di file non associano un tipo ai .jar e ai .mrpack.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.addFiles(uris, "file")
    }
    val profile = mods.active
    // Lo ZIP con i file attivi del profilo: il nome del file viene proposto dal selettore di sistema.
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) model.exportZip(uri)
    }

    SectionPage(title = Section.MODS.title, onBack = { vm.back() }, onMenu = onMenu) {
        InfoCard {
            Text(
                text = "Mod Java in profili indipendenti",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Bullets(
                listOf(
                    "Aggiungi file .jar o raccolte .zip: l'app li legge senza eseguirli e dice se vanno bene per il profilo.",
                    "Ogni profilo ha le sue cartelle: quello che metti in uno non tocca gli altri.",
                    "Ogni modifica si può annullare. I file restano nella memoria dell'app."
                )
            )
        }

        val message = mods.message
        if (message != null) {
            InfoCard {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { model.clearMessage() }) { Text("OK") }
            }
        }

        SectionLabel("Profilo")
        if (!mods.loaded) {
            Text(
                text = "Lettura dei profili…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (profile == null) {
            NoProfileCard(model)
        } else {
            ProfileCard(mods, profile, model) {
                val safe = profile.name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "profilo" }
                exporter.launch("$safe.zip")
            }
        }

        GreenButton(
            text = "AGGIUNGI FILE (.jar, .zip)",
            onClick = { picker.launch(arrayOf("*/*")) },
            height = 56.dp
        )
        FullButton("CERCA ONLINE (MODRINTH)", onClick = onOpenCatalog)

        PendingSection(mods, model)

        if (profile != null) ProfileFilesSection(mods, model)

        val check = mods.check
        if (check != null) CheckSection(check, model)

        SectionLabel("Cosa fa e cosa no")
        InfoCard {
            Bullets(
                listOf(
                    "Legge l'elenco dei file e le descrizioni dentro la mod (fabric.mod.json, mods.toml…) e l'intestazione " +
                        "delle classi. Non esegue mai il codice della mod.",
                    "Le compatibilità si deducono da quello che la mod dichiara di sé: può essere incompleto o sbagliato. " +
                        "Un controllo superato non garantisce che il gioco parta.",
                    "Non avvia Minecraft Java. I file restano nei profili di questa app: per usarli altrove esportali in uno " +
                        "ZIP (solo i file attivi) e estrailo nella cartella di gioco del tuo programma di avvio.",
                    "Modrinth è collegato in «CERCA ONLINE». CurseForge no (serve una chiave personale): per i suoi file usa " +
                        "la memoria o il DOWNLOAD MANAGER."
                )
            )
        }
    }

    ModsDialogs(mods, model)
}

// ---------------------------------------------------------------------------------------------------- profilo

@Composable
private fun NoProfileCard(model: ModsViewModel) {
    InfoCard {
        Text(text = "Nessun profilo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            text = "Un profilo è una raccolta indipendente di mod per una versione di Minecraft e un caricatore " +
                "(Fabric, Quilt, Forge o NeoForge). Creane uno per iniziare: se prima aggiungi un file, l'app ti propone " +
                "i valori che legge al suo interno.",
            style = MaterialTheme.typography.bodyMedium
        )
        GreenButton(text = "CREA IL PRIMO PROFILO", onClick = { model.openProfileForm(null) }, height = 52.dp)
    }
}

@Composable
private fun ProfileCard(mods: ModsState, profile: Profile, model: ModsViewModel, onExport: () -> Unit) {
    val disabled = mods.items.count { !it.enabled }
    InfoCard {
        Text(text = profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            text = profile.loader.label + (profile.loaderVersion?.let { " $it" } ?: "") + " · Minecraft " + profile.minecraft,
            style = MaterialTheme.typography.bodyMedium
        )
        val java = profile.effectiveJava
        StatRow("Java richiesto dal gioco", if (java != null) "Java $java" else "non determinabile")
        StatRow("File nel profilo", countsText(mods.items) + if (disabled > 0) " · $disabled disattivati" else "")
        StatRow("Spazio usato", formatBytes(mods.sizeBytes))

        ButtonsRow {
            RowButton("CAMBIA", enabled = mods.profiles.size > 1) { model.openChooseProfile() }
            RowButton("NUOVO") { model.openProfileForm(null) }
        }
        ButtonsRow {
            RowButton("MODIFICA") { model.openProfileEdit() }
            RowButton("ELIMINA") { model.askDeleteProfile() }
        }
        FullButton("VERIFICA PROFILO") { model.checkProfile() }
        FullButton("ESPORTA I FILE ATTIVI (ZIP)", enabled = mods.items.any { it.enabled }, onClick = onExport)
        val last = mods.lastOperation
        FullButton(
            text = if (last != null) "ANNULLA: $last" else "NESSUNA OPERAZIONE DA ANNULLARE",
            enabled = last != null
        ) { model.undo() }
    }
}

private fun countsText(items: List<ProfileItem>): String {
    if (items.isEmpty()) return "nessuno"
    val parts = ArrayList<String>()
    for (c in ItemCategory.values()) {
        val n = items.count { it.category == c }
        if (n > 0) parts.add("$n " + c.label.lowercase())
    }
    return parts.joinToString(" · ")
}

// ---------------------------------------------------------------------------------------------------- file da installare

@Composable
private fun PendingSection(mods: ModsState, model: ModsViewModel) {
    val pending = mods.pending
    if (pending.isEmpty()) return
    val ready = pending.count { it.outcome == null && it.plan?.decision == PlanDecision.READY }
    val finished = pending.count { it.outcome?.ok == true || it.plan?.decision == PlanDecision.ALREADY_INSTALLED }

    SectionLabel("Da installare (" + pending.size + ")")
    if (ready > 1) {
        GreenButton(text = "INSTALLA I $ready FILE PRONTI", onClick = { model.installAllReady() }, height = 52.dp)
    }
    for (p in pending) PendingCard(p, mods, model)
    ButtonsRow {
        if (finished > 0) RowButton("TOGLI I FINITI") { model.clearFinished() }
        RowButton("SVUOTA L'ELENCO") { model.clearAllPending() }
    }
}

@Composable
private fun PendingCard(p: PendingFile, mods: ModsState, model: ModsViewModel) {
    val plan = p.plan
    val outcome = p.outcome
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = p.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            val tag = pendingTag(p)
            Tag(tag.first, tag.second)
        }
        val report = p.report
        if (report != null) {
            Text(
                text = report.kind.label + if (p.sizeBytes > 0) " · " + formatBytes(p.sizeBytes) else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for (line in summaryLines(report)) Text(text = line, style = MaterialTheme.typography.bodySmall)
        }
        val error = p.error
        if (error != null) ToneRow(Tone.BAD, error)

        if (outcome != null) {
            ToneRow(if (outcome.ok) Tone.OK else Tone.BAD, outcome.text)
        } else if (plan != null) {
            for (issue in plan.issues.sortedByDescending { it.level.ordinal }) IssueRow(issue)
            if (plan.missing.isNotEmpty()) {
                Text(
                    text = "Mancano nel profilo",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Bullets(
                    plan.missing.map { m ->
                        "«" + m.id + "»" + (m.range?.let { " ($it)" } ?: "") + ", richiesta da «" + m.requiredBy + "»"
                    }
                )
                Text(
                    text = "Aggiungi anche quei file (da «CERCA ONLINE» si scaricano insieme alla mod).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (p.report != null) {
            Text(
                text = "Per installarlo serve un profilo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (outcome != null) {
            if (!outcome.ok) {
                ButtonsRow {
                    RowButton("RIPROVA") { model.retry(p.key) }
                    RowButton("TOGLI") { model.dismissPending(p.key) }
                }
            } else {
                FullButton("TOGLI DALL'ELENCO") { model.dismissPending(p.key) }
            }
        } else if (plan == null) {
            if (p.report != null) {
                GreenButton(text = "CREA UN PROFILO", onClick = { model.openProfileForm(p.key) }, height = 48.dp)
            }
            FullButton("TOGLI DALL'ELENCO") { model.dismissPending(p.key) }
        } else {
            when (plan.decision) {
                PlanDecision.READY ->
                    GreenButton(text = "INSTALLA", onClick = { model.install(p.key, false) }, height = 52.dp)
                PlanDecision.NEEDS_CONFIRMATION ->
                    FullButton("INSTALLA COMUNQUE…") { model.askConfirm(p.key) }
                PlanDecision.BLOCKED -> {
                    if (ModsViewModel.suggestsOtherProfile(plan)) {
                        FullButton("CREA UN PROFILO PER QUESTO FILE") { model.openProfileForm(p.key) }
                    }
                }
                PlanDecision.ALREADY_INSTALLED -> {}
            }
            FullButton("TOGLI DALL'ELENCO") { model.dismissPending(p.key) }
        }
    }
}

@Composable
private fun pendingTag(p: PendingFile): Pair<String, Color> {
    val outcome = p.outcome
    if (outcome != null) {
        return if (outcome.ok) "Installato" to toneColor(Tone.OK) else "Non installato" to toneColor(Tone.BAD)
    }
    if (p.error != null) return "Non letto" to toneColor(Tone.BAD)
    val plan = p.plan ?: return "Nessun profilo" to toneColor(Tone.WARN)
    return when (plan.decision) {
        PlanDecision.READY -> "Pronto" to toneColor(Tone.OK)
        PlanDecision.NEEDS_CONFIRMATION -> "Serve conferma" to toneColor(Tone.WARN)
        PlanDecision.BLOCKED -> "Non installabile" to toneColor(Tone.BAD)
        PlanDecision.ALREADY_INSTALLED -> "Già installato" to toneColor(Tone.INFO)
    }
}

/** Le righe di riepilogo di un file: cosa contiene, per quale caricatore e per quale Minecraft. */
private fun summaryLines(report: JarReport): List<String> {
    val lines = ArrayList<String>()
    val mod = report.mods.firstOrNull()
    if (mod != null) {
        val loaders = report.mods.map { it.loader.label }.distinct().joinToString(" / ")
        lines.add(mod.displayName + (mod.version?.let { " $it" } ?: "") + " · " + loaders)
        val minecraft = mod.dependencies.firstOrNull { it.id == "minecraft" }
        if (minecraft != null) lines.add("Minecraft: " + minecraft.constraint.describe())
        if (report.mods.size > 1) lines.add("Il file contiene " + report.mods.size + " descrizioni di mod.")
    }
    val pack = report.pack
    if (pack != null) {
        val description = pack.description?.let { Format.clean(it) }
        if (!description.isNullOrBlank()) lines.add(description.take(120))
        if (pack.knownMinecraft != null) lines.add("Pensato per Minecraft " + pack.knownMinecraft)
    }
    val modpack = report.modpack
    if (modpack != null) {
        lines.add(
            listOfNotNull(modpack.name, modpack.version).joinToString(" ") + " · " + modpack.fileCount + " file da scaricare"
        )
    }
    return lines
}

private fun levelTone(level: IssueLevel): Tone = when (level) {
    IssueLevel.BLOCK -> Tone.BAD
    IssueLevel.CONFIRM, IssueLevel.WARNING -> Tone.WARN
    IssueLevel.INFO -> Tone.INFO
}

@Composable
private fun IssueRow(issue: PlanIssue, prefix: String? = null) {
    val text = if (prefix != null && !issue.message.contains(prefix)) "«$prefix»: " + issue.message else issue.message
    ToneRow(levelTone(issue.level), text, issue.detail)
}

// ---------------------------------------------------------------------------------------------------- file del profilo

@Composable
private fun ProfileFilesSection(mods: ModsState, model: ModsViewModel) {
    SectionLabel("Nel profilo")
    for (row in ItemCategory.values().toList().chunked(2)) {
        ButtonsRow {
            for (c in row) {
                val label = c.label + " (" + mods.items.count { it.category == c } + ")"
                if (c == mods.tab) {
                    FilledTonalButton(onClick = { model.selectTab(c) }, modifier = Modifier.weight(1f)) { Text(label) }
                } else {
                    OutlinedButton(onClick = { model.selectTab(c) }, modifier = Modifier.weight(1f)) { Text(label) }
                }
            }
        }
    }
    val shown = mods.items.filter { it.category == mods.tab }.sortedBy { it.displayName.lowercase() }
    if (shown.isEmpty()) {
        Text(
            text = "Nessun file di tipo «" + mods.tab.label + "» in questo profilo.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    for (item in shown) ItemCard(item, model)
}

@Composable
private fun ItemCard(item: ProfileItem, model: ModsViewModel) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(text = item.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = item.fileName + " · " + formatBytes(item.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = item.enabled, onCheckedChange = { model.setEnabled(item, it) })
        }
        if (!item.enabled) {
            Text(
                text = "Disattivato: il gioco non lo carica.",
                style = MaterialTheme.typography.bodySmall,
                color = toneColor(Tone.WARN)
            )
        }
        ButtonsRow {
            RowButton("DETTAGLI") { model.showDetails(item) }
            RowButton("RIMUOVI") { model.askRemove(item) }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- verifica del profilo

@Composable
private fun CheckSection(view: CheckView, model: ModsViewModel) {
    val check = view.check
    SectionLabel("Verifica del profilo")
    InfoCard {
        Text(
            text = "Controllati " + check.checkedItems + " file attivi",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        if (check.issues.isEmpty()) {
            ToneRow(
                Tone.OK, "Nessun problema trovato",
                "Il controllo si basa su ciò che i file dichiarano di sé: non garantisce che il gioco parta."
            )
        }
        for (issue in check.issues.sortedByDescending { it.level.ordinal }) {
            IssueRow(issue, issue.itemId?.let { view.names[it] })
        }
        for (note in view.notes) {
            Text(text = note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { model.closeCheck() }) { Text("CHIUDI") }
    }
}

// ---------------------------------------------------------------------------------------------------- finestre

@Composable
private fun ModsDialogs(mods: ModsState, model: ModsViewModel) {
    val dialog = mods.dialog ?: return
    when (dialog) {
        is ModsDialog.ProfileForm -> ProfileFormDialog(dialog, model)
        is ModsDialog.ChooseProfile -> StackedDialog(
            title = "Scegli il profilo",
            onDismiss = { model.dismissDialog() },
            cancelable = true,
            body = { BodyText("Il profilo scelto è quello in cui vengono installati i file.") },
            buttons = {
                for (p in mods.profiles) {
                    val mark = if (p.id == mods.activeId) "● " else ""
                    PlainDialogButton(mark + p.title) { model.selectProfile(p.id) }
                }
                CloseDialogButton("CHIUDI") { model.dismissDialog() }
            }
        )
        is ModsDialog.DeleteProfile -> StackedDialog(
            title = "Eliminare il profilo?",
            onDismiss = { model.dismissDialog() },
            cancelable = true,
            body = {
                BodyText(
                    "«" + dialog.profile.name + "» viene cancellato con tutti i suoi file (" + dialog.itemCount +
                        "). Questa operazione non si può annullare."
                )
            },
            buttons = {
                DangerDialogButton("ELIMINA PROFILO") { model.deleteProfile(dialog.profile) }
                CloseDialogButton("ANNULLA") { model.dismissDialog() }
            }
        )
        is ModsDialog.RemoveItem -> StackedDialog(
            title = "Rimuovere dal profilo?",
            onDismiss = { model.dismissDialog() },
            cancelable = true,
            body = {
                BodyText(
                    "«" + dialog.item.displayName + "» viene messo da parte. Finché non fai un'altra modifica puoi " +
                        "rimetterlo con ANNULLA."
                )
            },
            buttons = {
                DangerDialogButton("RIMUOVI") { model.remove(dialog.item) }
                CloseDialogButton("ANNULLA") { model.dismissDialog() }
            }
        )
        is ModsDialog.ConfirmInstall -> {
            val p = mods.pending.firstOrNull { it.key == dialog.key }
            val plan = p?.plan
            if (p != null && plan != null) {
                StackedDialog(
                    title = "Installare comunque?",
                    onDismiss = { model.dismissDialog() },
                    cancelable = true,
                    body = {
                        BodyText("«" + p.label + "» probabilmente non funziona in questo profilo:")
                        for (issue in plan.confirmReasons) IssueRow(issue)
                        if (plan.missing.isNotEmpty()) {
                            BodyText("Mancano: " + plan.missing.joinToString(", ") { "«" + it.id + "»" })
                        }
                        BodyText("Se lo installi puoi annullare subito dopo.")
                    },
                    buttons = {
                        DangerDialogButton("INSTALLA COMUNQUE") { model.install(p.key, true) }
                        CloseDialogButton("ANNULLA") { model.dismissDialog() }
                    }
                )
            }
        }
        is ModsDialog.Details -> {
            val item = mods.items.firstOrNull { it.id == dialog.itemId }
            if (item != null) DetailsDialog(item, model)
        }
    }
}

@Composable
private fun ProfileFormDialog(form: ModsDialog.ProfileForm, model: ModsViewModel) {
    // I campi tengono il testo per conto loro (il cursore non salta); ripartono dai valori della finestra quando cambia.
    var name by remember(form) { mutableStateOf(form.name) }
    var minecraft by remember(form) { mutableStateOf(form.minecraft) }
    var loader by remember(form) { mutableStateOf(form.loader) }
    var loaderVersion by remember(form) { mutableStateOf(form.loaderVersion) }
    StackedDialog(
        title = if (form.editing == null) "Nuovo profilo" else "Modifica profilo",
        onDismiss = { model.dismissDialog() },
        cancelable = true,
        body = {
            val hint = form.hint
            if (hint != null) BodyText(hint)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Nome del profilo") }
            )
            OutlinedTextField(
                value = minecraft,
                onValueChange = { minecraft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Versione di Minecraft (es. 1.20.1)") }
            )
            Text(text = "Caricatore", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            // Due per riga: in una colonna sola la finestra era così alta che i pulsanti restavano fuori vista.
            for (pair in ModLoader.values().toList().chunked(2)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    for (option in pair) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { loader = option },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = loader == option, onClick = { loader = option })
                            Text(text = option.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            OutlinedTextField(
                value = loaderVersion,
                onValueChange = { loaderVersion = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Versione del caricatore (facoltativa)") }
            )
            val error = form.error
            if (error != null) {
                Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        buttons = {
            GreenDialogButton(if (form.editing == null) "CREA PROFILO" else "SALVA") {
                model.saveProfile(form.editing, ProfileDraft(name, minecraft, loader, loaderVersion.trim().ifEmpty { null }))
            }
            CloseDialogButton("ANNULLA") { model.dismissDialog() }
        }
    )
}

@Composable
private fun DetailsDialog(item: ProfileItem, model: ModsViewModel) {
    StackedDialog(
        title = item.displayName,
        onDismiss = { model.dismissDialog() },
        cancelable = true,
        body = {
            StatRow("Tipo", item.kind.label)
            StatRow("File", item.physicalName)
            StatRow("Dimensione", formatBytes(item.sizeBytes))
            StatRow("Stato", if (item.enabled) "attivo" else "disattivato")
            StatRow("Aggiunto il", Format.dateTime(item.installedAt))
            StatRow("Origine", item.origin)
            if (item.javaMajor != null) StatRow("Java richiesto dal codice", "Java " + item.javaMajor)
            if (item.packFormat != null) StatRow("Formato del pacchetto", item.packFormat.toString())
            Text(
                text = "SHA-256: " + item.sha256,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for (m in item.mods.take(5)) {
                Text(
                    text = m.displayName + (m.version?.let { " $it" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                val extras = listOfNotNull(m.loader.label, m.environment.label, m.license)
                Text(text = extras.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                val description = m.description?.let { Format.clean(it) }
                if (!description.isNullOrBlank()) {
                    Text(text = description.take(300), style = MaterialTheme.typography.bodySmall)
                }
                val required = m.dependencies.filter { it.kind == DependencyKind.REQUIRED && !it.isPlatform }
                if (required.isNotEmpty()) {
                    Text(
                        text = "Richiede: " + required.joinToString(", ") { it.id },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (item.mods.size > 5) {
                Text(text = "…e altre " + (item.mods.size - 5) + " descrizioni.", style = MaterialTheme.typography.bodySmall)
            }
        },
        buttons = { CloseDialogButton("CHIUDI") { model.dismissDialog() } }
    )
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
