package com.mmi.modinstaller.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipFile

enum class IssueLevel { WARNING, PROBLEM }

class IntegrityIssue(val level: IssueLevel, val title: String, val detail: String)

class IntegrityReport(val checked: Int, val issues: List<IntegrityIssue>) {
    val problems: Int get() = issues.count { it.level == IssueLevel.PROBLEM }
    val warnings: Int get() = issues.count { it.level == IssueLevel.WARNING }
    val ok: Boolean get() = issues.isEmpty()
}

/**
 * Controlla che i file preparati dall'app esistano ancora, abbiano la dimensione registrata e siano archivi leggibili,
 * e segnala i file che nessuna voce dell'elenco usa più. Non modifica nulla.
 */
object IntegrityChecker {

    fun check(installed: List<InstalledEntry>, preparedDir: File): IntegrityReport {
        val issues = ArrayList<IntegrityIssue>()
        val known = HashSet<String>()
        for (entry in installed) {
            known.add(entry.fileName)
            val file = File(preparedDir, entry.fileName)
            if (entry.fileName.isEmpty() || !file.isFile) {
                issues.add(
                    IntegrityIssue(
                        IssueLevel.PROBLEM, "«${entry.title}»: file preparato mancante",
                        "Non si può più inviare a Minecraft da qui: scegli di nuovo il file originale."
                    )
                )
                continue
            }
            if (file.length() != entry.sizeBytes) {
                issues.add(
                    IntegrityIssue(
                        IssueLevel.PROBLEM, "«${entry.title}»: dimensione diversa dal registro",
                        "Registrati ${formatBytes(entry.sizeBytes)}, trovati ${formatBytes(file.length())}: il file è stato alterato o troncato."
                    )
                )
                continue
            }
            if (!readableZip(file)) {
                issues.add(
                    IntegrityIssue(
                        IssueLevel.PROBLEM, "«${entry.title}»: archivio non leggibile",
                        "Il file preparato è danneggiato: scegli di nuovo il file originale."
                    )
                )
            }
        }
        for (file in preparedDir.listFiles().orEmpty()) {
            if (!file.isFile || file.name in known) continue
            if (file.name.startsWith(".") && file.name.endsWith(".part")) {
                issues.add(
                    IntegrityIssue(
                        IssueLevel.WARNING, "File temporaneo incompleto: ${file.name}",
                        "Resto di una preparazione interrotta (${formatBytes(file.length())}). Viene eliminato al prossimo avvio."
                    )
                )
            } else {
                issues.add(
                    IntegrityIssue(
                        IssueLevel.WARNING, "File non presente nell'elenco: ${file.name}",
                        "Occupa ${formatBytes(file.length())} e nessun contenuto gestito lo usa."
                    )
                )
            }
        }
        return IntegrityReport(installed.size, issues)
    }

    private fun readableZip(file: File): Boolean = try {
        ZipFile(file).use { it.size() >= 0 }
    } catch (e: Exception) {
        false
    }
}

/** Spazio occupato da file e cartelle. */
object FileSizes {

    /** Somma delle dimensioni dei file sotto [file] (o la sua, se è un file). I collegamenti simbolici non vengono seguiti. */
    fun of(file: File): Long {
        if (!file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath())) return 0L
        if (file.isFile) return file.length()
        var total = 0L
        for (child in file.listFiles().orEmpty()) total += of(child)
        return total
    }
}

/** Fotografia del telefono, riempita dall'app con le API di Android. Qui solo dati, nessuna dipendenza da Android. */
data class DeviceSnapshot(
    val model: String,
    val androidRelease: String,
    val sdk: Int,
    val abis: List<String>,
    val cpuCores: Int,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val lowMemory: Boolean,
    val storageTotalBytes: Long,
    val storageFreeBytes: Long,
    /** Temperatura della batteria in °C, se nota. */
    val batteryTempC: Float?,
    /** Stato termico di Android (0 = nessuno … 6 = emergenza), se noto (Android 10+). */
    val thermalStatus: Int?
) {
    val totalRamMb: Int get() = (totalRamBytes / (1024 * 1024)).toInt()
    val availableRamMb: Int get() = (availableRamBytes / (1024 * 1024)).toInt()
    val supportsArm64: Boolean get() = abis.any { it == "arm64-v8a" }
}

/** Giudizio sul telefono per Java Edition: solo fatti misurati, nessuna promessa. */
object DeviceAssessment {

    class Line(val ok: Boolean, val text: String)

