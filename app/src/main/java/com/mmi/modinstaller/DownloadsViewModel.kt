package com.mmi.modinstaller

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mmi.modinstaller.data.AppContainer
import com.mmi.modinstaller.download.DownloadEngine
import com.mmi.modinstaller.download.DownloadRecord
import com.mmi.modinstaller.download.DownloadRequest
import com.mmi.modinstaller.download.DownloadState
import com.mmi.modinstaller.download.EnqueueResult
import com.mmi.modinstaller.download.ExpectedHash
import com.mmi.modinstaller.download.UrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Domanda all'utente: consentire i download da un sito sicuro ma che non conosce ancora?
 * [resumeId] = download già in coda che il server ha reindirizzato lì (da riprendere dopo il consenso).
 */
class ConsentRequest(val host: String, val url: String?, val expectedHash: ExpectedHash?, val resumeId: String?)

data class DownloadsState(
    val items: List<DownloadRecord> = emptyList(),
    val inputError: String? = null,
    /** Sale quando un indirizzo è stato accettato: la schermata svuota i campi (che tengono il testo per conto loro). */
    val resetCount: Int = 0,
    val consent: ConsentRequest? = null,
    val approvedHosts: List<String> = emptyList(),
    /** Esito dell'ultima azione (es. «Copia salvata»), mostrato sotto l'elenco. */
    val message: String? = null
)

/**
 * Stato e azioni del DOWNLOAD MANAGER. Il lavoro vero lo fa [DownloadEngine] (lo stesso per tutta l'app); qui ci sono i
 * campi da compilare, il consenso per i nuovi siti e il passaggio dei file scaricati alle altre sezioni.
 */
class DownloadsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)
    private val engine: DownloadEngine = container.downloads
    private val _state = MutableStateFlow(
        DownloadsState(items = sorted(engine.records()), approvedHosts = hosts())
    )
    val state: StateFlow<DownloadsState> = _state.asStateFlow()

    private val stopListening: () -> Unit = engine.addListener { list ->
        _state.update { it.copy(items = sorted(list)) }
    }

    override fun onCleared() {
        stopListening()
        super.onCleared()
    }

    // ------------------------------------------------------------------ campi

    /** Chi sta scrivendo ha corretto qualcosa: l'errore precedente non serve più. */
    fun clearInputError() {
        if (_state.value.inputError != null || _state.value.message != null) {
            _state.update { it.copy(inputError = null, message = null) }
        }
    }

    /** Mette in coda l'indirizzo scritto. Un sito non ancora consentito apre la domanda di consenso. */
    fun add(urlText: String, hashInput: String) {
        val url = urlText.trim()
        val hashText = hashInput.trim()
        val hash = if (hashText.isEmpty()) null else ExpectedHash.parse(hashText)
        if (hashText.isNotEmpty() && hash == null) {
            _state.update { it.copy(inputError = "L'impronta non è valida: servono 40, 64 o 128 caratteri esadecimali (SHA-1, SHA-256, SHA-512).") }
            return
        }
        enqueue(url, hash)
    }

    private fun enqueue(url: String, hash: ExpectedHash?) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { engine.enqueue(DownloadRequest(url = url, expectedHash = hash)) }
            when (result) {
                is EnqueueResult.Added -> _state.update {
                    it.copy(resetCount = it.resetCount + 1, inputError = null, message = "Aggiunto alla coda: «" + result.record.fileName + "».")
                }
                is EnqueueResult.Rejected -> {
                    val host = result.hostToApprove
                    if (host != null) {
                        _state.update { it.copy(consent = ConsentRequest(host, url, hash, null), inputError = null) }
                    } else {
                        _state.update { it.copy(inputError = result.message) }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ consenso per i nuovi siti

    /** Un download fermo perché il server lo ha mandato su un sito non consentito: chiede il consenso per riprenderlo. */
    fun askConsentFor(record: DownloadRecord) {
        val host = record.pendingHost ?: return
        _state.update { it.copy(consent = ConsentRequest(host, null, null, record.id)) }
    }

    fun confirmConsent() {
        val request = _state.value.consent ?: return
        _state.update { it.copy(consent = null) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { engine.approveHost(request.host) }
            if (!ok) {
                _state.update { it.copy(inputError = "Il nome del sito «" + request.host + "» non è valido.") }
                return@launch
            }
            _state.update { it.copy(approvedHosts = hosts()) }
            if (request.resumeId != null) {
                withContext(Dispatchers.IO) { engine.resume(request.resumeId) }
            } else if (request.url != null) {
                enqueue(request.url, request.expectedHash)
            }
        }
    }

    fun dismissConsent() = _state.update { it.copy(consent = null) }

    // ------------------------------------------------------------------ azioni sui download

    fun pause(id: String) = inBackground { engine.pause(id) }

    fun resume(id: String) = inBackground { engine.resume(id) }

    fun cancel(id: String) = inBackground { engine.cancel(id) }

    /** Toglie dall'elenco e cancella il file. Mentre scarica non si può: prima va annullato. */
    fun remove(record: DownloadRecord) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { engine.remove(record.id) }
            _state.update {
                it.copy(message = if (removed) "«" + record.fileName + "» eliminato." else "Annulla prima il download in corso.")
            }
        }
    }

    private fun inBackground(action: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { action() }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    // ------------------------------------------------------------------ file scaricati

    /** Indirizzo (content://) con cui l'analisi dell'INSTALLER BEDROCK legge il file scaricato. */
    fun contentUri(record: DownloadRecord): Uri? {
        val file = engine.file(record)
        if (record.state != DownloadState.COMPLETED || !file.isFile) return null
        val app = getApplication<Application>()
        return FileProvider.getUriForFile(app, app.packageName + ".fileprovider", file)
    }

    /** Copia il file scaricato dove l'utente ha scelto (selettore di sistema). */
    fun saveCopy(record: DownloadRecord, target: Uri) {
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val source = engine.file(record)
                    val out = getApplication<Application>().contentResolver.openOutputStream(target)
                        ?: return@withContext "Non riesco a scrivere nel punto scelto."
                    out.use { o -> source.inputStream().use { it.copyTo(o) } }
                    "Copia di «" + record.fileName + "» salvata."
                } catch (e: Exception) {
                    container.errors.record("Salvataggio di una copia", e.message ?: e.javaClass.simpleName, e.stackTraceToString())
                    "Non sono riuscito a salvare la copia: " + (e.message ?: e.javaClass.simpleName)
                }
            }
            _state.update { it.copy(message = message) }
        }
    }

    private fun hosts(): List<String> = DownloadEngine.approvedHosts(container.store).sorted()

    companion object {
        private fun sorted(list: List<DownloadRecord>) = list.sortedByDescending { it.createdAt }

        /** Estensioni che l'INSTALLER BEDROCK sa aprire. Il resto (jar, mrpack…) è Java e non è per Bedrock. */
        private val BEDROCK = setOf("mcpack", "mcaddon", "mcworld", "mctemplate", "zip", "rar", "7z")

        /** Il file si può passare all'INSTALLER BEDROCK per l'analisi. */
        fun isBedrockCandidate(fileName: String): Boolean =
            fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) in BEDROCK

        /** Estensioni che il MOD MANAGER sa leggere: mod (.jar) e raccolte o pacchetti (.zip). */
        private val JAVA = setOf("jar", "zip")

        /** Il file si può aggiungere al MOD MANAGER. */
        fun isJavaCandidate(fileName: String): Boolean =
            fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) in JAVA

        /** Modpack Modrinth (.mrpack): si apre nel MODPACK MANAGER. */
        fun isModpack(fileName: String): Boolean =
            fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) == "mrpack"

        /** Siti consentiti in partenza (elenco fisso), da mostrare all'utente. */
        val defaultHosts: List<String> = UrlPolicy.DEFAULT_HOSTS
    }
}
