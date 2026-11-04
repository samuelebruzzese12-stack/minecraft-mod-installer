package com.mmi.modinstaller.download

import com.mmi.modinstaller.core.DocumentStore
import com.mmi.modinstaller.core.asJsonList
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/** Esito della richiesta di mettere un file in coda. */
sealed class EnqueueResult {
    class Added(val record: DownloadRecord) : EnqueueResult()

    /** Indirizzo non accettato, con il motivo da mostrare. [hostToApprove] = sito sicuro ma non ancora consentito. */
    class Rejected(val message: String, val hostToApprove: String? = null) : EnqueueResult()
}

/**
 * Coda dei download: più file in parallelo (pochi), ripresa dal punto raggiunto, verifica dell'impronta, annullamento.
 *
 * Regole che non ammettono eccezioni:
 * - si scarica solo da indirizzi che passano [UrlChecker], reindirizzamenti compresi;
 * - un file che non supera la verifica dell'impronta o dei limiti di dimensione non viene tenuto;
 * - il motore non invia mai nulla dell'utente: fa solo richieste GET;
 * - niente parte da solo all'avvio dell'app: i download interrotti restano «In pausa» finché l'utente non li riprende.
 *
 * I dati stanno nell'archivio centrale (sezione `downloads`); i file parziali sono `<id>.part` in [dir].
 */
