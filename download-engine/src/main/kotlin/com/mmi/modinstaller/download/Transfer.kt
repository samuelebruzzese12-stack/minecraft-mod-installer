package com.mmi.modinstaller.download

import com.mmi.modinstaller.core.formatBytes
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/** Cosa l'utente (o il motore) ha chiesto alla trasmissione in corso. */
internal class Control {
    enum class Wanted { RUN, PAUSE, CANCEL }

    @Volatile
    var wanted: Wanted = Wanted.RUN

    @Volatile
    var connection: HttpURLConnection? = null

    /** Chiude la connessione: una lettura bloccata sul telefono finisce subito con un errore e si può fermare. */
    fun abort() {
        try {
            connection?.disconnect()
        } catch (e: Exception) {
            // già chiusa
        }
    }
}

/** Impostazioni del motore (tutte con valori adatti al telefono). */
class DownloadOptions(
    val maxParallel: Int = 2,
    /** Dimensione massima accettata per un singolo file. */
    val maxBytes: Long = 2L * 1024 * 1024 * 1024,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 30_000,
    val maxRedirects: Int = 5,
    /** Attese prima di ogni nuovo tentativo automatico dopo un errore passeggero (rete, 5xx, 429). */
    val retryDelaysMs: LongArray = longArrayOf(1_000, 3_000, 8_000),
    val userAgent: String = "MinecraftModInstaller",
    /** Spazio da lasciare libero oltre al file. */
    val reserveBytes: Long = 64L * 1024 * 1024,
    val freeSpace: (File) -> Long = { it.usableSpace }
)

