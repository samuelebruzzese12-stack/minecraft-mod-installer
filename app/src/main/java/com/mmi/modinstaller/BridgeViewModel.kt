package com.mmi.modinstaller

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mmi.modinstaller.core.AppSettings
import com.mmi.modinstaller.core.DeviceSnapshot
import com.mmi.modinstaller.core.DiagnosticReport
import com.mmi.modinstaller.core.ErrorRecord
import com.mmi.modinstaller.core.FileSizes
import com.mmi.modinstaller.core.IntegrityChecker
import com.mmi.modinstaller.core.IntegrityReport
import com.mmi.modinstaller.core.MemoryPlanner
import com.mmi.modinstaller.core.PerformanceProfile
import com.mmi.modinstaller.core.ThemeMode
import com.mmi.modinstaller.data.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Una voce dello spazio occupato dall'app. */
data class SpaceItem(val label: String, val bytes: Long)

/** Dati delle sezioni che non riguardano l'installer: dashboard, Java Edition, Diagnostica, Impostazioni. */
data class BridgeState(
    val settings: AppSettings = AppSettings(),
    val device: DeviceSnapshot? = null,
    val memoryPlan: MemoryPlanner.Plan? = null,
    val space: List<SpaceItem> = emptyList(),
    val appBytes: Long = 0L,
    val integrity: IntegrityReport? = null,
    val checking: Boolean = false,
    val errors: List<ErrorRecord> = emptyList(),
    val errorCount: Int = 0
)

/**
 * Stato e azioni delle sezioni "di servizio". Usa gli stessi oggetti dell'installer ([AppContainer]): stesso archivio
 * dati, stesse cartelle, stesso registro errori. Non conosce l'installer e non ne tocca lo stato.
 */
class BridgeViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)
    private val _state = MutableStateFlow(BridgeState(settings = container.settings.load()))
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Rilegge telefono, spazio occupato ed errori. Il controllo dell'integrità parte solo su richiesta. */
    fun refresh() {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) { gather() }
            _state.update {
                it.copy(
                    device = data.device,
                    memoryPlan = plan(it.settings, data.device),
                    space = data.space,
                    appBytes = data.space.sumOf { s -> s.bytes },
                    errors = data.errors,
                    errorCount = data.errorCount
                )
            }
        }
    }

    private class Gathered(
        val device: DeviceSnapshot?,
        val space: List<SpaceItem>,
        val errors: List<ErrorRecord>,
        val errorCount: Int
    )

    private fun gather(): Gathered {
        val device = try {
            container.probe.snapshot()
        } catch (e: Exception) {
            null
        }
        val paths = container.paths
        val space = listOf(
            SpaceItem("Pacchetti pronti per Minecraft", FileSizes.of(paths.prepared)),
            SpaceItem("Backup", FileSizes.of(paths.backups)),
            SpaceItem("Elenchi e impostazioni", FileSizes.of(paths.libraryFile.parentFile ?: paths.libraryFile)),
            SpaceItem("File temporanei", FileSizes.of(paths.sessions))
        )
        val errors = container.errors.recent(MAX_ERRORS_SHOWN)
        return Gathered(device, space, errors, container.errors.count())
    }

    private fun plan(settings: AppSettings, device: DeviceSnapshot?): MemoryPlanner.Plan? {
        if (device == null || device.totalRamMb <= 0) return null
        return MemoryPlanner.plan(settings, device.totalRamMb, device.availableRamMb)
    }

    // ------------------------------------------------------------------ impostazioni

    fun setTheme(mode: ThemeMode) = updateSettings { it.copy(theme = mode) }

    fun setProfile(profile: PerformanceProfile) = updateSettings { it.copy(profile = profile) }

    fun setCustomMemory(megabytes: Int) {
        val value = megabytes.coerceIn(AppSettings.MIN_CUSTOM_MB, AppSettings.MAX_CUSTOM_MB)
        updateSettings { it.copy(customMemoryMb = value) }
    }

    private fun updateSettings(change: (AppSettings) -> AppSettings) {
        // L'interfaccia cambia subito; il salvataggio avviene in secondo piano, sempre con la stessa modifica.
        _state.update {
            val next = change(it.settings)
            it.copy(settings = next, memoryPlan = plan(next, it.device))
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                container.settings.update(change)
            } catch (e: Exception) {
                container.errors.record("Impostazioni", "Salvataggio non riuscito", e.stackTraceToString())
            }
        }
    }

    // ------------------------------------------------------------------ diagnostica

    fun checkIntegrity() {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true) }
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                try {
                    IntegrityChecker.check(container.store.installed(), container.paths.prepared)
                } catch (e: Exception) {
                    container.errors.record("Controllo dei file", "Controllo non riuscito", e.stackTraceToString())
                    null
                }
            }
            _state.update { it.copy(integrity = report, checking = false) }
            refresh()
        }
    }

    fun clearErrors() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.errors.clear() }
            _state.update { it.copy(errors = emptyList(), errorCount = 0) }
        }
    }

    /** Elimina le copie temporanee dei file scelti. [onDone] riceve lo spazio liberato. */
    fun clearTemporaryFiles(onDone: (freedBytes: Long) -> Unit) {
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) {
                val before = FileSizes.of(container.paths.sessions)
                container.paths.clearSessions()
                before
            }
            refresh()
            onDone(freed)
        }
    }

    /** Il rapporto di diagnostica in testo semplice, da condividere. */
    fun reportText(minecraft: List<String>, counts: List<Pair<String, String>>): String {
        val s = _state.value
        return DiagnosticReport.build(
            DiagnosticReport.Input(
                appVersion = BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")",
                device = s.device,
                minecraft = minecraft,
                counts = counts,
                appBytes = s.appBytes,
                integrity = s.integrity,
                errors = container.errors.recent(MAX_ERRORS_SHOWN),
                memoryPlan = s.memoryPlan?.let { it.memoryMb.toString() + " MB (" + s.settings.profile.label + ")" }
            )
        )
    }

    companion object {
        const val MAX_ERRORS_SHOWN = 30
    }
}
