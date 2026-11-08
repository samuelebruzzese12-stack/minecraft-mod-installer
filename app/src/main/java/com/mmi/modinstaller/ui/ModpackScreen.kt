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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mmi.modinstaller.MainViewModel
import com.mmi.modinstaller.ModpackDialog
import com.mmi.modinstaller.ModpackState
import com.mmi.modinstaller.ModpackViewModel
import com.mmi.modinstaller.PackOutcome
import com.mmi.modinstaller.PendingPack
import com.mmi.modinstaller.RecordRow
import com.mmi.modinstaller.Section
import com.mmi.modinstaller.core.formatBytes
import com.mmi.modinstaller.modpack.ModpackPlan
import com.mmi.modinstaller.modpack.Pack

/**
 * MODPACK MANAGER: installa un modpack Modrinth (.mrpack) in un profilo del MOD MANAGER.
 *
 * Prima di scaricare qualsiasi cosa mostra cosa contiene il modpack e cosa farebbe l'installazione. Ogni file arriva solo da un
 * sito che l'utente ha consentito, via https, e va nel profilo solo se la sua impronta coincide con quella scritta nel modpack.
 * Non avvia Minecraft e non può sapere se il modpack, una volta installato, si avvia: lo dice chiaramente.
 */
@Composable
fun ModpackScreen(state: ModpackState, model: ModpackViewModel, vm: MainViewModel, onMenu: () -> Unit, onOpenMods: () -> Unit, onConvert: (String) -> Unit = {}) {
    // «*/*»: molti gestori di file non associano un tipo ai .mrpack.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val first = uris.firstOrNull()
        if (first != null) {
            model.openFile(first, if (uris.size > 1) "Un modpack alla volta: ho aperto il primo. Per gli altri ripeti la scelta." else null)
        }
    }

    SectionPage(title = Section.MODPACKS.title, onBack = { vm.back() }, onMenu = onMenu) {
        InfoCard {
            Text(
                text = "Modpack Modrinth (.mrpack) e CurseForge (.zip)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Bullets(
                listOf(
                    "Scegli un file .mrpack: l'app ti mostra cosa contiene e cosa installerebbe, prima di scaricare qualsiasi cosa.",
                    "I file si scaricano solo da siti che consenti tu, con https, e restano nel profilo solo se la loro impronta (SHA) " +
                        "coincide con quella scritta nel modpack.",
                    "Il modpack va in un profilo nuovo del MOD MANAGER, con le sue impostazioni. Se qualcosa non arriva, " +
                        "il modpack risulta «Incompleto» e puoi riprovare solo i file mancanti."
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
            text = "SCEGLI UN MODPACK (.mrpack o .zip)",
            onClick = { picker.launch(arrayOf("*/*")) },
            enabled = state.busy == null,
            height = 56.dp
        )

        val pending = state.pending
        if (pending != null) {
            SectionLabel(if (pending.resume != null) "Da completare" else "Anteprima")
            PendingPackCard(pending, model)
        }

        val outcome = state.outcome
        if (outcome != null) {
            SectionLabel("Esito dell'installazione")
            OutcomeCard(outcome, state, model, onOpenMods, onConvert)
        }

        SectionLabel("Modpack installati")
        if (!state.loaded) {
            Text(
                text = "Lettura dei modpack…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (state.rows.isEmpty()) {
            Text(
                text = "Nessun modpack installato da qui.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            for (row in state.rows) RecordCard(row, model, onOpenMods, onConvert)
        }

        SectionLabel("Cosa fa e cosa no")
        InfoCard {
            Bullets(
                listOf(
                    "Legge l'indice del modpack (modrinth.index.json) e le impostazioni incluse. Non esegue mai codice del modpack.",
                    "Scarica ogni file dall'indirizzo scritto nel modpack e lo tiene solo se l'impronta coincide. Un file che non " +
                        "coincide viene scartato e segnalato.",
                    "Le impostazioni incluse (cartelle config, script, ecc.) vengono messe nel profilo; quelle che non si possono " +
                        "applicare in sicurezza sono elencate.",
                    "Modpack Modrinth (.mrpack) e CurseForge (.zip con manifest.json). Un modpack CurseForge contiene solo l'elenco delle mod: " +
                        "l'app cerca le stesse mod su Modrinth (stessa Minecraft, stesso caricatore) e scarica da lì; quelle che su Modrinth " +
                        "non ci sono vengono elencate per nome.",
                    "Dopo l'installazione, da JAVA TO BEDROCK «CONVERTI LE MOD DI UN PROFILO» porta in Bedrock tutte le mod del profilo in un unico add-on.",
                    "Non avvia Minecraft Java e non può sapere se il modpack parte: lo scopo è avere tutti i file giusti, verificati, " +
                        "in un profilo da esportare (ZIP) nella cartella di gioco del tuo launcher."
                )
            )
        }
    }

    ModpackDialogs(state, model)
}

// ---------------------------------------------------------------------------------------------------- anteprima

@Composable
private fun PendingPackCard(p: PendingPack, model: ModpackViewModel) {
    val plan = p.plan
    val pack = p.pack
    val resuming = p.resume != null
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = pack.name + (pack.versionId?.let { " $it" } ?: ""),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            val tag = packTag(plan, resuming)
            Tag(tag.first, tag.second)
        }
        Text(
            text = packLine(pack),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val summary = pack.summary
        if (!summary.isNullOrBlank()) Text(text = summary.trim().take(300), style = MaterialTheme.typography.bodySmall)

        var downloads = plan.toDownload.size.toString()
        if (plan.downloadBytes > 0) downloads += " · " + formatBytes(plan.downloadBytes)
        if (plan.unknownSizes > 0) downloads += " (+" + plan.unknownSizes + " senza dimensione dichiarata)"
        StatRow("File da scaricare", downloads)
        if (plan.present.isNotEmpty()) StatRow("Già al loro posto", plan.present.size.toString())
        if (plan.skipped.isNotEmpty()) StatRow("Solo per server (non scaricati)", plan.skipped.size.toString())
        if (pack.overrides.applied > 0) StatRow("Impostazioni da applicare", pack.overrides.applied.toString())

        for (blocker in plan.blockers) ToneRow(Tone.BAD, blocker)
        BlockedSection(plan, model)
        if (pack.problems.isNotEmpty()) {
            ToneRow(
                Tone.WARN,
                pack.problems.size.toString() + (if (pack.problems.size == 1) " voce dell'indice è stata scartata" else " voci dell'indice sono state scartate") +
                    " perché non valide: il modpack risulterà incompleto.",
                pack.problems.cappedLines(3) { it }.joinToString("\n")
            )
        }
        if (p.lowOnSpace) {
            ToneRow(
                Tone.WARN,
                "Poco spazio libero sul telefono",
                "Libero: " + formatBytes(p.freeBytes) + " · servono circa " + formatBytes(p.neededBytes) + "."
            )
        }

        if (resuming) {
            Text(
                text = "Si completa il profilo «" + p.profileName + "»: i file già al loro posto con l'impronta giusta non si riscaricano.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            // Il testo vive qui oltre che nello stato: così il cursore non salta mentre si scrive.
            var name by remember(p.file.path) { mutableStateOf(p.profileName) }
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    model.setProfileName(it)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Nome del nuovo profilo") }
            )
            val error = p.nameError
            if (error != null) Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        GreenButton(
            text = if (resuming) "COMPLETA L'INSTALLAZIONE" else "INSTALLA IL MODPACK",
            onClick = { model.install() },
            enabled = plan.canInstall && (resuming || p.profileName.isNotBlank()),
            height = 52.dp
        )
        FullButton("SCARTA") { model.discardPending() }
    }
}

/** I file che non si possono scaricare adesso: quelli di un sito da consentire (con il pulsante) e quelli senza nessuna via ammessa. */
@Composable
private fun BlockedSection(plan: ModpackPlan, model: ModpackViewModel) {
    val blocked = plan.blocked
    if (blocked.isEmpty()) return
    for (host in plan.hostsToApprove) {
        val n = blocked.count { it.hostToApprove == host }
        ToneRow(
            Tone.WARN,
            n.toString() + (if (n == 1) " file si scarica" else " file si scaricano") + " da «" + host + "», un sito che non hai ancora consentito."
        )
        FullButton("CONSENTI «$host»") { model.askConsent(host) }
    }
    val noWay = blocked.filter { it.hostToApprove == null }
    if (noWay.isNotEmpty()) {
        ToneRow(
            Tone.BAD,
            noWay.size.toString() + (if (noWay.size == 1) " file non ha" else " file non hanno") +
                " nessun indirizzo ammesso dalle regole di sicurezza (servono https e un sito sicuro): non " +
                (if (noWay.size == 1) "verrà scaricato." else "verranno scaricati."),
            noWay.cappedLines(4) { it.file.path }.joinToString("\n")
        )
    }
    Text(
        text = "Senza questi file il modpack resta incompleto: a fine installazione l'app lo dice e ti lascia riprovare.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun packTag(plan: ModpackPlan, resuming: Boolean): Pair<String, Color> = when {
    !plan.canInstall -> "Non installabile" to toneColor(Tone.BAD)
    plan.blocked.isNotEmpty() || plan.pack.problems.isNotEmpty() -> "Incompleto" to toneColor(Tone.WARN)
    resuming -> "Da completare" to toneColor(Tone.INFO)
    else -> "Pronto" to toneColor(Tone.OK)
}

/** «Minecraft 1.20.1 · Fabric 0.15.11». */
private fun packLine(pack: Pack): String {
    val loader = pack.loader?.label?.let { it + (pack.loaderVersion?.let { v -> " $v" } ?: "") } ?: "caricatore non indicato"
    return "Minecraft " + (pack.minecraft ?: "non indicato") + " · " + loader
}

// ---------------------------------------------------------------------------------------------------- esito

@Composable
private fun OutcomeCard(o: PackOutcome, state: ModpackState, model: ModpackViewModel, onOpenMods: () -> Unit, onConvert: (String) -> Unit) {
    val r = o.result
    val row = state.rows.firstOrNull { it.record.id == o.recordId }
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = o.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (r.complete) Tag("Completo", toneColor(Tone.OK)) else Tag("Incompleto", toneColor(Tone.WARN))
        }
        if (r.complete) {
            ToneRow(
                Tone.OK,
                "Tutti i " + r.totalFiles + " file del modpack sono nel profilo",
                "L'impronta di ognuno è stata controllata. Questo non garantisce che il gioco parta con questo modpack."
            )
        } else {
            ToneRow(
                Tone.WARN,
                r.placedFiles.toString() + " file su " + r.totalFiles + " sono al loro posto: il modpack è incompleto",
                "Qui sotto trovi cosa manca e perché."
            )
        }
        StatRow("Registrati nel MOD MANAGER", r.registered.toString())
        if (r.overridesApplied > 0 || r.overridesSkipped.isNotEmpty()) {
            StatRow("Impostazioni applicate", r.overridesApplied.toString())
        }
        if (r.skippedByEnv.isNotEmpty()) StatRow("Solo per server (non scaricati)", r.skippedByEnv.size.toString())

        if (r.failures.isNotEmpty()) {
            ListBlock("File che non sono arrivati (" + r.failures.size + ")", r.failures.cappedLines(8) { it.path + ": " + it.reason })
        }
        if (r.overridesSkipped.isNotEmpty()) {
            ListBlock("Impostazioni non applicate (" + r.overridesSkipped.size + ")", r.overridesSkipped.cappedLines(5) { it })
        }
        if (r.problems.isNotEmpty()) {
            ListBlock("Voci dell'indice scartate (" + r.problems.size + ")", r.problems.cappedLines(5) { it })
        }
        if (r.unreadable.isNotEmpty()) {
            ListBlock(
                "File al loro posto ma non letti come mod (" + r.unreadable.size + ")",
                r.unreadable.cappedLines(5) { it } + "Sono nel profilo, ma l'app non ne ha potuto leggere il contenuto."
            )
        }

        val pid = o.profileId
        Text(
            text = "L'app non avvia Minecraft: per usare il modpack apri il profilo nel MOD MANAGER ed esporta i file attivi in uno ZIP " +
                "da mettere nella cartella di gioco del tuo launcher.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (pid != null && row?.profileName != null) {
            GreenButton(text = "PORTA IN BEDROCK (JAVA TO BEDROCK)", onClick = { onConvert(pid) }, height = 52.dp)
            FullButton("APRI NEL MOD MANAGER") { model.openProfile(pid, onOpenMods) }
        }
        if (!r.complete && row != null && row.profileName != null) {
            FullButton("RIPROVA I FILE MANCANTI") { model.prepareResume(row.record) }
        }
        FullButton("CHIUDI") { model.clearOutcome() }
    }
}

@Composable
private fun ListBlock(title: String, lines: List<String>) {
    Text(text = title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    Bullets(lines)
}

// ---------------------------------------------------------------------------------------------------- elenco

@Composable
private fun RecordCard(row: RecordRow, model: ModpackViewModel, onOpenMods: () -> Unit, onConvert: (String) -> Unit) {
    val r = row.record
    val profileName = row.profileName
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = r.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (r.complete) Tag("Completo", toneColor(Tone.OK)) else Tag("Incompleto", toneColor(Tone.WARN))
        }
        Text(
            text = "Minecraft " + r.minecraft + " · " + r.loader.label + (r.loaderVersion?.let { " $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = r.placedFiles.toString() + " di " + r.totalFiles + " file al loro posto",
            style = MaterialTheme.typography.bodySmall
        )
        if (profileName != null) {
            Text(text = "Profilo: «$profileName»", style = MaterialTheme.typography.bodySmall)
        } else {
            ToneRow(Tone.WARN, "Il profilo di questo modpack non esiste più (eliminato dal MOD MANAGER).")
        }
        if (r.problems.isNotEmpty()) Bullets(r.problems.cappedLines(3) { it })

        if (profileName != null) {
            ButtonsRow {
                RowButton("APRI IL PROFILO") { model.openProfile(r.profileId, onOpenMods) }
                if (!r.complete) RowButton("COMPLETA") { model.prepareResume(r) }
            }
            FullButton("PORTA IN BEDROCK") { onConvert(r.profileId) }
        }
        FullButton("TOGLI DALL'ELENCO") { model.askRemove(r) }
    }
}

// ---------------------------------------------------------------------------------------------------- finestre

@Composable
private fun ModpackDialogs(state: ModpackState, model: ModpackViewModel) {
    when (val d = state.dialog) {
        is ModpackDialog.Consent -> StackedDialog(
            title = "Consentire i download da «" + d.host + "»?",
            onDismiss = { model.dismissDialog() },
            cancelable = true,
            body = {
                BodyText("L'app scaricherà da questo sito i file del modpack e nient'altro. Non invia nulla del tuo telefono: fa solo richieste per ricevere i file.")
                BodyText("Ogni file viene tenuto solo se la sua impronta coincide con quella scritta nel modpack. Consenti solo i siti di cui ti fidi.")
            },
            buttons = {
                GreenDialogButton("CONSENTI E CONTINUA") { model.confirmConsent() }
                CloseDialogButton("ANNULLA") { model.dismissDialog() }
            }
        )
        is ModpackDialog.RemoveRecord -> StackedDialog(
            title = "Togliere il modpack dall'elenco?",
            onDismiss = { model.dismissDialog() },
            cancelable = true,
            body = {
                BodyText(
                    "«" + d.record.name + "» esce da questo elenco e la copia del file .mrpack conservata dall'app viene cancellata."
                )
                BodyText("Il profilo e i suoi file restano nel MOD MANAGER: non perdi nulla di quello che è già stato installato.")
            },
            buttons = {
                DangerDialogButton("TOGLI DALL'ELENCO") { model.removeRecord(d.record) }
                CloseDialogButton("ANNULLA") { model.dismissDialog() }
            }
        )
        null -> {}
    }
}

// ---------------------------------------------------------------------------------------------------- utilità

/** Le prime [max] righe e, se ce ne sono altre, «…e altri N». */
private fun <T> List<T>.cappedLines(max: Int, line: (T) -> String): List<String> {
    val out = ArrayList<String>()
    for (i in 0 until minOf(size, max)) out.add(line(this[i]))
    if (size > max) out.add("…e altri " + (size - max))
    return out
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
