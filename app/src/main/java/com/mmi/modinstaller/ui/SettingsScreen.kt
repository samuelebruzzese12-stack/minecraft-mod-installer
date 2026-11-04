package com.mmi.modinstaller.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mmi.modinstaller.BridgeState
import com.mmi.modinstaller.BridgeViewModel
import com.mmi.modinstaller.BuildConfig
import com.mmi.modinstaller.MainViewModel
import com.mmi.modinstaller.Section
import com.mmi.modinstaller.UiState
import com.mmi.modinstaller.core.AppSettings
import com.mmi.modinstaller.core.PerformanceProfile
import com.mmi.modinstaller.core.ThemeMode
import com.mmi.modinstaller.core.formatBytes

/** Impostazioni: tema, profilo di prestazioni e manutenzione. Ogni scelta viene salvata nell'archivio centrale. */
@Composable
fun SettingsScreen(
    state: UiState,
    bridge: BridgeState,
    vm: MainViewModel,
    model: BridgeViewModel,
    onMenu: () -> Unit
) {
    val settings = bridge.settings
    SectionPage(title = Section.SETTINGS.title, onBack = { vm.back() }, onMenu = onMenu) {
        SectionLabel("Tema")
        InfoCard {
            for (mode in ThemeMode.values()) {
                ChoiceRow(
                    selected = settings.theme == mode,
                    title = mode.label,
                    detail = null,
                    onSelect = { model.setTheme(mode) }
                )
            }
        }

        SectionLabel("Profilo prestazioni (Java Edition)")
        InfoCard {
            Text(
                text = "Decide quanta memoria dare al gioco Java quando sarà disponibile. Nessun profilo usa tutta la memoria del telefono.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for (profile in PerformanceProfile.values()) {
                ChoiceRow(
                    selected = settings.profile == profile,
                    title = profile.label,
                    detail = profile.description,
                    onSelect = { model.setProfile(profile) }
                )
            }
            if (settings.profile == PerformanceProfile.CUSTOM) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { model.setCustomMemory(settings.customMemoryMb - MEMORY_STEP_MB) },
                        enabled = settings.customMemoryMb > AppSettings.MIN_CUSTOM_MB
                    ) { Text("−") }
                    Text(
                        text = settings.customMemoryMb.toString() + " MB",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = { model.setCustomMemory(settings.customMemoryMb + MEMORY_STEP_MB) },
                        enabled = settings.customMemoryMb < AppSettings.MAX_CUSTOM_MB
                    ) { Text("+") }
                }
            }
            val plan = bridge.memoryPlan
            if (plan != null) {
                StatRow("Memoria che verrebbe usata", plan.memoryMb.toString() + " MB")
                Text(
                    text = plan.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionLabel("Manutenzione")
        InfoCard {
            val busy = state.busy != null || state.files.isNotEmpty()
            OutlinedButton(
                onClick = {
                    model.clearTemporaryFiles { freed ->
                        vm.showMessage(
                            if (freed > 0) "File temporanei eliminati: liberati " + formatBytes(freed) + "."
                            else "Non c'erano file temporanei da eliminare."
                        )
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("SVUOTA I FILE TEMPORANEI") }
            if (busy) {
                Text(
                    text = "Disponibile quando non c'è un rapporto aperto nell'INSTALLER BEDROCK: le copie temporanee servono a installarlo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = { model.clearErrors() },
                enabled = bridge.errorCount > 0,
                modifier = Modifier.fillMaxWidth()
            ) { Text("AZZERA IL REGISTRO ERRORI") }
        }

        SectionLabel("Informazioni")
        InfoCard {
            StatRow("Versione", BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")")
            Text(
                text = "I tuoi file restano sul telefono: l'app usa Internet solo per scaricare, su tua richiesta, i file del DOWNLOAD MANAGER, " +
                    "e non invia nulla a nessuno. " +
                    "Le impostazioni e gli elenchi sono nello stesso archivio dell'INSTALLER BEDROCK, quindi " +
                    "aggiornare l'app non li cancella.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private const val MEMORY_STEP_MB = 256

/** Una scelta singola (tema, profilo): tutta la riga è toccabile. */
@Composable
private fun ChoiceRow(selected: Boolean, title: String, detail: String?, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
