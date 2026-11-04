package com.mmi.modinstaller.data

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.mmi.modinstaller.core.LaunchAttempt
import com.mmi.modinstaller.core.LaunchMode
import com.mmi.modinstaller.core.LaunchPlanner
import com.mmi.modinstaller.core.McVersion
import com.mmi.modinstaller.core.MinecraftInfo
import java.io.File

/** Un'app Minecraft Bedrock installata sul telefono. */
class MinecraftApp(val packageName: String, val label: String, val versionName: String?)

sealed class SendResult {
    /** Android ha aperto una schermata per il file: NON significa che Minecraft l'abbia già importato. */
    class Started(val attempt: LaunchAttempt) : SendResult()

    object NotInstalled : SendResult()

    class Failed(val message: String) : SendResult()
}

/**
 * Consegna i file a Minecraft con il metodo ufficiale: un Intent ACTION_VIEW con un URI di contenuto
 * (FileProvider) e il permesso temporaneo di lettura. Minecraft apre il file e avvia da solo l'importazione.
 * Android non restituisce all'app alcun risultato di questa operazione.
 */
class MinecraftBridge(private val context: Context, private val authority: String) {

    fun installedApps(): List<MinecraftApp> {
        val result = ArrayList<MinecraftApp>()
        val pm = context.packageManager
        for (pkg in LaunchPlanner.MINECRAFT_PACKAGES) {
            try {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkg, 0)
                result.add(MinecraftApp(pkg, labelFor(pkg), info.versionName))
            } catch (e: PackageManager.NameNotFoundException) {
                // non installato
            } catch (e: Exception) {
                // pacchetto non leggibile: si ignora
            }
        }
        return result
    }

    fun isInstalled(): Boolean = installedApps().isNotEmpty()

    /** Versione dell'app Minecraft preferita (rilascio, poi Preview, poi Trial), per il controllo di compatibilità. */
    fun primaryInfo(): MinecraftInfo? {
        val app = installedApps().firstOrNull() ?: return null
        return MinecraftInfo(app.packageName, app.versionName, McVersion.parse(app.versionName))
    }

    /** Invia [file] (che deve stare nella cartella dei pacchetti preparati) a Minecraft. */
    fun send(file: File): SendResult {
        val apps = installedApps()
        if (apps.isEmpty()) return SendResult.NotInstalled
        val uri: Uri = try {
            FileProvider.getUriForFile(context, authority, file)
        } catch (e: IllegalArgumentException) {
            return SendResult.Failed("Il file non si trova nella cartella condivisibile dell'app")
        }
        val packages = apps.map { it.packageName }
        val attempts = LaunchPlanner.plan(packages) { pkg -> launcherClass(pkg) }
        var lastError = "Nessuna app ha accettato il file"
        for (attempt in attempts) {
            try {
                val pkg = attempt.packageName
                if (pkg != null) {
                    try {
                        context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: Exception) {
                        // il permesso viene comunque concesso dal flag dell'Intent
                    }
                }
                context.startActivity(buildIntent(uri, attempt))
                return SendResult.Started(attempt)
            } catch (e: ActivityNotFoundException) {
                lastError = "Minecraft non ha accettato il file (${attempt.mode.name.lowercase()})"
            } catch (e: SecurityException) {
                lastError = "Android ha negato l'apertura di Minecraft (${attempt.mode.name.lowercase()})"
            } catch (e: Exception) {
                lastError = e.message ?: lastError
            }
        }
        return SendResult.Failed(lastError)
    }

    /** Revoca i permessi di lettura concessi per [file] (da chiamare quando il file viene eliminato). */
    fun revoke(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, authority, file)
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // niente da revocare
        }
    }

    fun openMinecraft(): Boolean {
        val app = installedApps().firstOrNull() ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openStore(): Boolean {
        val targets = listOf(
            "market://details?id=com.mojang.minecraftpe",
            "https://play.google.com/store/apps/details?id=com.mojang.minecraftpe"
        )
        for (target in targets) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                // si prova l'indirizzo successivo
            }
        }
        return false
    }

    private fun launcherClass(pkg: String): String? =
        try {
            context.packageManager.getLaunchIntentForPackage(pkg)?.component?.className
        } catch (e: Exception) {
            null
        }

    private fun buildIntent(uri: Uri, attempt: LaunchAttempt): Intent {
        val view = Intent(Intent.ACTION_VIEW)
        view.setDataAndType(uri, attempt.mimeType)
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        view.clipData = ClipData.newRawUri("pacchetto", uri)
        val pkg = attempt.packageName
        val cls = attempt.className
        when (attempt.mode) {
            LaunchMode.EXPLICIT_COMPONENT -> {
                if (pkg != null && cls != null) view.component = ComponentName(pkg, cls)
            }
            LaunchMode.PACKAGE_ONLY -> {
                if (pkg != null) view.setPackage(pkg)
            }
            LaunchMode.CHOOSER -> Unit
        }
        if (attempt.mode == LaunchMode.CHOOSER) {
            val chooser = Intent.createChooser(view, "Apri il file con Minecraft")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return chooser
        }
        return view
    }

    private fun labelFor(pkg: String): String = when (pkg) {
        "com.mojang.minecraftpe" -> "Minecraft"
        "com.mojang.minecraftpe.preview" -> "Minecraft Preview"
        "com.mojang.minecrafttrialpe" -> "Minecraft (versione di prova)"
        else -> pkg
    }
}
