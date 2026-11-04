package com.mmi.modinstaller.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mmi.modinstaller.MainViewModel
import com.mmi.modinstaller.PickPurpose
import com.mmi.modinstaller.Screen
import com.mmi.modinstaller.UiState
import com.mmi.modinstaller.core.InstallState

@Composable
fun HomeScreen(state: UiState, vm: MainViewModel, onPick: (PickPurpose) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Minecraft Mod Installer",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Importa mod, mappe, texture e add-on in Minecraft Bedrock",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { vm.go(Screen.ABOUT) }) {
                Icon(Icons.Filled.Info, contentDescription = "Informazioni e limiti")
            }
        }

        MinecraftCard(state, vm)

        val pending = state.unconfirmed
        if (pending.isNotEmpty()) {
            val latest = pending[0]
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Hai inviato «${latest.title}» a Minecraft",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text(
                        text = "Minecraft non comunica all'app se l'importazione è riuscita. " +
                            "Controlla nel gioco e rispondi: finché non lo fai il contenuto resta «da confermare».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.markEntry(latest.id, InstallState.CONFIRMED) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = InstallGreen, contentColor = Color.White)
                        ) { Text("Sì, importato") }
                        OutlinedButton(
                            onClick = { vm.markEntry(latest.id, InstallState.FAILED) },
                            modifier = Modifier.weight(1f)
                        ) { Text("No, errore") }
                    }
                    if (pending.size > 1) {
                        TextButton(onClick = { vm.go(Screen.INSTALLED) }) {
                            Text("Vedi tutti i contenuti da confermare (${pending.size})")
                        }
                    }
                }
            }
        }

        GreenButton(
            text = "+ SELEZIONA FILE",
            onClick = { onPick(PickPurpose.ANY) },
            height = 120.dp
        )
        Text(
            text = "Scegli un file scaricato: .mcpack, .mcaddon, .mcworld, .mctemplate, .zip, .rar o .7z. " +
                "L'app lo controlla e ti mostra un rapporto prima di installare.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ImportTile("IMPORTA MOD", Modifier.weight(1f)) { onPick(PickPurpose.MOD) }
            ImportTile("IMPORTA MAPPA", Modifier.weight(1f)) { onPick(PickPurpose.MAP) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ImportTile("IMPORTA TEXTURE", Modifier.weight(1f)) { onPick(PickPurpose.TEXTURE) }
            ImportTile("IMPORTA ADD-ON", Modifier.weight(1f)) { onPick(PickPurpose.ADDON) }
        }

        ListButton("FILE RECENTI", state.recents.size) { vm.go(Screen.RECENT) }
        ListButton("MOD INSTALLATI", state.installed.size) { vm.go(Screen.INSTALLED) }
        ListButton("CRONOLOGIA", state.history.size) { vm.go(Screen.HISTORY) }
        ListButton("BACKUP", state.backups.size) { vm.go(Screen.BACKUPS) }
    }
}

@Composable
private fun MinecraftCard(state: UiState, vm: MainViewModel) {
    val mc = state.minecraft
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (mc.installed) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                if (mc.installed) {
                    Text(
                        text = (mc.label ?: "Minecraft") + " trovato",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (mc.versionName != null) "Versione ${mc.versionName}" else "Versione non leggibile",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        text = "Minecraft non trovato",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Installa Minecraft Bedrock dal Play Store per poter importare i contenuti.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (mc.installed) {
                TextButton(onClick = { vm.openMinecraft() }) { Text("Apri") }
            } else {
                TextButton(onClick = { vm.openStore() }) { Text("Play Store") }
            }
        }
    }
}

@Composable
private fun ImportTile(text: String, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(72.dp),
        shape = RoundedCornerShape(18.dp)
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun ListButton(text: String, count: Int, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(text = text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(text = count.toString(), color = MaterialTheme.colorScheme.primary)
    }
}