class DownloadEngine(
    private val store: DocumentStore,
    private val dir: File,
    private val checker: UrlChecker,
    private val options: DownloadOptions = DownloadOptions(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private val lock = Any()
    private val records = LinkedHashMap<String, DownloadRecord>()
    private val controls = HashMap<String, Control>()
    private val listeners = CopyOnWriteArrayList<(List<DownloadRecord>) -> Unit>()
    private val transfer = Transfer(checker, options, dir)
    private val executor: ExecutorService = Executors.newFixedThreadPool(options.maxParallel.coerceAtLeast(1), DaemonThreads())
    private var lastNotify = 0L
    private var lastPersist = 0L

    init {
        dir.mkdirs()
        load()
    }

    // ------------------------------------------------------------------ lettura

    fun records(): List<DownloadRecord> = synchronized(lock) { records.values.toList() }

    fun record(id: String): DownloadRecord? = synchronized(lock) { records[id] }

    /**
     * Il listener riceve l'elenco aggiornato a ogni cambiamento, anche dal thread che scarica: deve essere veloce e non
     * bloccarsi (basta passare l'elenco all'interfaccia). Restituisce la funzione che lo toglie.
     */
    fun addListener(listener: (List<DownloadRecord>) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun approvedHosts(): Set<String> = approvedHosts(store)

    // ------------------------------------------------------------------ comandi

    fun enqueue(request: DownloadRequest): EnqueueResult {
        val url = request.url.trim()
        when (val verdict = checker.check(url)) {
            is UrlVerdict.Allowed -> {}
            is UrlVerdict.Rejected -> return EnqueueResult.Rejected(verdict.message)
            is UrlVerdict.HostNotAllowed -> return EnqueueResult.Rejected(
                "Il sito «${verdict.host}» non è tra quelli consentiti.", hostToApprove = verdict.host
            )
        }
        synchronized(lock) {
            if (records.values.any { it.url == url && !it.state.finished }) {
                return EnqueueResult.Rejected("Questo indirizzo è già nella lista dei download.")
            }
            val named = request.fileName?.let { DownloadNames.safe(it) }
            val (name, provisional) = if (named != null) Pair(named, false) else DownloadNames.fromUrl(url)
            val now = clock()
            val record = DownloadRecord(
                id = UUID.randomUUID().toString().replace("-", "").take(12),
                url = url,
                fileName = DownloadNames.unique(dir, name, records.values.map { it.fileName }.toSet()),
                nameProvisional = provisional,
                state = DownloadState.QUEUED,
                doneBytes = 0,
                totalBytes = -1,
                expectedHash = request.expectedHash,
                sha256 = null,
                etag = null,
                lastModified = null,
                error = null,
                pendingHost = null,
                note = request.note?.take(200),
                createdAt = now,
                updatedAt = now
            )
            records[record.id] = record
            changed(force = true)
            pump()
            return EnqueueResult.Added(record)
        }
    }

    /** Ferma un download senza perdere quanto già scaricato. */
    fun pause(id: String) {
        synchronized(lock) {
            val r = records[id] ?: return
            when (r.state) {
                DownloadState.QUEUED -> update(id) { it.copy(state = DownloadState.PAUSED, error = null) }
                DownloadState.RUNNING -> controls[id]?.let {
                    it.wanted = Control.Wanted.PAUSE
                    it.abort()
                }
                else -> {}
            }
        }
    }

    /** Rimette in coda un download in pausa o non riuscito; riparte da dove era arrivato. */
    fun resume(id: String) {
        synchronized(lock) {
            val r = records[id] ?: return
            if (r.state == DownloadState.PAUSED || r.state == DownloadState.FAILED || r.state == DownloadState.CANCELED) {
                update(id) { it.copy(state = DownloadState.QUEUED, error = null, pendingHost = null) }
                pump()
            }
        }
    }

    fun retry(id: String) = resume(id)

    /** Annulla: il file parziale viene cancellato. */
    fun cancel(id: String) {
        synchronized(lock) {
            val r = records[id] ?: return
            when (r.state) {
                DownloadState.RUNNING -> controls[id]?.let {
                    it.wanted = Control.Wanted.CANCEL
                    it.abort()
                }
                DownloadState.QUEUED, DownloadState.PAUSED, DownloadState.FAILED -> {
                    transfer.partFile(id).delete()
                    update(id) { it.copy(state = DownloadState.CANCELED, doneBytes = 0, error = null, pendingHost = null) }
                }
                else -> {}
            }
        }
    }

    /**
     * Toglie il download dall'elenco, cancellando anche il file (completo o parziale). Non si può togliere mentre
     * scarica: prima va annullato. Restituisce false in quel caso.
     */
    fun remove(id: String): Boolean {
        synchronized(lock) {
            val r = records[id] ?: return true
            if (r.state == DownloadState.RUNNING) return false
            transfer.partFile(id).delete()
            if (r.state == DownloadState.COMPLETED) file(r).delete()
            records.remove(id)
            changed(force = true)
            return true
        }
    }

    /** Il file completato (esiste solo per i download COMPLETED). */
    fun file(record: DownloadRecord): File = File(dir, record.fileName)

    /** L'utente consente i download da questo sito (nome senza schema e percorso). Restituisce false se non è un nome valido. */
    fun approveHost(host: String): Boolean {
        val normalized = UrlPolicy.normalizeHost(host) ?: return false
        store.update(HOSTS_SECTION) { current ->
            val list = ArrayList<Any?>()
            current.asJsonList()?.let { list.addAll(it) }
            if (list.none { it == normalized }) list.add(normalized)
            list
        }
        return true
    }

    /** Ferma tutto e chiude i thread. Non cancella nulla: i parziali restano per la ripresa. */
    fun shutdown() {
        synchronized(lock) {
            for ((_, c) in controls) {
                c.wanted = Control.Wanted.PAUSE
                c.abort()
            }
        }
        executor.shutdownNow()
    }

    // ------------------------------------------------------------------ esecuzione

    private fun pump() {
        synchronized(lock) {
            while (controls.size < options.maxParallel) {
                val next = records.values.firstOrNull { it.state == DownloadState.QUEUED && it.id !in controls } ?: break
                val control = Control()
                controls[next.id] = control
                update(next.id) { it.copy(state = DownloadState.RUNNING, error = null, pendingHost = null) }
                try {
                    executor.execute { work(next.id, control) }
                } catch (e: Exception) {
                    controls.remove(next.id)
                    update(next.id) { it.copy(state = DownloadState.PAUSED, error = "Il motore dei download è stato chiuso.") }
                    break
                }
            }
        }
    }

    private fun work(id: String, control: Control) {
        try {
            val listener = object : Transfer.Listener {
                override fun onMeta(totalBytes: Long, etag: String?, lastModified: String?) {
                    synchronized(lock) {
                        update(id) { it.copy(totalBytes = totalBytes, etag = etag, lastModified = lastModified, error = null) }
                    }
                }

                override fun onProgress(done: Long, total: Long) {
                    synchronized(lock) {
                        update(id, force = false) { it.copy(doneBytes = done, totalBytes = total) }
                    }
                }
            }
            var attempt = 0
            var outcome: Transfer.Outcome
            while (true) {
                val current = record(id) ?: return
                outcome = try {
                    transfer.run(current, control, listener)
                } catch (e: Exception) {
                    // Un errore dovuto a una pausa o a un annullamento appena chiesti non è un guasto.
                    when (control.wanted) {
                        Control.Wanted.CANCEL -> Transfer.Outcome.Canceled
                        Control.Wanted.PAUSE -> Transfer.Outcome.Paused
                        Control.Wanted.RUN ->
                            Transfer.Outcome.Failed("Errore imprevisto: " + (e.message ?: e.javaClass.simpleName), retryable = false)
                    }
                }
                val failed = outcome as? Transfer.Outcome.Failed
                if (failed != null && failed.retryable && attempt < options.retryDelaysMs.size && control.wanted == Control.Wanted.RUN) {
                    note(id, "Nuovo tentativo tra poco… (${failed.message})")
                    if (!sleepUnlessStopped(options.retryDelaysMs[attempt], control)) {
                        outcome = if (control.wanted == Control.Wanted.CANCEL) Transfer.Outcome.Canceled else Transfer.Outcome.Paused
                        break
                    }
                    attempt++
                    continue
                }
                break
            }
            finish(id, outcome)
        } catch (e: Throwable) {
            finish(id, Transfer.Outcome.Failed("Errore imprevisto: " + (e.message ?: e.javaClass.simpleName), retryable = false))
        } finally {
            synchronized(lock) { controls.remove(id) }
            pump()
        }
    }

    private fun note(id: String, text: String) {
        synchronized(lock) { update(id) { it.copy(error = text) } }
    }

    private fun sleepUnlessStopped(totalMs: Long, control: Control): Boolean {
        var left = totalMs
        while (left > 0) {
            if (control.wanted != Control.Wanted.RUN) return false
            val slice = minOf(left, 100L)
            try {
                Thread.sleep(slice)
            } catch (e: InterruptedException) {
                return false
            }
            left -= slice
        }
        return control.wanted == Control.Wanted.RUN
    }

    private fun finish(id: String, outcome: Transfer.Outcome) {
        synchronized(lock) {
            val r = records[id] ?: return
            val part = transfer.partFile(id)
            when (outcome) {
                is Transfer.Outcome.Completed -> {
                    val taken = records.values.filter { it.id != id }.map { it.fileName }.toSet()
                    var name = r.fileName
                    if (r.nameProvisional && outcome.suggestedName != null) {
                        name = DownloadNames.unique(dir, outcome.suggestedName, taken)
                    } else if (File(dir, name).exists()) {
                        name = DownloadNames.unique(dir, name, taken)
                    }
                    val target = File(dir, name)
                    val moved = try {
                        try {
                            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        } catch (e: Exception) {
                            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                        true
                    } catch (e: Exception) {
                        false
                    }
                    if (moved) {
                        update(id) {
                            it.copy(
                                state = DownloadState.COMPLETED, fileName = name, nameProvisional = false,
                                doneBytes = outcome.bytes, totalBytes = outcome.bytes, sha256 = outcome.sha256,
                                error = null, pendingHost = null
                            )
                        }
                    } else {
                        update(id) {
                            it.copy(state = DownloadState.FAILED, error = "Non riesco a salvare il file nella memoria dell'app.")
                        }
                    }
                }
                is Transfer.Outcome.Paused ->
                    update(id) { it.copy(state = DownloadState.PAUSED, error = null) }
                is Transfer.Outcome.Canceled -> {
                    part.delete()
                    update(id) {
                        it.copy(state = DownloadState.CANCELED, doneBytes = 0, error = null, pendingHost = null)
                    }
                }
                is Transfer.Outcome.NeedsHost ->
                    update(id) {
                        it.copy(
                            state = DownloadState.PAUSED, pendingHost = outcome.host,
                            error = "Il server ti manda a «${outcome.host}», un sito che non hai ancora consentito."
                        )
                    }
                is Transfer.Outcome.Failed -> {
                    if (outcome.deletePartial) part.delete()
                    update(id) {
                        it.copy(
                            state = DownloadState.FAILED, error = outcome.message,
                            doneBytes = if (outcome.deletePartial) 0 else it.doneBytes
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ stato e salvataggio

    /** Cambia un record. [force] = cambio di stato (notifica e salvataggio subito); altrimenti è solo avanzamento. */
    private fun update(id: String, force: Boolean = true, change: (DownloadRecord) -> DownloadRecord) {
        synchronized(lock) {
            val old = records[id] ?: return
            records[id] = change(old).copy(updatedAt = clock())
            changed(force)
        }
    }

    /** Notifica i listener (al massimo 5 volte al secondo salvo [force]) e salva (al massimo ogni 2 secondi salvo [force]). */
    private fun changed(force: Boolean) {
        val now = System.nanoTime() / 1_000_000L
        val doNotify = force || now - lastNotify >= 200
        val doSave = force || now - lastPersist >= 2_000
        if (doSave) {
            lastPersist = now
            store.write(SECTION, records.values.map { it.toMap() })
        }
        if (doNotify) {
            lastNotify = now
            val snapshot = records.values.toList()
            for (l in listeners) {
                try {
                    l(snapshot)
                } catch (e: Exception) {
                    // un ascoltatore che sbaglia non deve fermare i download
                }
            }
        }
    }

    private fun load() {
        val stored = store.read(SECTION).asJsonList().orEmpty().mapNotNull { DownloadRecord.fromValue(it) }
        val now = clock()
        for (r in stored) {
            // Un download rimasto «in corso» o «in coda» quando l'app si è chiusa non riparte da solo: l'utente decide.
            records[r.id] = if (r.state == DownloadState.RUNNING || r.state == DownloadState.QUEUED) {
                r.copy(state = DownloadState.PAUSED, error = "Interrotto quando l'app si è chiusa. Tocca RIPRENDI per continuare.", updatedAt = now)
            } else {
                r
            }
        }
        // File parziali senza più un download che li aspetta.
        val partFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".part") }.orEmpty()
        for (f in partFiles) {
            val id = f.name.removeSuffix(".part")
            val r = records[id]
            if (r == null || r.state == DownloadState.CANCELED || r.state == DownloadState.COMPLETED) f.delete()
        }
        if (stored.isNotEmpty()) store.write(SECTION, records.values.map { it.toMap() })
    }

    private class DaemonThreads : ThreadFactory {
        private var n = 0

        @Synchronized
        override fun newThread(r: Runnable): Thread {
            val t = Thread(r, "download-" + (++n))
            t.isDaemon = true
            return t
        }
    }

    companion object {
        /** Sezione dell'archivio centrale con l'elenco dei download. */
        const val SECTION = "downloads"

        /** Sezione con i siti che l'utente ha consentito. */
        const val HOSTS_SECTION = "download_hosts"

        /** Siti consentiti dall'utente, letti dall'archivio (la [UrlPolicy] li consulta a ogni controllo). */
        fun approvedHosts(store: DocumentStore): Set<String> =
            store.read(HOSTS_SECTION).asJsonList().orEmpty().mapNotNull { it as? String }.toSet()
    }
}
