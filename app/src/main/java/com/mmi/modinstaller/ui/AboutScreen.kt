package com.mmi.modinstaller.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mmi.modinstaller.BuildConfig
import com.mmi.modinstaller.MainViewModel
import com.mmi.modinstaller.UiState

@Composable
private fun InfoCard(title: String, lines: List<String>) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            for (line in lines) {
                Text(text = line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun AboutScreen(state: UiState, vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Informazioni", onBack = { vm.back() })
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Minecraft Mod Installer · versione " + BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            InfoCard(
                "Come funziona",
                listOf(
                    "1. Scegli un file scaricato (.mcpack, .mcaddon, .mcworld, .mctemplate, .zip, .rar, .7z, .json).",
                    "2. L'app lo apre e controlla davvero il contenuto: manifest.json, UUID, versioni, dipendenze, " +
                        "file mancanti, archivio danneggiato, duplicati e compatibilità con la versione di Minecraft installata.",
                    "3. Se serve, prepara un pacchetto pulito (per esempio toglie la cartella di troppo in cui è finito " +
                        "il contenuto dentro lo ZIP).",
                    "4. Con INSTALLA IN MINECRAFT consegna il pacchetto a Minecraft con l'apertura ufficiale di Android: " +
                        "è Minecraft a importarlo."
                )
            )

            InfoCard(
                "Cosa Android e Minecraft non permettono",
                listOf(
                    "Minecraft non comunica all'app se l'importazione è riuscita. Per questo l'app non scrive mai " +
                        "«installato» da sola: dopo l'invio ti chiede cosa hai visto nel gioco e registra la tua risposta.",
                    "Android impedisce alle app di scrivere nelle cartelle private di altre app (compresa quella di " +
                        "Minecraft). L'app non ci prova nemmeno: usa solo l'importazione ufficiale.",
                    "Se Minecraft rifiuta un contenuto (per esempio perché lo stesso UUID e la stessa versione " +
                        "sono già importati) il messaggio compare dentro Minecraft, non qui.",
                    "I file RAR5 e gli archivi protetti da password non si possono leggere: converti l'archivio in ZIP o 7z.",
                    "Sono supportati i contenuti per Minecraft Bedrock (Android). I contenuti per Java Edition vengono riconosciuti ma non installati."
                )
            )

            InfoCard(
                "Sicurezza e privacy",
                listOf(
                    "Internet serve solo al DOWNLOAD MANAGER, per RICEVERE il file che indichi (solo https, solo siti consentiti). " +
                        "L'app non invia nulla dal telefono: nessun file, nessun dato.",
                    "Gli archivi sono letti con limiti su dimensione, numero di file e rapporto di compressione " +
                        "(protezione da zip bomb) e i percorsi pericolosi (ZIP Slip, «../») vengono bloccati.",
                    "Nessun codice contenuto negli archivi viene eseguito. I file eseguibili non vengono copiati nel pacchetto.",
                    "Prima di eliminare un contenuto dall'app ne viene salvata una copia di sicurezza."
                )
            )

            InfoCard(
                "Formati riconosciuti",
                listOf(
                    "Resource pack (texture), behavior pack, add-on, mappe e template di mondo, pacchetti di skin.",
                    "Archivi ZIP, RAR (versione 4) e 7z, anche con più contenuti o archivi annidati.",
                    "Il file .json viene riconosciuto come manifest, ma da solo non è installabile."
                )
            )

            if (state.minecraft.installed) {
                OutlinedButton(onClick = { vm.openMinecraft() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Apri Minecraft")
                }
            } else {
                OutlinedButton(onClick = { vm.openStore() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Minecraft non trovato · apri il Play Store")
                }
            }
        }
    }
}