    fun forJava(device: DeviceSnapshot): List<Line> {
        val lines = ArrayList<Line>()
        lines.add(
            if (device.supportsArm64) Line(true, "Processore a 64 bit ARM (arm64-v8a): richiesto dai runtime Java per Android")
            else Line(false, "Processore senza arm64-v8a (${device.abis.joinToString(", ").ifEmpty { "sconosciuto" }}): i runtime Java per Android non sono compatibili")
        )
        lines.add(
            when {
                device.totalRamMb >= 6000 -> Line(true, "Memoria: ${formatBytes(device.totalRamBytes)} — adatta anche a modpack medi")
                device.totalRamMb >= 4000 -> Line(true, "Memoria: ${formatBytes(device.totalRamBytes)} — sufficiente per Minecraft con poche mod")
                else -> Line(false, "Memoria: ${formatBytes(device.totalRamBytes)} — poca per Java Edition con mod")
            }
        )
        lines.add(
            if (device.sdk >= 26) Line(true, "Android ${device.androidRelease} (API ${device.sdk})")
            else Line(false, "Android ${device.androidRelease} (API ${device.sdk}): troppo vecchio")
        )
        val thermal = device.thermalStatus
        if (thermal != null && thermal >= 3) {
            lines.add(Line(false, "Il telefono è già molto caldo (stato termico $thermal): evita sessioni di gioco lunghe adesso"))
        }
        return lines
    }
}

/** Rapporto di diagnostica in testo semplice, da condividere. Non contiene nomi di file dell'utente né dati personali. */
object DiagnosticReport {

    class Input(
        val appVersion: String,
        val device: DeviceSnapshot?,
        val minecraft: List<String>,
        val counts: List<Pair<String, String>>,
        val appBytes: Long,
        val integrity: IntegrityReport?,
        val errors: List<ErrorRecord>,
        val memoryPlan: String?
    )

    fun build(input: Input, nowMillis: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        sb.append("Rapporto di diagnostica — Minecraft Mod Installer ").append(input.appVersion).append('\n')
        sb.append("Creato il ").append(fmt.format(Date(nowMillis))).append(" UTC\n\n")

        val d = input.device
        sb.append("[Dispositivo]\n")
        if (d == null) {
            sb.append("non rilevato\n")
        } else {
            sb.append("Modello: ").append(d.model).append('\n')
            sb.append("Android: ").append(d.androidRelease).append(" (API ").append(d.sdk).append(")\n")
            sb.append("Architetture: ").append(d.abis.joinToString(", ")).append('\n')
            sb.append("Core CPU: ").append(d.cpuCores).append('\n')
            sb.append("RAM: ").append(formatBytes(d.availableRamBytes)).append(" liberi su ").append(formatBytes(d.totalRamBytes))
            if (d.lowMemory) sb.append(" (memoria bassa)")
            sb.append('\n')
            sb.append("Spazio: ").append(formatBytes(d.storageFreeBytes)).append(" liberi su ").append(formatBytes(d.storageTotalBytes)).append('\n')
            d.batteryTempC?.let { sb.append("Temperatura batteria: ").append(String.format(Locale.ROOT, "%.1f", it)).append(" °C\n") }
            d.thermalStatus?.let { sb.append("Stato termico Android: ").append(it).append('\n') }
        }
        sb.append("Spazio usato dall'app: ").append(formatBytes(input.appBytes)).append("\n\n")

        sb.append("[Minecraft]\n")
        if (input.minecraft.isEmpty()) sb.append("Bedrock non trovato\n") else input.minecraft.forEach { sb.append(it).append('\n') }
        input.memoryPlan?.let { sb.append("Memoria consigliata per Java Edition: ").append(it).append('\n') }
        sb.append('\n')

        sb.append("[Contenuti]\n")
        for ((k, v) in input.counts) sb.append(k).append(": ").append(v).append('\n')
        sb.append('\n')

        sb.append("[Integrità dei file]\n")
        val integrity = input.integrity
        if (integrity == null) {
            sb.append("non controllata\n")
        } else if (integrity.ok) {
            sb.append("Nessun problema (").append(integrity.checked).append(" contenuti controllati)\n")
        } else {
            for (i in integrity.issues) {
                sb.append(if (i.level == IssueLevel.PROBLEM) "PROBLEMA: " else "Avviso: ").append(i.title).append(" — ").append(i.detail).append('\n')
            }
        }
        sb.append('\n')

        sb.append("[Ultimi errori]\n")
        if (input.errors.isEmpty()) sb.append("Nessun errore registrato\n")
        for (e in input.errors) {
            sb.append(fmt.format(Date(e.at))).append(" · ").append(e.where).append(": ").append(e.message).append('\n')
            if (!e.detail.isNullOrBlank()) sb.append("    ").append(e.detail.take(600).replace("\n", "\n    ")).append('\n')
        }
        return sb.toString()
    }
}
