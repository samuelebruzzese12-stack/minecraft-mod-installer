package com.mmi.modinstaller.download

import com.mmi.modinstaller.core.asJsonObject
import com.mmi.modinstaller.core.bool
import com.mmi.modinstaller.core.long
import com.mmi.modinstaller.core.str
import java.util.Locale

enum class DownloadState(val label: String) {
    QUEUED("In coda"),
    RUNNING("In corso"),
    PAUSED("In pausa"),
    COMPLETED("Completato"),
    FAILED("Non riuscito"),
    CANCELED("Annullato");

    /** Sta usando o aspetta di usare la rete. */
    val active: Boolean get() = this == QUEUED || this == RUNNING

    /** Non cambierà più da solo. */
    val finished: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELED
}

enum class HashAlgorithm(val javaName: String, val hexLength: Int, val label: String) {
    SHA1("SHA-1", 40, "SHA-1"),
    SHA256("SHA-256", 64, "SHA-256"),
    SHA512("SHA-512", 128, "SHA-512");

    companion object {
        fun forHexLength(length: Int): HashAlgorithm? = values().firstOrNull { it.hexLength == length }
    }
}

/** Impronta che il file scaricato deve avere (la pubblicano i siti dei mod: Modrinth dà SHA-1 e SHA-512). */
class ExpectedHash private constructor(val algorithm: HashAlgorithm, val hex: String) {

    override fun equals(other: Any?): Boolean = other is ExpectedHash && other.algorithm == algorithm && other.hex == hex
    override fun hashCode(): Int = algorithm.hashCode() * 31 + hex.hashCode()
    override fun toString(): String = algorithm.label + ":" + hex

    companion object {
        /**
         * Legge un'impronta scritta da una persona: maiuscole o minuscole, spazi ignorati, prefisso facoltativo
         * («sha256:…»). L'algoritmo si riconosce dal prefisso o dalla lunghezza (40 = SHA-1, 64 = SHA-256, 128 = SHA-512).
         * Restituisce null se non è un'impronta valida.
         */
        fun parse(text: String): ExpectedHash? {
            var t = text.trim()
            var named: HashAlgorithm? = null
            val colon = t.indexOf(':')
            if (colon > 0) {
                val prefix = t.substring(0, colon).trim().lowercase(Locale.ROOT).replace("-", "")
                named = when (prefix) {
                    "sha1" -> HashAlgorithm.SHA1
                    "sha256" -> HashAlgorithm.SHA256
                    "sha512" -> HashAlgorithm.SHA512
                    else -> return null
                }
                t = t.substring(colon + 1)
            }
            val hex = t.filter { !it.isWhitespace() }.lowercase(Locale.ROOT)
            if (hex.isEmpty() || !hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
            val byLength = HashAlgorithm.forHexLength(hex.length) ?: return null
            if (named != null && named != byLength) return null
            return ExpectedHash(byLength, hex)
        }
    }
}

/** Cosa si vuole scaricare. Il nome del file, se manca, si ricava dall'indirizzo (o dalla risposta del server). */
data class DownloadRequest(
    val url: String,
    val fileName: String? = null,
    val expectedHash: ExpectedHash? = null,
    /** A cosa serve il file (es. «Mod Sodium»): serve solo a chi guarda l'elenco. */
    val note: String? = null
)

data class DownloadRecord(
    val id: String,
    val url: String,
    val fileName: String,
    /** Il nome è solo una prima ipotesi (l'indirizzo non ha un'estensione): il server può dare quello giusto. */
    val nameProvisional: Boolean,
    val state: DownloadState,
    val doneBytes: Long,
    /** -1 se il server non dichiara la dimensione. */
    val totalBytes: Long,
    val expectedHash: ExpectedHash?,
    /** SHA-256 del file completato (sempre calcolato, anche se si attendeva un'altra impronta). */
    val sha256: String?,
    /** Per riprendere solo se il file sul server è lo stesso (If-Range). */
    val etag: String?,
    val lastModified: String?,
    val error: String?,
    /** Sito verso cui il server ha reindirizzato e che l'utente non ha ancora consentito. */
    val pendingHost: String?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long
) {
    /** Avanzamento da 0 a 1, o null se la dimensione totale non è nota. */
    val fraction: Float?
        get() = if (totalBytes > 0) (doneBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat() else null

    fun toMap(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["id"] = id
        m["url"] = url
        m["fileName"] = fileName
        m["nameProvisional"] = nameProvisional
        m["state"] = state.name
        m["doneBytes"] = doneBytes
        m["totalBytes"] = totalBytes
        m["expectedHash"] = expectedHash?.toString()
        m["sha256"] = sha256
        m["etag"] = etag
        m["lastModified"] = lastModified
        m["error"] = error
        m["pendingHost"] = pendingHost
        m["note"] = note
        m["createdAt"] = createdAt
        m["updatedAt"] = updatedAt
        return m
    }

    companion object {
        /** Legge un record salvato; restituisce null se mancano i campi essenziali (il record si scarta, non blocca l'app). */
        fun fromValue(value: Any?): DownloadRecord? {
            val m = value.asJsonObject() ?: return null
            val id = m.str("id") ?: return null
            val url = m.str("url") ?: return null
            val fileName = m.str("fileName") ?: return null
            val state = DownloadState.values().firstOrNull { it.name == m.str("state") } ?: return null
            return DownloadRecord(
                id = id,
                url = url,
                fileName = fileName,
                nameProvisional = m.bool("nameProvisional") ?: false,
                state = state,
                doneBytes = (m.long("doneBytes") ?: 0L).coerceAtLeast(0L),
                totalBytes = m.long("totalBytes") ?: -1L,
                expectedHash = m.str("expectedHash")?.let { ExpectedHash.parse(it) },
                sha256 = m.str("sha256"),
                etag = m.str("etag"),
                lastModified = m.str("lastModified"),
                error = m.str("error"),
                pendingHost = m.str("pendingHost"),
                note = m.str("note"),
                createdAt = m.long("createdAt") ?: 0L,
                updatedAt = m.long("updatedAt") ?: 0L
            )
        }
    }
}
