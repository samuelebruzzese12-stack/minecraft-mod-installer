package com.mmi.modinstaller.catalog

import com.mmi.modinstaller.core.CancelCheck
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

/** Risposta del servizio. [body] è già testo (UTF-8); [retryAfterSeconds] c'è solo se il servizio lo ha indicato. */
class HttpReply(val status: Int, val body: String, val retryAfterSeconds: Int? = null)

/**
 * Chiede un indirizzo e restituisce la risposta. È un'interfaccia perché le prove usano un finto servizio e perché il
 * catalogo non deve sapere come funziona la rete di Android. Chi implementa lancia [CatalogException] per i problemi di
 * rete e lascia passare l'eccezione di [cancel] quando l'utente annulla.
 */
fun interface CatalogHttp {
    fun get(url: String, cancel: CancelCheck): HttpReply
}

/**
 * Rete vera, con le regole di sicurezza del catalogo:
 * - solo richieste GET verso i siti in [allowedHosts], e solo `https://` sulla porta standard
 *   ([insecureHosts], nella forma «host:porta», ammette `http://` solo per i server di prova);
 * - nessun reindirizzamento: se il servizio rimanda altrove, la richiesta è respinta;
 * - nessun dato dell'utente nella richiesta (solo l'indirizzo e l'identificativo dell'app);
 * - risposte limitate a [maxBodyBytes] e tempi massimi di attesa.
 */
class UrlConnectionHttp(
    private val userAgent: String,
    private val allowedHosts: Set<String> = setOf(MODRINTH_HOST),
    private val insecureHosts: Set<String> = emptySet(),
    private val maxBodyBytes: Int = DEFAULT_MAX_BODY,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000
) : CatalogHttp {

    /** Controlla l'indirizzo e lo restituisce; [CatalogException] di tipo BLOCKED se non è ammesso. */
    fun checkUrl(url: String): URI {
        val uri = try {
            URI(url.trim())
        } catch (e: URISyntaxException) {
            throw CatalogException(CatalogError.BLOCKED, "L'indirizzo del catalogo non è valido.")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (host.isNullOrEmpty() || uri.userInfo != null) {
            throw CatalogException(CatalogError.BLOCKED, "L'indirizzo del catalogo non è valido.")
        }
        val port = uri.port
        if (scheme == "http") {
            val hostPort = if (port == -1) host else "$host:$port"
            if (hostPort in insecureHosts) return uri
            throw CatalogException(CatalogError.BLOCKED, "Il catalogo si apre solo con indirizzi sicuri (https).")
        }
        if (scheme != "https" || (port != -1 && port != 443)) {
            throw CatalogException(CatalogError.BLOCKED, "Il catalogo si apre solo con indirizzi sicuri (https).")
        }
        if (host !in allowedHosts) {
            throw CatalogException(CatalogError.BLOCKED, "Il sito «$host» non è tra quelli del catalogo.")
        }
        return uri
    }

    override fun get(url: String, cancel: CancelCheck): HttpReply {
        val uri = checkUrl(url)
        cancel()
        var conn: HttpURLConnection? = null
        try {
            conn = uri.toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "application/json")
            // Senza compressione: lo stesso comportamento sulla JVM e su Android.
            conn.setRequestProperty("Accept-Encoding", "identity")

            val status = conn.responseCode
            if (status in 300..399) {
                throw CatalogException(CatalogError.BAD_RESPONSE, "Il servizio ha reindirizzato altrove: non lo seguo.")
            }
            val declared = conn.contentLengthLong
            if (declared > maxBodyBytes) {
                throw CatalogException(CatalogError.TOO_LARGE, "La risposta del catalogo è troppo grande.")
            }
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val limit = if (status in 200..299) maxBodyBytes else ERROR_BODY_LIMIT
            val body = if (stream != null) readBounded(stream, limit, status in 200..299, cancel) else ""
            return HttpReply(status, body, retryAfter(conn))
        } catch (e: CatalogException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw CatalogException(CatalogError.TIMEOUT, "Il catalogo non risponde: riprova tra poco.", cause = e)
        } catch (e: UnknownHostException) {
            throw CatalogException(CatalogError.NETWORK, "Non riesco a raggiungere il catalogo: controlla la connessione a Internet.", cause = e)
        } catch (e: SSLException) {
            throw CatalogException(CatalogError.NETWORK, "La connessione sicura al catalogo non è riuscita.", cause = e)
        } catch (e: IOException) {
            throw CatalogException(CatalogError.NETWORK, "Non riesco a raggiungere il catalogo: controlla la connessione a Internet.", cause = e)
        } finally {
            conn?.disconnect()
        }
    }

    private fun readBounded(input: InputStream, limit: Int, failIfLarger: Boolean, cancel: CancelCheck): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        input.use { stream ->
            while (true) {
                cancel()
                val n = stream.read(buffer)
                if (n < 0) break
                if (out.size() + n > limit) {
                    if (failIfLarger) throw CatalogException(CatalogError.TOO_LARGE, "La risposta del catalogo è troppo grande.")
                    out.write(buffer, 0, limit - out.size())
                    break
                }
                out.write(buffer, 0, n)
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** Secondi di attesa chiesti dal servizio (intestazione Retry-After, o in mancanza X-Ratelimit-Reset). */
    private fun retryAfter(conn: HttpURLConnection): Int? {
        for (name in listOf("Retry-After", "X-Ratelimit-Reset")) {
            val value = conn.getHeaderField(name)?.trim()?.toIntOrNull() ?: continue
            if (value in 0..MAX_RETRY_SECONDS) return value
        }
        return null
    }

    companion object {
        const val MODRINTH_HOST = "api.modrinth.com"
        const val DEFAULT_MAX_BODY = 8 * 1024 * 1024
        private const val ERROR_BODY_LIMIT = 64 * 1024
        private const val MAX_RETRY_SECONDS = 3600
    }
}
