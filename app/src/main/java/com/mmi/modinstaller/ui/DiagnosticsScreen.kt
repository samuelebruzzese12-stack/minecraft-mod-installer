package com.mmi.modinstaller.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mmi.modinstaller.BridgeState
import com.mmi.modinstaller.BridgeViewModel
import com.mmi.modinstaller.CrashView
import com.mmi.modinstaller.MainViewModel
import com.mmi.modinstaller.ModsViewModel
import com.mmi.modinstaller.mods.CrashCause
import com.mmi.modinstaller.Section
import com.mmi.modinstaller.UiState
import com.mmi.modinstaller.core.IssueLevel
import com.mmi.modinstaller.core.formatBytes
import java.util.Locale

/** Diagnostica: telefono, spazio, controllo dei file preparati, ultimi errori e rapporto da condividere. */
@Composable
fun DiagnosticsScreen(
    state: UiState,
    bridge: BridgeState,
    vm: MainViewModel,
    model: BridgeViewModel,
    mods: ModsViewModel,
    onMenu: () -> Unit
) {
    val context = LocalContext.current
    val pickCrash = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.analyzeCrash(uri) { vm.showMessage(it) }
    }
    SectionPage(
        title = Section.DIAGNOSTICS.title,
        onBack = { vm.back() },
        onMenu = onMenu,
        actions = {
            IconButton(onClick = { model.refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Aggiorna i dati")
            }
        }
    ) {
        SectionLabel("Telefono")
        InfoCard {
            val d = bridge.device
            if (d == null) {
                Text("Dati del telefono non disponibili.", style = MaterialTheme.typography.bodyMedium)
            } else {
                StatRow("Modello", d.model)
                StatRow("Android", d.androidRelease + " (API " + d.sdk + ")")
                StatRow("Architettura", d.abis.joinToString(", ").ifEmpty { "sconosciuta" })
                StatRow("Core CPU", d.cpuCores.toString())
                StatRow(
                    "Memoria libera",
                    formatBytes(d.availableRamBytes) + " su " + formatBytes(d.totalRamBytes),
                    if (d.lowMemory) Tone.WARN else null
                )
                StatRow("Spazio libero", formatBytes(d.storageFreeBytes) + " su " + formatBytes(d.storageTotalBytes))
                d.batteryTempC?.let { StatRow("Temperatura batteria", String.format(Locale.ROOT, "%.1f", it) + " °C") }
                d.thermalStatus?.let {
                    StatRow("Stato termico", it.toString() + if (it >= 3) " (caldo)" else "", if (it >= 3) Tone.WARN else null)
                }
            }
        }

        SectionLabel("Spazio usato dall'app")
        InfoCard {
            for (item in bridge.space) StatRow(item.label, formatBytes(item.bytes))
            StatRow("Totale", formatBytes(bridge.appBytes))
        }

        SectionLabel("Controllo dei file")
        InfoCard {
            Text(
                text = "Verifica che i pacchetti preparati esistano ancora, abbiano la dimensione registrata e siano archivi " +
                    "leggibili. Non modifica nulla.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { model.checkIntegrity() },
                enabled = !bridge.checking,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (bridge.checking) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  CONTROLLO IN CORSO…")
                } else {
                    Text("CONTROLLA I FILE")
                }
            }
            val integrity = bridge.integrity
            if (integrity != null) {
                if (integrity.ok) {
                    ToneRow(
                        Tone.OK,
                        "Nessun problema",
                        Format.plural(integrity.checked, "contenuto controllato", "contenuti controllati")
                    )
                } else {
                    for (issue in integrity.issues) {
                        ToneRow(
                            if (issue.level == IssueLevel.PROBLEM) Tone.BAD else Tone.WARN,
                            issue.title,
                            issue.detail
                        )
                    }
                }
            }
        }

        SectionLabel("Ultimi errori")
        InfoCard {
            if (bridge.errors.isEmpty()) {
                ToneRow(Tone.OK, "Nessun errore registrato")
            } else {
                for (e in bridge.errors.take(MAX_ERRORS_ON_SCREEN)) {
                    ToneRow(Tone.BAD, Format.dateTime(e.at) + " · " + e.where, e.message)
                }
                if (bridge.errorCount > MAX_ERRORS_ON_SCREEN) {
                    Text(
                        text = "Altri " + (bridge.errorCount - MAX_ERRORS_ON_SCREEN) + " più vecchi (sono nel rapporto).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = { model.clearErrors() }, modifier = Modifier.fillMaxWidth()) {
                    Text("SVUOTA IL REGISTRO")
                }
            }
        }

        SectionLabel("Rapporto")
        InfoCard {
            Text(
                text = "Il rapporto contiene i dati del telefono e gli ultimi errori, senza i tuoi file. " +
                    "Si condivide solo se premi il pulsante.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = {
                    val mc = state.minecraft
                    val minecraftLines = if (mc.installed) {
                        listOf((mc.label ?: "Minecraft") + " " + (mc.versionName ?: "(versione non leggibile)"))
                    } else {
                        emptyList()
                    }
                    val counts = listOf(
                        "Contenuti Bedrock gestiti" to state.installed.size.toString(),
                        "Da confermare" to state.unconfirmed.size.toString(),
                        "Backup" to state.backups.size.toString()
                    )
                    val text = model.reportText(minecraftLines, counts)
                    if (!shareText(context, text)) vm.showMessage("Non riesco ad aprire la condivisione.")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("CONDIVIDI IL RAPPORTO", fontWeight = FontWeight.Bold)
            }
        }

        SectionLabel("Analisi di un crash (Java)")
        InfoCard {
            Text(
                text = "Se Minecraft Java si chiude da solo, scegli il crash report (cartella crash-reports, file .txt) o il log " +
                    "(logs/latest.log, anche .log.gz) del programma di avvio. L'app lo legge soltanto: non lo modifica e non lo invia.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { pickCrash.launch(arrayOf("*/*")) },
                enabled = !bridge.crashReading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (bridge.crashReading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  LETTURA IN CORSO…")
                } else {
                    Text("SCEGLI UN CRASH REPORT O UN LOG")
                }
            }
        }
        bridge.crash?.let { crash ->
            CrashCard(crash, onOpenProfile = { id ->
                mods.selectProfile(id)
                vm.openSection(Section.MODS)
            }, onShare = {
                if (!shareText(context, crashText(crash), "Analisi del crash", "Condividi l'analisi")) vm.showMessage("Non riesco ad aprire la condivisione.")
            }, onClose = { model.closeCrash() })
        }
    }
}

private const val MAX_ERRORS_ON_SCREEN = 10

@Composable
private fun CrashCard(crash: CrashView, onOpenProfile: (String) -> Unit, onShare: () -> Unit, onClose: () -> Unit) {
    val a = crash.analysis
    SectionLabel("Esito dell'analisi")
    InfoCard {
        Text(crash.fileName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        StatRow("Tipo", if (a.isCrashReport) "Crash report di Minecraft" else "Log o altro testo")
        a.minecraft?.let { StatRow("Minecraft", it) }
        a.loader?.let { StatRow("Caricatore", it) }
        a.java?.let { StatRow("Java", it) }
        a.description?.let { StatRow("Descrizione", it) }
        if (crash.truncated) {
            ToneRow(Tone.WARN, "File molto grande", "Letto solo fino a " + formatBytes(crash.bytesRead) + ": l'analisi guarda l'inizio e la fine.")
        }
        for (f in a.findings) {
            ToneRow(if (f.cause == CrashCause.UNKNOWN) Tone.WARN else Tone.BAD, f.cause.label, f.text + "\n" + f.advice)
        }
        if (a.suspectedMods.isNotEmpty()) {
            ToneRow(Tone.INFO, "Mod sospettate dal gioco", a.suspectedMods.joinToString(", "))
        }
        Text(
            text = "Sono indicazioni ricavate dai messaggi del gioco e del caricatore, non certezze.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (crash.matches.isNotEmpty()) {
        SectionLabel("Nei tuoi profili")
        InfoCard {
            for ((profileId, rows) in crash.matches.groupBy { it.profile.id }) {
                val profile = rows.first().profile
                Text(profile.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                for (m in rows) {
                    ToneRow(
                        if (m.item.enabled) Tone.INFO else Tone.WARN,
                        "«" + m.modId + "»: " + m.item.displayName,
                        m.item.fileName + if (m.item.enabled) "" else " (disattivata)"
                    )
                }
                OutlinedButton(onClick = { onOpenProfile(profileId) }, modifier = Modifier.fillMaxWidth()) {
                    Text("APRI NEL MOD MANAGER")
                }
            }
        }
    }
    OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) { Text("CONDIVIDI L'ANALISI") }
    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("CHIUDI L'ANALISI") }
}

/** L'analisi in testo semplice, da condividere (senza il contenuto del file). */
private fun crashText(crash: CrashView): String {
    val a = crash.analysis
    val b = StringBuilder()
    b.append("Analisi di ").append(crash.fileName).append('\n')
    a.minecraft?.let { b.append("Minecraft: ").append(it).append('\n') }
    a.loader?.let { b.append("Caricatore: ").append(it).append('\n') }
    a.java?.let { b.append("Java: ").append(it).append('\n') }
    a.description?.let { b.append("Descrizione: ").append(it).append('\n') }
    a.exception?.let { b.append("Errore: ").append(it).append('\n') }
    for (f in a.findings) b.append("- ").append(f.cause.label).append(": ").append(f.text).append(" ").append(f.advice).append('\n')
    if (a.suspectedMods.isNotEmpty()) b.append("Mod sospettate: ").append(a.suspectedMods.joinToString(", ")).append('\n')
    return b.toString()
}

private fun shareText(
    context: Context,
    text: String,
    subject: String = "Rapporto di diagnostica",
    chooserTitle: String = "Condividi il rapporto"
): Boolean {
    return try {
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_SUBJECT, subject)
        send.putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, chooserTitle))
        true
    } catch (e: Exception) {
        false
    }
}
