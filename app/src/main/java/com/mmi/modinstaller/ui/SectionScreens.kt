package com.mmi.modinstaller.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mmi.modinstaller.Availability
import com.mmi.modinstaller.BridgeState
import com.mmi.modinstaller.MainViewModel
import com.mmi.modinstaller.Section
import com.mmi.modinstaller.UiState
import com.mmi.modinstaller.core.DeviceAssessment
import com.mmi.modinstaller.core.JavaRequirements
import com.mmi.modinstaller.core.formatBytes

// ---------------------------------------------------------------------------------------------------- elementi comuni

/** Pagina di una sezione: barra con il tasto indietro e il menu, poi il contenuto scorrevole. */
@Composable
fun SectionPage(
    title: String,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        AppTopBar(
            title = title,
            onBack = onBack,
            actions = {
                actions()
                IconButton(onClick = onMenu) {
                    Icon(Icons.Filled.Menu, contentDescription = "Apri il menu")
                }
            }
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

/** Riquadro con sfondo leggero per raggruppare informazioni. */
@Composable
fun InfoCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

/** Riga «etichetta … valore»; il valore può avere il colore di uno stato. */
@Composable
fun StatRow(label: String, value: String, tone: Tone? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (tone != null) toneColor(tone) else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End
        )
    }
}

