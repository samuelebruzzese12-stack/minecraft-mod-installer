package com.mmi.modinstaller

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import com.mmi.modinstaller.ui.MmiApp
import com.mmi.modinstaller.ui.MmiTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private val bridge: BridgeViewModel by viewModels()
    private val downloads: DownloadsViewModel by viewModels()
    private val mods: ModsViewModel by viewModels()
    private val catalog: CatalogViewModel by viewModels()
    private val modpacks: ModpackViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val bridgeState by bridge.state.collectAsState()
            MmiTheme(bridgeState.settings.theme) {
                MmiApp(vm, bridge, downloads, mods, catalog, modpacks)
            }
        }
        // Dopo una ricreazione (es. processo chiuso da Android) l'intent originale non va rielaborato.
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.onAppResumed()
        mods.onAppResumed()
    }

    override fun onPause() {
        vm.onAppPaused()
        super.onPause()
    }

    /** File aperti o condivisi con l'app da un gestore di file, dal browser o da un'altra app. */
    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        val uris = ArrayList<Uri>()
        when (action) {
            Intent.ACTION_VIEW -> intent.data?.let { uris.add(it) }
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (stream != null) uris.add(stream)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val streams = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (streams != null) uris.addAll(streams)
            }
        }
        if (uris.isEmpty()) {
            val clip = intent.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    val uri = clip.getItemAt(i).uri
                    if (uri != null) uris.add(uri)
                }
            }
        }

        // L'intent è stato consumato: se l'activity viene ricreata non deve essere riaperto lo stesso file.
        intent.action = null

        if (uris.isEmpty()) {
            vm.showMessage("Quello che hai condiviso non è un file: scegli un file Minecraft con «+ SELEZIONA FILE».")
            return
        }
        // I modpack Modrinth (.mrpack) vanno al MODPACK MANAGER, gli altri file Java (.jar) al MOD MANAGER; tutto il resto
        // all'INSTALLER BEDROCK, come prima.
        modpacks.routeIncoming(
            uris,
            openSection = { vm.openSection(Section.MODPACKS) },
            others = { rest ->
                mods.routeIncoming(rest, openMods = { vm.openSection(Section.MODS) }, others = { vm.openFiles(it, PickPurpose.ANY) })
            }
        )
    }
}
