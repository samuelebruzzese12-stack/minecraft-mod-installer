package com.mmi.modinstaller.core

/** Aspetto dell'app: «automatico» segue il tema del telefono. */
enum class ThemeMode(val label: String) {
    AUTO("Automatico"),
    LIGHT("Chiaro"),
    DARK("Scuro")
}

/** Profilo di prestazioni per Java Edition. Nessun profilo usa mai tutta la memoria del telefono. */
enum class PerformanceProfile(val label: String, val description: String) {
    SAVER("Risparmio energetico", "Meno memoria e meno calore: batteria che dura di più."),
    BALANCED("Bilanciato", "Un buon compromesso per la maggior parte delle mod."),
    PERFORMANCE("Prestazioni", "Più memoria per modpack pesanti, con più calore e consumo."),
    CUSTOM("Personalizzato", "Scegli tu la memoria (mai oltre la metà di quella del telefono).")
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.AUTO,
    val profile: PerformanceProfile = PerformanceProfile.BALANCED,
    /** Memoria scelta con il profilo «Personalizzato», in MB. */
    val customMemoryMb: Int = 2048
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "theme" to theme.name,
        "profile" to profile.name,
        "customMemoryMb" to customMemoryMb
    )

    companion object {
        const val SECTION = "settings"
        const val MIN_CUSTOM_MB = 512
        const val MAX_CUSTOM_MB = 16384

        /** Legge le impostazioni da un valore salvato; i valori mancanti o sconosciuti tornano al predefinito. */
        fun fromJson(value: Any?): AppSettings {
            val o = value.asJsonObject() ?: return AppSettings()
            val defaults = AppSettings()
            return AppSettings(
                theme = ThemeMode.values().firstOrNull { it.name == o.str("theme") } ?: defaults.theme,
                profile = PerformanceProfile.values().firstOrNull { it.name == o.str("profile") } ?: defaults.profile,
                customMemoryMb = (o.int("customMemoryMb") ?: defaults.customMemoryMb)
                    .coerceIn(MIN_CUSTOM_MB, MAX_CUSTOM_MB)
            )
        }
    }
}

/** Impostazioni nell'archivio centrale (sezione «settings»). */
class SettingsRepository(private val store: DocumentStore) {

    fun load(): AppSettings = AppSettings.fromJson(store.read(AppSettings.SECTION))

    /** Applica [change] alle impostazioni correnti e le salva; restituisce quelle risultanti. */
    fun update(change: (AppSettings) -> AppSettings): AppSettings {
        var result = AppSettings()
        store.update(AppSettings.SECTION) { current ->
            result = change(AppSettings.fromJson(current))
            result.toJson()
        }
        return result
    }
}

/**
 * Quanta memoria dare a un gioco Java in base al profilo e alla RAM del telefono. I valori sono prudenti:
 * Android ha bisogno di memoria per sé e per le altre app, e una chiusura improvvisa per memoria esaurita
 * costa più di qualche fotogramma in meno.
 */
object MemoryPlanner {

    class Plan(val memoryMb: Int, val note: String)

    /** [totalRamMb] = RAM del telefono; [availableRamMb] = RAM libera in questo momento (null se non nota). */
    fun plan(settings: AppSettings, totalRamMb: Int, availableRamMb: Int?): Plan {
        if (totalRamMb <= 0) return Plan(0, "Memoria del telefono non rilevata")
        val half = totalRamMb / 2
        val wanted = when (settings.profile) {
            PerformanceProfile.SAVER -> (totalRamMb * 0.20).toInt().coerceIn(1024, 2048)
            PerformanceProfile.BALANCED -> (totalRamMb * 0.30).toInt().coerceIn(1536, 3072)
            PerformanceProfile.PERFORMANCE -> (totalRamMb * 0.40).toInt().coerceIn(2048, 4096)
            PerformanceProfile.CUSTOM -> settings.customMemoryMb
        }
        var memory = minOf(wanted, half)
        var note = if (memory < wanted) "Ridotta alla metà della memoria del telefono" else "In linea con il profilo scelto"
        if (availableRamMb != null) {
            val ceiling = availableRamMb - SYSTEM_RESERVE_MB
            if (ceiling < MIN_GAME_MB) {
                memory = MIN_GAME_MB
                note = "Memoria libera molto bassa: chiudi le altre app prima di giocare"
            } else if (ceiling < memory) {
                memory = ceiling
                note = "Ridotta perché ora la memoria libera è poca"
            }
        }
        memory = maxOf(memory, MIN_GAME_MB)
        return Plan(memory / 64 * 64, note)
    }

    private const val SYSTEM_RESERVE_MB = 768
    private const val MIN_GAME_MB = 512
}

/** Quale versione di Java richiede una versione di Minecraft Java Edition. */
object JavaRequirements {

    /** Versione principale di Java (8, 16, 17, 21) richiesta da [minecraft]. */
    fun javaFor(minecraft: McVersion): Int = when {
        minecraft < McVersion(listOf(1, 17)) -> 8
        minecraft < McVersion(listOf(1, 18)) -> 16
        minecraft < McVersion(listOf(1, 20, 5)) -> 17
        else -> 21
    }

    /** Righe di spiegazione per la schermata Java Edition. */
    val TABLE: List<Pair<String, Int>> = listOf(
        "Minecraft fino a 1.16.5" to 8,
        "Minecraft 1.17.x" to 16,
        "Minecraft da 1.18 a 1.20.4" to 17,
        "Minecraft 1.20.5 e successive" to 21
    )
}