/** Elenco puntato. */
@Composable
fun Bullets(lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (line in lines) {
            Row {
                Text(text = "•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp))
                Text(text = line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun availabilityColor(availability: Availability): Color = when (availability) {
    Availability.READY -> toneColor(Tone.OK)
    Availability.PARTIAL -> toneColor(Tone.WARN)
    Availability.SOON -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ---------------------------------------------------------------------------------------------------- dashboard

/** Dashboard: stato dell'app in breve e l'elenco delle sezioni, ciascuna con il suo stato reale di avanzamento. */
@Composable
fun DashboardScreen(state: UiState, bridge: BridgeState, vm: MainViewModel, onMenu: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.Menu, contentDescription = "Apri il menu")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Minecraft Mod Installer",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Bedrock, Java, mod e mondi in un'unica app",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        StatusCard(state, bridge)

        SectionLabel("Sezioni")
        for (section in Section.values()) {
            if (section == Section.HOME) continue
            SectionCard(section) { vm.openSection(section) }
        }
        Text(
            text = "Le sezioni «In arrivo» non sono ancora state costruite: aprendole trovi cosa faranno e cosa manca, " +
                "senza pulsanti finti.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusCard(state: UiState, bridge: BridgeState) {
    val mc = state.minecraft
    val pending = state.unconfirmed.size
    val device = bridge.device
    InfoCard {
        Text(text = "Stato", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        StatRow(
            "Minecraft Bedrock",
            if (!mc.installed) "non trovato" else mc.versionName?.let { "versione $it" } ?: "trovato",
            if (mc.installed) Tone.OK else Tone.WARN
        )
        StatRow(
            "Contenuti Bedrock gestiti",
            state.installed.size.toString() + if (pending > 0) " · $pending da confermare" else ""
        )
        if (device != null) {
            StatRow("Memoria libera", formatBytes(device.availableRamBytes) + " su " + formatBytes(device.totalRamBytes))
            StatRow("Spazio libero", formatBytes(device.storageFreeBytes) + " su " + formatBytes(device.storageTotalBytes))
        }
        StatRow("Spazio usato dall'app", formatBytes(bridge.appBytes))
        StatRow("Runtime Java", "non integrato", Tone.INFO)
        StatRow(
            "Errori registrati",
            bridge.errorCount.toString(),
            if (bridge.errorCount > 0) Tone.WARN else Tone.OK
        )
    }
}

@Composable
private fun SectionCard(section: Section, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = section.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Tag(section.availability.label, availabilityColor(section.availability))
        }
    }
}

// ---------------------------------------------------------------------------------------------------- sezioni non ancora costruite

private class SectionPlan(val what: List<String>, val missing: String)

private fun planFor(section: Section): SectionPlan = when (section) {
    Section.MODS -> SectionPlan(
        listOf(
            "Leggere le mod Java (.jar, .zip, .mrpack) senza mai eseguirne il codice",
            "Riconoscere loader (Fabric, Quilt, Forge, NeoForge), versioni di Minecraft e dipendenze",
            "Calcolare l'impronta SHA-256 e tenere le mod in profili indipendenti, con backup e annullamento"
        ),
        "Non è ancora stato costruito. Le mod Java servono a Minecraft Java Edition e non funzionano in Bedrock."
    )
    Section.MODPACKS -> SectionPlan(
        listOf(
            "Leggere i modpack Modrinth (.mrpack) e CurseForge (manifest.json)",
            "Scaricare i file mancanti con ripresa e verifica, e creare un profilo indipendente",
            "Applicare gli «overrides» del modpack solo dentro il profilo"
        ),
        "Non è ancora stato costruito. Dipende dal Download Manager e dal Mod Manager."
    )
    Section.WORLDS -> SectionPlan(
        listOf(
            "Importare mondi Java e Bedrock (ZIP, .mcworld, .mctemplate) anche molto grandi, a flusso",
            "Lavorare sempre su una copia, lasciando intatto l'originale, con ripresa se si interrompe",
            "Contare e controllare i file del mondo prima di importarlo"
        ),
        "Non è ancora stato costruito. I mondi Bedrock (.mcworld) si possono già importare dall'INSTALLER BEDROCK."
    )
    Section.CONVERTER -> SectionPlan(
        listOf(
            "Analizzare un mondo e dire quali blocchi hanno un equivalente nell'altra edizione",
            "Mostrare un rapporto onesto di cosa verrebbe perso o cambiato"
        ),
        "Non è ancora stato costruito. Una conversione completa richiede un motore dedicato (come Chunker, che usa Java) " +
            "che questa app non include: non mostrerà mai «convertito al 100%» senza una verifica reale."
    )
    Section.JAVA_TO_BEDROCK -> SectionPlan(
        listOf(
            "Analizzare in modo statico una mod Java per capire cosa usa (blocchi, oggetti, ricette, texture)",
            "Preparare un rapporto di cosa potrebbe avere un equivalente in Bedrock"
        ),
        "Non è ancora stato costruito. Il codice Java di una mod non si può tradurre automaticamente in un add-on " +
            "Bedrock: l'app non promette conversioni complete."
    )
    Section.DOWNLOADS -> SectionPlan(
        listOf(
            "Scaricare file da fonti ufficiali (come Modrinth) o da link che autorizzi tu",
            "Riprendere i download interrotti e verificare l'impronta dei file",
            "Non inviare mai i tuoi file a nessuno"
        ),
        "Non è ancora stato costruito. Oggi l'app non ha il permesso Internet: lo chiederà solo quando questa sezione esisterà."
    )
    else -> SectionPlan(emptyList(), "")
}

/** Pagina onesta per una sezione non ancora costruita: cosa farà e cosa manca, nessun pulsante finto. */
@Composable
fun ComingSoonScreen(section: Section, vm: MainViewModel, onMenu: () -> Unit) {
    val plan = planFor(section)
    SectionPage(title = section.title, onBack = { vm.back() }, onMenu = onMenu) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(section.availability.label, availabilityColor(section.availability))
        }
        Text(text = section.summary, style = MaterialTheme.typography.bodyLarge)
        InfoCard {
            Text(text = "Cosa farà", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Bullets(plan.what)
        }
        InfoCard {
            Text(text = "A che punto è", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(text = plan.missing, style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = { vm.openSection(Section.HOME) }, modifier = Modifier.fillMaxWidth()) {
            Text("TORNA ALLA DASHBOARD")
        }
    }
}

// ---------------------------------------------------------------------------------------------------- Java Edition

/** Quello che l'app può dire davvero su Java Edition: il telefono, la memoria consigliata e le versioni di Java. */
@Composable
fun JavaEditionScreen(bridge: BridgeState, vm: MainViewModel, onMenu: () -> Unit) {
    SectionPage(title = Section.JAVA.title, onBack = { vm.back() }, onMenu = onMenu) {
        InfoCard {
            ToneRow(
                Tone.WARN,
                "Java Edition non si può ancora avviare da questa app",
                "Nessun runtime Java è integrato. Servirebbero un Java per ARM64, un livello grafico che traduca OpenGL e " +
                    "il tuo account Minecraft Java: finché non sono presenti e verificati su un telefono vero, " +
                    "l'app non promette di far girare il gioco."
            )
        }

        SectionLabel("Il tuo telefono")
        val device = bridge.device
        InfoCard {
            if (device == null) {
                Text("Dati del telefono non disponibili.", style = MaterialTheme.typography.bodyMedium)
            } else {
                for (line in DeviceAssessment.forJava(device)) {
                    ToneRow(if (line.ok) Tone.OK else Tone.WARN, line.text)
                }
            }
        }

        SectionLabel("Memoria consigliata")
        InfoCard {
            val plan = bridge.memoryPlan
            if (plan == null) {
                Text("Memoria del telefono non rilevata.", style = MaterialTheme.typography.bodyMedium)
            } else {
                StatRow("Profilo", bridge.settings.profile.label)
                StatRow("Memoria per il gioco", plan.memoryMb.toString() + " MB")
                Text(
                    text = plan.note + ". Il profilo si cambia in IMPOSTAZIONI; non viene mai usata tutta la memoria del telefono.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionLabel("Versione di Java richiesta")
        InfoCard {
            for ((minecraft, java) in JavaRequirements.TABLE) {
                StatRow(minecraft, "Java $java")
            }
        }

        OutlinedButton(onClick = { vm.openSection(Section.SETTINGS) }, modifier = Modifier.fillMaxWidth()) {
            Text("CAMBIA PROFILO PRESTAZIONI")
        }
    }
}
