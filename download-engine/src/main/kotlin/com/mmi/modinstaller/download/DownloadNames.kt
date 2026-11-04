package com.mmi.modinstaller.download

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.text.Normalizer
import java.util.Locale

/** Nomi dei file scaricati: mai un percorso, mai caratteri che il telefono o Minecraft non gestiscono. */
object DownloadNames {

    const val FALLBACK = "download"
    private const val MAX_LENGTH = 120
    private val FORBIDDEN = Regex("[\\u0000-\\u001f\\u007f<>:\"/\\\\|?*]")
    private val HAS_EXTENSION = Regex("\\.[A-Za-z0-9]{1,8}$")

    /** Nome sicuro ricavato da un testo qualsiasi (anche un percorso o un nome ostile come «..\\..\\x.jar»). */
    fun safe(raw: String, fallback: String = FALLBACK): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\')
        name = Normalizer.normalize(name, Normalizer.Form.NFC)
        name = FORBIDDEN.replace(name, "_")
        name = name.replace(Regex("\\s+"), " ").trim().trim('.', ' ')
        // Niente file nascosti e niente nomi fatti solo di punti.
        name = name.trimStart('.')
        if (name.length > MAX_LENGTH) {
            val ext = HAS_EXTENSION.find(name)?.value ?: ""
            name = name.substring(0, (MAX_LENGTH - ext.length).coerceAtLeast(1)).trimEnd('.', ' ') + ext
        }
        return if (name.isEmpty()) fallback else name
    }

    /** Nome dal percorso dell'indirizzo. Il secondo valore dice se è solo un'ipotesi (manca l'estensione). */
    fun fromUrl(url: String): Pair<String, Boolean> {
        val path = try {
            URI(url.trim()).rawPath ?: ""
        } catch (e: Exception) {
            ""
        }
        val last = path.trimEnd('/').substringAfterLast('/')
        val decoded = try {
            URLDecoder.decode(last.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            last
        }
        val name = safe(decoded)
        val provisional = name == FALLBACK || !HAS_EXTENSION.containsMatchIn(name)
        return Pair(name, provisional)
    }

    /** Nome indicato dal server nell'intestazione Content-Disposition (forme `filename*=UTF-8''…` e `filename="…"`). */
    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        Regex("filename\\*\\s*=\\s*([A-Za-z0-9-]+)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(header)?.let { m ->
            val charset = m.groupValues[1]
            val value = m.groupValues[2].trim()
            val decoded = try {
                URLDecoder.decode(value.replace("+", "%2B"), charset)
            } catch (e: Exception) {
                null
            }
            if (!decoded.isNullOrBlank()) return safe(decoded)
        }
        Regex("filename\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(header)?.let { m ->
            if (m.groupValues[1].isNotBlank()) return safe(m.groupValues[1])
        }
        Regex("filename\\s*=\\s*([^;\\s]+)", RegexOption.IGNORE_CASE).find(header)?.let { m ->
            if (m.groupValues[1].isNotBlank()) return safe(m.groupValues[1])
        }
        return null
    }

    /** Primo nome libero in [dir]: «mod.jar», poi «mod (1).jar», «mod (2).jar»… ([taken] = nomi già prenotati). */
    fun unique(dir: File, name: String, taken: Set<String> = emptySet()): String {
        val takenLower = taken.map { it.lowercase(Locale.ROOT) }.toSet()
        fun free(candidate: String) = candidate.lowercase(Locale.ROOT) !in takenLower && !File(dir, candidate).exists()
        if (free(name)) return name
        val ext = HAS_EXTENSION.find(name)?.value ?: ""
        val base = name.removeSuffix(ext)
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (free(candidate)) return candidate
            n++
        }
    }
}
