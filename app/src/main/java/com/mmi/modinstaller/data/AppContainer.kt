package com.mmi.modinstaller.data

import android.content.Context
import com.mmi.modinstaller.core.ErrorLog
import com.mmi.modinstaller.core.LibraryStore
import com.mmi.modinstaller.core.SettingsRepository

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