/** Una singola trasmissione: apre la connessione, segue i reindirizzamenti controllati, scrive, verifica. */
internal class Transfer(
    private val checker: UrlChecker,
    private val options: DownloadOptions,
    private val dir: File
) {

    sealed class Outcome {
        class Completed(val sha256: String, val bytes: Long, val suggestedName: String?) : Outcome()
        object Paused : Outcome()
        object Canceled : Outcome()
        class NeedsHost(val host: String) : Outcome()
        class Failed(val message: String, val retryable: Boolean, val deletePartial: Boolean = false) : Outcome()
    }

    interface Listener {
        /** Dimensione totale e validatori noti: servono a riprendere con la certezza che il file sia lo stesso. */
        fun onMeta(totalBytes: Long, etag: String?, lastModified: String?)

        fun onProgress(done: Long, total: Long)
    }

    fun partFile(id: String): File = File(dir, "$id.part")

    fun run(record: DownloadRecord, control: Control, listener: Listener): Outcome {
        val part = partFile(record.id)
        var existing = if (part.isFile) part.length() else 0L
        // Si riprende solo se si può sapere che il file sul server è rimasto lo stesso.
        val validator = record.etag ?: record.lastModified
        if (existing > 0 && validator == null) {
            part.delete()
            existing = 0L
        }

        // 1) connessione, seguendo i reindirizzamenti uno per uno (ognuno deve passare i controlli di sicurezza)
        var current = record.url
        var conn: HttpURLConnection? = null
        var code = 0
        try {
            var hop = 0
            while (true) {
                when (val verdict = checker.check(current)) {
                    is UrlVerdict.Allowed -> {}
                    is UrlVerdict.HostNotAllowed -> return Outcome.NeedsHost(verdict.host)
                    is UrlVerdict.Rejected -> return Outcome.Failed(verdict.message, retryable = false)
                }
                if (control.wanted != Control.Wanted.RUN) return stopped(control)
                val c = try {
                    open(current, existing, validator)
                } catch (e: Exception) {
                    return Outcome.Failed("Non riesco a contattare il server: " + (e.message ?: e.javaClass.simpleName), retryable = true)
                }
                conn = c
                control.connection = c
                // Se nel frattempo è arrivata una pausa o un annullamento, la connessione non esisteva ancora da chiudere.
                if (control.wanted != Control.Wanted.RUN) return stopped(control)
                code = try {
                    c.responseCode
                } catch (e: Exception) {
                    // Anche chiudere la connessione mentre si aspetta la risposta può dare errori diversi da IOException.
                    if (control.wanted != Control.Wanted.RUN) return stopped(control)
                    return Outcome.Failed("Il server non risponde: " + (e.message ?: e.javaClass.simpleName), retryable = true)
                }
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    val location = c.getHeaderField("Location")
                    c.disconnect()
                    if (location.isNullOrBlank()) return Outcome.Failed("Il server ha indicato uno spostamento senza dire dove.", retryable = false)
                    hop++
                    if (hop > options.maxRedirects) return Outcome.Failed("Troppi reindirizzamenti: l'indirizzo non porta a un file.", retryable = false)
                    current = try {
                        URI(current).resolve(location.trim()).toString()
                    } catch (e: Exception) {
                        return Outcome.Failed("Il server ha indicato un indirizzo di destinazione non valido.", retryable = false)
                    }
                    continue
                }
                break
            }
            val c = conn ?: return Outcome.Failed("Connessione non riuscita.", retryable = true)

            // 2) significato della risposta
            var resumed = false
            var total = -1L
            when (code) {
                200 -> {
                    // Anche se si era chiesto di riprendere: il server manda tutto da capo (non supporta Range o il file è cambiato).
                    existing = 0L
                    total = c.contentLengthLong
                }
                206 -> {
                    val range = parseContentRange(c.getHeaderField("Content-Range"))
                    if (range == null || range.start != existing || existing == 0L) {
                        c.disconnect()
                        part.delete()
                        return Outcome.Failed("Il server ha risposto in modo inatteso alla ripresa: riprovo da capo.", retryable = true)
                    }
                    resumed = true
                    total = range.total ?: (existing + c.contentLengthLong.coerceAtLeast(0L))
                }
                416 -> {
                    val range = parseContentRange(c.getHeaderField("Content-Range"))
                    c.disconnect()
                    if (range?.total != null && existing > 0 && range.total == existing) {
                        // Il file parziale è già completo: resta da verificarlo.
                        return finish(record, part, existing, null, control)
                    }
                    part.delete()
                    return Outcome.Failed("Il file sul server è cambiato: riprovo da capo.", retryable = true)
                }
                404, 410 -> {
                    c.disconnect()
                    return Outcome.Failed("Il file non esiste (più) sul server (errore $code).", retryable = false)
                }
                401, 403 -> {
                    c.disconnect()
                    return Outcome.Failed("Il server non permette di scaricare questo file (errore $code).", retryable = false)
                }
                408, 429 -> {
                    c.disconnect()
                    return Outcome.Failed("Il server è troppo occupato (errore $code).", retryable = true)
                }
                else -> {
                    c.disconnect()
                    val retry = code in 500..599
                    return Outcome.Failed("Il server ha risposto con l'errore $code.", retryable = retry)
                }
            }

            // 3) controlli prima di scrivere
            if (total > options.maxBytes) {
                c.disconnect()
                return Outcome.Failed(
                    "Il file (${formatBytes(total)}) supera il limite di ${formatBytes(options.maxBytes)}.",
                    retryable = false, deletePartial = true
                )
            }
            if (total >= 0) {
                val needed = total - existing + options.reserveBytes
                val free = options.freeSpace(dir)
                if (free < needed) {
                    c.disconnect()
                    return Outcome.Failed(
                        "Spazio insufficiente: servono circa ${formatBytes(needed)}, ne restano ${formatBytes(free)}.",
                        retryable = false
                    )
                }
            }
            val newEtag = if (resumed) record.etag else strongEtag(c.getHeaderField("ETag"))
            val newModified = if (resumed) record.lastModified else c.getHeaderField("Last-Modified")
            listener.onMeta(total, newEtag, newModified)
            val suggested = DownloadNames.fromContentDisposition(c.getHeaderField("Content-Disposition"))

            // 4) scrittura
            var done = existing
            listener.onProgress(done, total)
            try {
                FileOutputStream(part, resumed).use { out ->
                    c.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (control.wanted != Control.Wanted.RUN) return stopped(control)
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            if (done > options.maxBytes) {
                                return Outcome.Failed(
                                    "Il file supera il limite di ${formatBytes(options.maxBytes)}.",
                                    retryable = false, deletePartial = true
                                )
                            }
                            listener.onProgress(done, total)
                        }
                    }
                }
            } catch (e: Exception) {
                if (control.wanted != Control.Wanted.RUN) return stopped(control)
                return Outcome.Failed("Connessione interrotta: " + (e.message ?: e.javaClass.simpleName), retryable = true)
            }
            if (control.wanted == Control.Wanted.CANCEL) return Outcome.Canceled

            if (total >= 0 && done != total) {
                return Outcome.Failed(
                    "Scaricamento incompleto: ricevuti ${formatBytes(done)} di ${formatBytes(total)}.",
                    retryable = true
                )
            }
            return finish(record, part, done, suggested, control)
        } finally {
            control.connection = null
            try {
                conn?.disconnect()
            } catch (e: Exception) {
                // già chiusa
            }
        }
    }

    private fun stopped(control: Control): Outcome =
        if (control.wanted == Control.Wanted.CANCEL) Outcome.Canceled else Outcome.Paused

    /** Verifica del file completo: dimensione e impronta. Un file che non supera la verifica non viene mai tenuto. */
    private fun finish(record: DownloadRecord, part: File, size: Long, suggested: String?, control: Control): Outcome {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val expected = record.expectedHash
        val extra = if (expected != null && expected.algorithm != HashAlgorithm.SHA256) MessageDigest.getInstance(expected.algorithm.javaName) else null
        try {
            part.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (control.wanted == Control.Wanted.CANCEL) return Outcome.Canceled
                    val n = input.read(buffer)
                    if (n < 0) break
                    sha256.update(buffer, 0, n)
                    extra?.update(buffer, 0, n)
                }
            }
        } catch (e: IOException) {
            return Outcome.Failed("Non riesco a leggere il file scaricato: " + (e.message ?: ""), retryable = false)
        }
        val shaHex = hex(sha256.digest())
        if (expected != null) {
            val actual = if (extra != null) hex(extra.digest()) else shaHex
            if (actual != expected.hex) {
                return Outcome.Failed(
                    "L'impronta ${expected.algorithm.label} non corrisponde: il file scaricato non è quello atteso e non è stato tenuto.",
                    retryable = false, deletePartial = true
                )
            }
        }
        return Outcome.Completed(shaHex, size, suggested)
    }

    private fun open(url: String, existing: Long, validator: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = options.connectTimeoutMs
        c.readTimeout = options.readTimeoutMs
        c.requestMethod = "GET"
        c.useCaches = false
        c.setRequestProperty("User-Agent", options.userAgent)
        c.setRequestProperty("Accept", "*/*")
        // Senza compressione: dimensioni e intervalli devono corrispondere ai byte scritti sul disco.
        c.setRequestProperty("Accept-Encoding", "identity")
        if (existing > 0) {
            c.setRequestProperty("Range", "bytes=$existing-")
            if (validator != null) c.setRequestProperty("If-Range", validator)
        }
        return c
    }

    internal class ContentRange(val start: Long, val end: Long, val total: Long?)

    companion object {
        private val RANGE = Regex("^\\s*bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)\\s*$", RegexOption.IGNORE_CASE)
        private val RANGE_UNSATISFIED = Regex("^\\s*bytes\\s+\\*/(\\d+)\\s*$", RegexOption.IGNORE_CASE)

        fun parseContentRange(header: String?): ContentRange? {
            if (header == null) return null
            RANGE.find(header)?.let { m ->
                val total = m.groupValues[3]
                return ContentRange(m.groupValues[1].toLong(), m.groupValues[2].toLong(), if (total == "*") null else total.toLong())
            }
            RANGE_UNSATISFIED.find(header)?.let { m ->
                return ContentRange(-1, -1, m.groupValues[1].toLong())
            }
            return null
        }

        /** Un ETag «debole» (W/…) non garantisce byte identici: non si usa per riprendere. */
        fun strongEtag(value: String?): String? = if (value == null || value.startsWith("W/")) null else value

        fun hex(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xff
                sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 15])
            }
            return sb.toString()
        }
    }
}
