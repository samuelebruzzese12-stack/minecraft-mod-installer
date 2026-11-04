package com.mmi.modinstaller.data

import android.content.Context
import com.mmi.modinstaller.BuildConfig
import com.mmi.modinstaller.core.ErrorLog
import com.mmi.modinstaller.core.LibraryStore
import com.mmi.modinstaller.core.SettingsRepository
import com.mmi.modinstaller.download.DownloadEngine
import com.mmi.modinstaller.download.DownloadOptions
import com.mmi.modinstaller.download.UrlPolicy

/**
 * Oggetti condivisi da tutte le sezioni dell'app: cartelle, archivio centrale, impostazioni e registro errori.
 *
 * C'è un solo archivio dati per tutta l'app ([store]): i motori nuovi (mod, modpack, mondi, download…) ne usano delle
 * sezioni, invece di creare ciascuno il proprio file o database. L'INSTALLER BEDROCK continua a usare le stesse
 * liste di prima (file recenti, contenuti gestiti, cronologia) nello stesso file.
 */
class AppContainer private constructor(context: Context) {

    val appContext: Context = context.applicationContext
    val paths: AppPaths = AppPaths(appContext).also { it.ensure() }
    val store: LibraryStore = LibraryStore(paths.libraryFile)
    val errors: ErrorLog = ErrorLog(store)
    val settings: SettingsRepository = SettingsRepository(store)
    val probe: DeviceProbe = DeviceProbe(appContext)

    /**
     * Motore dei download: vive quanto il processo, così un download continua anche se si cambia schermata. Parte (e
     * apre la rete) solo quando l'utente aggiunge o riprende un download. Nella build vera ammette solo https verso i
     * siti consentiti; la build di prova (debug) ammette in più l'HTTP verso il server locale di 10.0.2.2:8765, che serve
     * solo alla prova automatica su emulatore.
     */
    val downloads: DownloadEngine by lazy {
        val testServers = if (BuildConfig.DEBUG) setOf("10.0.2.2:8765") else emptySet()
        DownloadEngine(
            store = store,
            dir = paths.downloads,
            checker = UrlPolicy(userHosts = { DownloadEngine.approvedHosts(store) }, insecureHosts = testServers),
            options = DownloadOptions(userAgent = "MinecraftModInstaller/" + BuildConfig.VERSION_NAME)
        )
    }

    init {
        // Un arresto avvenuto nell'esecuzione precedente entra nel registro errori; da ora in poi si registrano i nuovi.
        CrashRecorder.importPending(paths.crashFile, errors)
        CrashRecorder.install(paths.crashFile)
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}
