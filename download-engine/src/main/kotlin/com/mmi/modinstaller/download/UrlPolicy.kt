package com.mmi.modinstaller.download

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** Esito del controllo di un indirizzo. */
sealed class UrlVerdict {
    object Allowed : UrlVerdict()

    /** Indirizzo da non usare, con la spiegazione da mostrare. */
    class Rejected(val message: String) : UrlVerdict()

    /** Indirizzo sicuro (https) ma di un sito che l'utente non ha ancora consentito. */
    class HostNotAllowed(val host: String) : UrlVerdict()
}

/** Decide se un indirizzo si può scaricare. Il motore la applica all'indirizzo iniziale e a ogni reindirizzamento. */
fun interface UrlChecker {
    fun check(url: String): UrlVerdict
}

/**
 * Regole di sicurezza degli indirizzi:
 * - solo `https://` (nessun `http://`), sulla porta standard 443;
 * - niente nome utente e password nell'indirizzo, niente indirizzi numerici (IP) né nomi della rete locale;
 * - solo siti consentiti: l'elenco di quelli noti (Modrinth, GitHub, CDN di CurseForge) più quelli che l'utente
 *   approva uno per uno. Un sito non consentito non viene mai contattato.
 *
 * [insecureHosts] (forma «host:porta») ammette `http://` solo per quei server: serve alle prove automatiche
 * (server locale nei test, emulatore Android in debug) e nell'app vera è vuoto.
 */
class UrlPolicy(
    private val userHosts: () -> Set<String> = { emptySet() },
    private val builtInHosts: List<String> = DEFAULT_HOSTS,
    private val insecureHosts: Set<String> = emptySet()
) : UrlChecker {

    override fun check(url: String): UrlVerdict {
        val text = url.trim()
        if (text.isEmpty()) return UrlVerdict.Rejected("Scrivi l'indirizzo del file da scaricare.")
        if (text.length > MAX_URL_LENGTH) return UrlVerdict.Rejected("L'indirizzo è troppo lungo.")
        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return UrlVerdict.Rejected("L'indirizzo non è valido.")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
        if (scheme == null || host.isNullOrEmpty()) {
            return UrlVerdict.Rejected("L'indirizzo non è valido: manca il nome del sito (esempio: https://cdn.modrinth.com/…).")
        }
        if (uri.userInfo != null) {
            return UrlVerdict.Rejected("Gli indirizzi con nome utente e password non sono ammessi.")
        }
        val port = uri.port
        if (scheme == "http") {
            val hostPort = if (port == -1) host else "$host:$port"
            if (hostPort in insecureHosts) return UrlVerdict.Allowed
            return UrlVerdict.Rejected("Sono ammessi solo indirizzi sicuri (https://): con http:// il file viaggia senza protezione.")
        }
        if (scheme != "https") return UrlVerdict.Rejected("Sono ammessi solo indirizzi https://.")
        if (port != -1 && port != 443) return UrlVerdict.Rejected("Sono ammessi solo i server sulla porta standard (443).")
        if (isIpLiteral(host)) return UrlVerdict.Rejected("Non si scarica da un indirizzo numerico (IP): serve il nome del sito.")
        if (isLocalName(host)) return UrlVerdict.Rejected("Gli indirizzi della rete locale non sono ammessi.")
        return if (isAllowed(host)) UrlVerdict.Allowed else UrlVerdict.HostNotAllowed(host)
    }

    private fun isAllowed(host: String): Boolean {
        if (host in userHosts()) return true
        for (entry in builtInHosts) {
            if (entry.startsWith("*.")) {
                val suffix = entry.substring(1) // ".githubusercontent.com"
                if (host.length > suffix.length && host.endsWith(suffix)) return true
            } else if (host == entry) {
                return true
            }
        }
        return false
    }

    companion object {
        const val MAX_URL_LENGTH = 2048

        /** Siti da cui scaricano i servizi più usati per mod e mondi. Il resto richiede il consenso dell'utente. */
        val DEFAULT_HOSTS: List<String> = listOf(
            "cdn.modrinth.com",
            "api.modrinth.com",
            "github.com",
            "*.githubusercontent.com",
            "edge.forgecdn.net",
            "mediafilez.forgecdn.net",
            "media.forgecdn.net"
        )

        private val HOST_NAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")
        private val IPV4 = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")

        /** Nome di sito valido da approvare (minuscolo, senza schema né percorso), o null se non lo è. */
        fun normalizeHost(raw: String): String? {
            val host = raw.trim().lowercase(Locale.ROOT).trimEnd('.')
            if (host.length > 253) return null
            if (!HOST_NAME.matches(host)) return null
            if (isIpLiteral(host) || isLocalName(host)) return null
            return host
        }

        internal fun isIpLiteral(host: String): Boolean =
            host.startsWith("[") || host.contains(':') || IPV4.matches(host) || host.all { it in '0'..'9' || it == '.' }

        internal fun isLocalName(host: String): Boolean =
            host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
                host.endsWith(".internal") || host.endsWith(".lan") || host.endsWith(".home.arpa") || !host.contains('.')
    }
}
