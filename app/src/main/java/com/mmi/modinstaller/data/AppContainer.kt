package com.mmi.modinstaller.data

import android.content.Context
import com.mmi.modinstaller.BuildConfig
import com.mmi.modinstaller.catalog.CatalogSource
import com.mmi.modinstaller.catalog.ModrinthApi
import com.mmi.modinstaller.catalog.UrlConnectionHttp
import com.mmi.modinstaller.core.ErrorLog
import com.mmi.modinstaller.core.LibraryStore
import com.mmi.modinstaller.core.SettingsRepository
import com.mmi.modinstaller.download.DownloadEngine
import com.mmi.modinstaller.download.DownloadOptions
import com.mmi.modinstaller.download.UrlPolicy
import com.mmi.modinstaller.mods.ModManager
import com.mmi.modinstaller.mods.ProfileRepository
import java.io.File

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

    /** Profili Java e file al loro interno (MOD MANAGER). Usa la sezione `java_profiles` dell'archivio centrale. */
    val modManager: ModManager by lazy { ModManager(ProfileRepository(store), paths.javaProfiles) }

    /**
     * Catalogo online (Modrinth, API pubblica v2). Parla solo con api.modrinth.com, solo con https e solo con richieste di
     * lettura; non invia nulla dell'utente oltre a ciò che cerca. I file non li scarica lui: lo fa [downloads].
     * Nella build di prova (debug) l'indirizzo del servizio si può sostituire con quello di un server locale scritto in
     * `files/dev/catalog_base.txt`: serve alla prova automatica su emulatore e nella build vera non esiste.
     */
    val catalog: CatalogSource by lazy {
        val testServers = if (BuildConfig.DEBUG) setOf("10.0.2.2:8765") else emptySet()
        val base = if (BuildConfig.DEBUG) debugCatalogBase() else null
        ModrinthApi(
            UrlConnectionHttp(userAgent = "MinecraftModInstaller/" + BuildConfig.VERSION_NAME, insecureHosts = testServers),
            base ?: ModrinthApi.DEFAULT_BASE_URL
        )
    }

    private fun debugCatalogBase(): String? = try {
        File(appContext.filesDir, "dev/catalog_base.txt").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.startsWith("http") }
    } catch (e: Exception) {
        null
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
