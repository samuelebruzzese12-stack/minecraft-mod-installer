package com.mmi.modinstaller.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mmi.modinstaller.Availability
import com.mmi.modinstaller.BridgeViewModel
import com.mmi.modinstaller.DownloadsViewModel
import com.mmi.modinstaller.BusyState
import com.mmi.modinstaller.MainViewModel
import com.mmi.modinstaller.PickPurpose
import com.mmi.modinstaller.Screen
import com.mmi.modinstaller.Section
import kotlinx.coroutines.launch

/** Radice dell'interfaccia: menu delle sezioni, schermata aperta, messaggi, finestre e avanzamento. */
@Composable
fun MmiApp(vm: MainViewModel, model: BridgeViewModel, downloadsModel: DownloadsViewModel) {
    val state by vm.state.collectAsState()
    val bridge by model.state.collectAsState()
    val downloads by downloadsModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var pickPurpose by remember { mutableStateOf(PickPurpose.ANY) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Selettore di file di Android (Storage Access Framework). "*/*" perché molti gestori di file
    // non associano un tipo ai .mcpack/.mcaddon/.mcworld.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.openFiles(uris, pickPurpose)
    }

    val notice = state.notice
    LaunchedEffect(notice?.id) {
        if (notice != null) {
            val result = snackbar.showSnackbar(
                message = notice.text,
                actionLabel = notice.actionLabel,
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) vm.noticeAction(notice.id)
            vm.noticeShown(notice.id)
        }
    }

    // I dati del telefono, lo spazio e gli errori si rileggono ogni volta che si apre una sezione che li mostra.
    LaunchedEffect(state.section) {
        if (state.section != Section.INSTALLER) model.refresh()
    }

    val openMenu: () -> Unit = { scope.launch { drawerState.open() } }

    BackHandler(enabled = state.section != Section.HOME || state.busy != null || state.dialog != null) {
        vm.back()
    }
    // Dichiarato dopo l'altro: con il menu aperto, «indietro» chiude prima il menu.
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Il menu si apre dal pulsante; il gesto di trascinamento serve solo a chiuderlo.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                // Scorrevole: con lo schermo in orizzontale o diviso, le 11 voci non entrano tutte.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "Minecraft Mod Installer",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp)
                    )
                    for (section in Section.values()) {
                        NavigationDrawerItem(
                            label = { Text(section.title) },
                            selected = state.section == section,
                            onClick = {
                                scope.launch { drawerState.close() }
                                vm.openSection(section)
                            },
                            badge = {
                                if (section.availability != Availability.READY) {
                                    Text(section.availability.label, style = MaterialTheme.typography.labelSmall)
                                }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }
                }
            }
        }
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                containerColor = MaterialTheme.colorScheme.background
            ) { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner)
                        .consumeWindowInsets(inner)
                        .imePadding()
                ) {
                    when (state.section) {
                        Section.HOME -> DashboardScreen(state, bridge, vm, openMenu)
                        Section.INSTALLER -> InstallerContent(
                            state = state,
                            vm = vm,
                            onMenu = openMenu,
                            onPick = { purpose ->
                                pickPurpose = purpose
                                picker.launch(arrayOf("*/*"))
                            }
                        )
                        Section.JAVA -> JavaEditionScreen(bridge, vm, openMenu)
                        Section.DOWNLOADS -> DownloadsScreen(downloads, downloadsModel, vm, openMenu)
                        Section.DIAGNOSTICS -> DiagnosticsScreen(state, bridge, vm, model, openMenu)
                        Section.SETTINGS -> SettingsScreen(state, bridge, vm, model, openMenu)
                        else -> ComingSoonScreen(state.section, vm, openMenu)
                    }
                    val busy = state.busy
                    if (busy != null) BusyOverlay(busy) { vm.cancelBusy() }
                }
            }
        }
    }

    AppDialogs(state, vm)
}

/** Le schermate dell'INSTALLER BEDROCK, invariate: home, rapporto, recenti, installati, backup, cronologia, informazioni. */
@Composable
private fun InstallerContent(
    state: com.mmi.modinstaller.UiState,
    vm: MainViewModel,
    onMenu: () -> Unit,
    onPick: (PickPurpose) -> Unit
) {
    when (state.screen) {
        Screen.HOME -> HomeScreen(state = state, vm = vm, onMenu = onMenu, onPick = onPick)
        Screen.REPORT -> ReportScreen(state, vm)
        Screen.RECENT -> RecentScreen(state, vm)
        Screen.INSTALLED -> InstalledScreen(state, vm)
        Screen.BACKUPS -> BackupsScreen(state, vm)
        Screen.HISTORY -> HistoryScreen(state, vm)
        Screen.ABOUT -> AboutScreen(state, vm)
    }
}

/** Copre lo schermo durante un'operazione lunga: blocca i tocchi e offre il pulsante Annulla. */
@Composable
private fun BusyOverlay(busy: BusyState, onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            // Assorbe i tocchi, così non arrivano ai pulsanti sotto.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = busy.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                val fraction = busy.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(text = (fraction * 100).toInt().toString() + "%", style = MaterialTheme.typography.labelLarge)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (busy.note.isNotBlank()) {
                    Text(
                        text = busy.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(onClick = onCancel) { Text("Annulla") }
            }
        }
    }
}
