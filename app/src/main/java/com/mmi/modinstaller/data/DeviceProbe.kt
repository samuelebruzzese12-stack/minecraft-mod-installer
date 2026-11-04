package com.mmi.modinstaller.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import com.mmi.modinstaller.core.DeviceSnapshot

/** Legge dal telefono i dati che servono a Diagnostica e Java Edition. Ogni valore non disponibile resta null o zero. */
class DeviceProbe(private val context: Context) {

    fun snapshot(): DeviceSnapshot {
        val memory = ActivityManager.MemoryInfo()
        try {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        } catch (e: Exception) {
            // memoria non rilevabile: i campi restano a zero
        }

        var storageTotal = 0L
        var storageFree = 0L
        try {
            val stat = StatFs(context.filesDir.absolutePath)
            storageTotal = stat.totalBytes
            storageFree = stat.availableBytes
        } catch (e: Exception) {
            // spazio non rilevabile
        }

        var batteryTemp: Float? = null
        try {
            // Con receiver null si legge l'ultimo valore già trasmesso, senza registrare nulla.
            val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (tenths != Int.MIN_VALUE) batteryTemp = tenths / 10f
        } catch (e: Exception) {
            // temperatura non rilevabile
        }

        var thermal: Int? = null
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                thermal = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
            } catch (e: Exception) {
                // stato termico non rilevabile
            }
        }

        val model = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
            .ifEmpty { "sconosciuto" }

        return DeviceSnapshot(
            model = model,
            androidRelease = Build.VERSION.RELEASE ?: "?",
            sdk = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            totalRamBytes = memory.totalMem,
            availableRamBytes = memory.availMem,
            lowMemory = memory.lowMemory,
            storageTotalBytes = storageTotal,
            storageFreeBytes = storageFree,
            batteryTempC = batteryTemp,
            thermalStatus = thermal
        )
    }
}
