package com.mmi.modinstaller.jar

/** Esito del confronto tra una versione e un vincolo. UNKNOWN: la versione non è confrontabile (es. snapshot «24w14a»). */
enum class Compat {
    YES, NO, UNKNOWN;

    companion object {
        fun of(value: Boolean): Compat = if (value) YES else NO
    }
}

/**
 * Versione «tollerante» per mod, caricatori e Minecraft. Forge usa le regole di Maven, Fabric e Quilt quelle di SemVer, e nel
 * mondo reale le mod usano entrambe in modo libero (`1.20.1-2.3.4`, `0.92.2+1.20.1`, `2.0.0-beta.3`): qui si confrontano
 * prima i numeri iniziali, poi il suffisso, che è
 *  - una *pre-versione* se inizia con alpha, beta, rc, pre, snapshot, dev… (viene prima della versione senza suffisso),
 *  - altrimenti un'*etichetta di build* (`-forge`, `-1.20.1`), che viene dopo.
 * I metadati dopo il `+` non contano. Una versione senza numeri iniziali (o uno snapshot) non è confrontabile: [comparable].
 */
class ModVersion private constructor(
    val raw: String,
    private val release: List<Long>,
    private val qualifier: List<Any>,
    val comparable: Boolean
) : Comparable<ModVersion> {

    private val qualifierClass: Int = when {
        qualifier.isEmpty() -> CLASS_NONE
        qualifier[0] is String && (qualifier[0] as String) in PRE_RANK -> CLASS_PRE
        else -> CLASS_POST
    }

    /** Numero iniziale di indice [index] (0 se manca). */
    fun component(index: Int): Long = if (index in release.indices) release[index] else 0L

    val componentCount: Int get() = release.size

    override fun compareTo(other: ModVersion): Int {
        if (!comparable || !other.comparable) return raw.compareTo(other.raw)
        val n = maxOf(release.size, other.release.size)
        for (i in 0 until n) {
            val c = component(i).compareTo(other.component(i))
            if (c != 0) return c
        }
        if (qualifierClass != other.qualifierClass) return qualifierClass.compareTo(other.qualifierClass)
        if (qualifierClass == CLASS_NONE) return 0
        val m = maxOf(qualifier.size, other.qualifier.size)
        for (i in 0 until m) {
            val a = qualifier.getOrNull(i)
            val b = other.qualifier.getOrNull(i)
            if (a == null && b == null) return 0
            if (a == null) return -1
            if (b == null) return 1
            val c = compareToken(a, b)
            if (c != 0) return c
        }
        return 0
    }

    private fun compareToken(a: Any, b: Any): Int {
        if (a is Long && b is Long) return a.compareTo(b)
        if (a is Long) return -1 // un numero precede un testo
        if (b is Long) return 1
        val sa = a as String
        val sb = b as String
        val ra = PRE_RANK[sa]
        val rb = PRE_RANK[sb]
        if (ra != null && rb != null && ra != rb) return ra.compareTo(rb)
        if (ra != null && rb == null) return -1
        if (ra == null && rb != null) return 1
        return sa.compareTo(sb)
    }

    override fun equals(other: Any?): Boolean = other is ModVersion && compareTo(other) == 0 && comparable == other.comparable

    override fun hashCode(): Int {
        var end = release.size
        while (end > 0 && release[end - 1] == 0L) end--
        return release.subList(0, end).hashCode() * 31 + qualifier.hashCode()
    }

    override fun toString(): String = raw

    companion object {
        private const val CLASS_PRE = 0
        private const val CLASS_NONE = 1
        private const val CLASS_POST = 2

        private val PRE_RANK: Map<String, Int> = mapOf(
            "dev" to 0, "nightly" to 0, "snapshot" to 0,
            "alpha" to 1, "a" to 1,
            "beta" to 2, "b" to 2,
            "milestone" to 3, "m" to 3, "pre" to 3, "preview" to 3, "experimental" to 3,
            "rc" to 4, "cr" to 4
        )
        private val RELEASE_WORDS = setOf("final", "ga", "release")
        private val SNAPSHOT_NAME = Regex("^\\d{2}w\\d{2}[a-z]$")
        private const val MAX_DIGITS = 18

        /** Restituisce sempre una versione; [comparable] dice se ha numeri iniziali utilizzabili. Null solo per testo vuoto. */
        fun parse(text: String?): ModVersion? {
            if (text == null) return null
            var t = text.trim()
            if (t.isEmpty()) return null
            if ((t[0] == 'v' || t[0] == 'V') && t.length > 1 && t[1].isDigit()) t = t.substring(1)
            val core = t.substringBefore('+')
            if (SNAPSHOT_NAME.matches(core)) return ModVersion(text.trim(), emptyList(), emptyList(), false)

            var i = 0
            val release = ArrayList<Long>()
            while (i < core.length && core[i].isDigit()) {
                val start = i
                while (i < core.length && core[i].isDigit()) i++
                if (i - start > MAX_DIGITS) return ModVersion(text.trim(), emptyList(), emptyList(), false)
                release.add(core.substring(start, i).toLong())
                if (i + 1 < core.length && core[i] == '.' && core[i + 1].isDigit()) i++ else break
            }
            if (release.isEmpty()) return ModVersion(text.trim(), emptyList(), emptyList(), false)

            val tokens = ArrayList<Any>()
            while (i < core.length) {
                val c = core[i]
                if (c.isDigit()) {
                    val start = i
                    while (i < core.length && core[i].isDigit()) i++
                    if (i - start > MAX_DIGITS) return ModVersion(text.trim(), emptyList(), emptyList(), false)
                    tokens.add(core.substring(start, i).toLong())
                } else if (c.isLetter()) {
                    val start = i
                    while (i < core.length && core[i].isLetter()) i++
                    tokens.add(core.substring(start, i).lowercase(java.util.Locale.ROOT))
                } else {
                    i++ // separatori: . - _ e simili
                }
            }
            if (tokens.size == 1 && tokens[0] is String && (tokens[0] as String) in RELEASE_WORDS) tokens.clear()
            return ModVersion(text.trim(), release, tokens, true)
        }
    }
}

/** Un vincolo di versione (intervallo Maven o predicato SemVer). */
interface VersionConstraint {
    /** Testo originale, così com'è scritto nella mod. */
    val text: String

    fun test(version: ModVersion?): Compat

    /** Descrizione leggibile in italiano. */
    fun describe(): String

    /** La versione più bassa ammessa, quando il vincolo ne ha una (serve a proporre un valore, non a decidere). */
    fun lowestVersion(): ModVersion? = null
}

fun VersionConstraint.test(version: String?): Compat = test(ModVersion.parse(version))

/** Nessun limite («*», vuoto, o versione «consigliata» di Maven che non è un requisito). */
class AnyVersion(override val text: String = "*") : VersionConstraint {
    override fun test(version: ModVersion?): Compat = Compat.YES
    override fun describe(): String = "qualsiasi versione"
}

/** Testo che non si riesce a interpretare: non si dà un giudizio. */
class UnparsedConstraint(override val text: String) : VersionConstraint {
    override fun test(version: ModVersion?): Compat = Compat.UNKNOWN
    override fun describe(): String = "«$text» (formato non riconosciuto)"
}

class IntervalConstraint(
    override val text: String,
    val lower: ModVersion?,
    val lowerInclusive: Boolean,
    val upper: ModVersion?,
    val upperInclusive: Boolean
) : VersionConstraint {

    override fun test(version: ModVersion?): Compat {
        if (version == null || !version.comparable) return Compat.UNKNOWN
        if (lower != null) {
            val c = version.compareTo(lower)
            if (c < 0 || (c == 0 && !lowerInclusive)) return Compat.NO
        }
        if (upper != null) {
            val c = version.compareTo(upper)
            if (c > 0 || (c == 0 && !upperInclusive)) return Compat.NO
        }
        return Compat.YES
    }

    override fun lowestVersion(): ModVersion? = lower

    override fun describe(): String = when {
        lower != null && upper != null && lowerInclusive && upperInclusive && lower.compareTo(upper) == 0 -> "esattamente $lower"
        lower != null && upper != null ->
            "da $lower (${if (lowerInclusive) "incluso" else "escluso"}) a $upper (${if (upperInclusive) "incluso" else "escluso"})"
        lower != null -> (if (lowerInclusive) "$lower o successiva" else "dopo la $lower")
        upper != null -> (if (upperInclusive) "fino alla $upper" else "prima della $upper")
        else -> "qualsiasi versione"
    }
}

/** Almeno uno dei vincoli deve essere soddisfatto. */
class AnyOfConstraint(override val text: String, val parts: List<VersionConstraint>) : VersionConstraint {
    override fun test(version: ModVersion?): Compat {
        var unknown = false
        for (p in parts) {
            when (p.test(version)) {
                Compat.YES -> return Compat.YES
                Compat.UNKNOWN -> unknown = true
                Compat.NO -> {}
            }
        }
        return if (unknown) Compat.UNKNOWN else Compat.NO
    }

    override fun lowestVersion(): ModVersion? {
        val lows = parts.map { it.lowestVersion() }
        if (lows.isEmpty() || lows.any { it == null }) return null
        return lows.filterNotNull().minOrNull()
    }

    override fun describe(): String = parts.joinToString(" oppure ") { it.describe() }
}

/** Tutti i vincoli devono essere soddisfatti. */
class AllOfConstraint(override val text: String, val parts: List<VersionConstraint>) : VersionConstraint {
    override fun test(version: ModVersion?): Compat {
        var unknown = false
        for (p in parts) {
            when (p.test(version)) {
                Compat.NO -> return Compat.NO
                Compat.UNKNOWN -> unknown = true
                Compat.YES -> {}
            }
        }
        return if (unknown) Compat.UNKNOWN else Compat.YES
    }

    override fun lowestVersion(): ModVersion? = parts.mapNotNull { it.lowestVersion() }.maxOrNull()

    override fun describe(): String = parts.joinToString(" e ") { it.describe() }
}

object VersionRanges {

    // ------------------------------------------------------------------ Maven (Forge, NeoForge)

    /**
     * Intervalli di Maven: `[1.20,1.21)`, `[40,)`, `(,1.0]`, `[1.20.1]`, più intervalli separati da virgola.
     * Una versione semplice (`1.20.1`) in Maven è solo «consigliata»: non limita nulla.
     * Non lancia mai eccezioni: se il testo non è valido restituisce [UnparsedConstraint].
     */
    fun parseMaven(spec: String?): VersionConstraint {
        val original = spec?.trim().orEmpty()
        if (original.isEmpty() || original == "*") return AnyVersion(original.ifEmpty { "*" })
        if (!original.startsWith("[") && !original.startsWith("(")) {
            // niente parentesi: versione «consigliata»
            return if (original.contains(',') || original.contains(' ')) UnparsedConstraint(original) else AnyVersion(original)
        }
        val parts = ArrayList<VersionConstraint>()
        var rest = original
        while (rest.isNotEmpty()) {
            val first = rest[0]
            if (first != '[' && first != '(') return UnparsedConstraint(original)
            val closeSq = rest.indexOf(']')
            val closeRd = rest.indexOf(')')
            val close = when {
                closeSq < 0 && closeRd < 0 -> return UnparsedConstraint(original)
                closeSq < 0 -> closeRd
                closeRd < 0 -> closeSq
                else -> minOf(closeSq, closeRd)
            }
            val inner = rest.substring(1, close).trim()
            val lowerInc = first == '['
            val upperInc = rest[close] == ']'
            val interval = parseInterval(original, inner, lowerInc, upperInc) ?: return UnparsedConstraint(original)
            parts.add(interval)
            rest = rest.substring(close + 1).trim()
            if (rest.startsWith(",")) rest = rest.substring(1).trim() else if (rest.isNotEmpty()) return UnparsedConstraint(original)
        }
        if (parts.isEmpty()) return UnparsedConstraint(original)
        return if (parts.size == 1) parts[0] else AnyOfConstraint(original, parts)
    }

    private fun parseInterval(original: String, inner: String, lowerInc: Boolean, upperInc: Boolean): IntervalConstraint? {
        val comma = inner.indexOf(',')
        if (comma < 0) {
            // «[1.20.1]»: versione esatta; «(1.0)» non è valido
            if (!lowerInc || !upperInc) return null
            val v = ModVersion.parse(inner)?.takeIf { it.comparable } ?: return null
            return IntervalConstraint(original, v, true, v, true)
        }
        val lowText = inner.substring(0, comma).trim()
        val upText = inner.substring(comma + 1).trim()
        if (upText.contains(',')) return null
        val low = if (lowText.isEmpty()) null else ModVersion.parse(lowText)?.takeIf { it.comparable } ?: return null
        val up = if (upText.isEmpty()) null else ModVersion.parse(upText)?.takeIf { it.comparable } ?: return null
        if (low != null && up != null && low.compareTo(up) > 0) return null
        return IntervalConstraint(original, low, lowerInc, up, upperInc)
    }

    // ------------------------------------------------------------------ SemVer (Fabric, Quilt)

    /** Un elenco di predicati alternativi (come l'array di stringhe di fabric.mod.json). */
    fun parseSemverAnyOf(specs: List<String>): VersionConstraint {
        val cleaned = specs.map { it.trim() }
        if (cleaned.isEmpty()) return AnyVersion("*")
        if (cleaned.size == 1) return parseSemver(cleaned[0])
        return AnyOfConstraint(cleaned.joinToString(" || "), cleaned.map { parseSemver(it) })
    }

    /**
     * Predicato SemVer di Fabric/Quilt: `*`, `1.2.3`, `>=1.2.3`, `>1`, `<=2`, `<2`, `=1.2.3`, `~1.2.3`, `^1.2.3`, `1.2.x`,
     * più predicati separati da spazi (tutti devono valere) o da `||` (ne basta uno).
     */
    fun parseSemver(spec: String?): VersionConstraint {
        val original = spec?.trim().orEmpty()
        if (original.isEmpty()) return AnyVersion("*")
        val alternatives = original.split("||").map { it.trim() }
        if (alternatives.size > 1) {
            return AnyOfConstraint(original, alternatives.map { parseSemverAllOf(it) })
        }
        return parseSemverAllOf(original)
    }

    private fun parseSemverAllOf(text: String): VersionConstraint {
        if (text.isEmpty()) return UnparsedConstraint(text)
        // «>= 1.2» (con spazio) va letto come un solo predicato
        val raw = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val tokens = ArrayList<String>()
        var i = 0
        while (i < raw.size) {
            val t = raw[i]
            if (t.all { it == '<' || it == '>' || it == '=' || it == '~' || it == '^' } && i + 1 < raw.size) {
                tokens.add(t + raw[i + 1])
                i += 2
            } else {
                tokens.add(t)
                i++
            }
        }
        val parts = tokens.map { parseSemverToken(it) }
        return if (parts.size == 1) parts[0] else AllOfConstraint(text, parts)
    }

    private fun isWildcard(part: String): Boolean = part == "x" || part == "X" || part == "*"

    private fun parseSemverToken(token: String): VersionConstraint {
        var op = ""
        for (candidate in listOf(">=", "<=", ">", "<", "=", "~", "^")) {
            if (token.startsWith(candidate)) {
                op = candidate
                break
            }
        }
        val body = token.substring(op.length)
        if (body.isEmpty()) return UnparsedConstraint(token)
        if (isWildcard(body)) return AnyVersion(token)

        // componenti con caratteri jolly: «1.2.x», «1.*»
        val pieces = body.substringBefore('+').split('.')
        val wildcardIndex = pieces.indexOfFirst { isWildcard(it) }
        if (wildcardIndex >= 0) {
            if (wildcardIndex == 0) return AnyVersion(token)
            if (pieces.drop(wildcardIndex).any { !isWildcard(it) }) return UnparsedConstraint(token)
            val fixed = ModVersion.parse(pieces.take(wildcardIndex).joinToString("."))?.takeIf { it.comparable }
                ?: return UnparsedConstraint(token)
            if (fixed.componentCount != wildcardIndex) return UnparsedConstraint(token)
            val low = fixed
            val high = bump(fixed, wildcardIndex - 1) ?: return UnparsedConstraint(token)
            return when (op) {
                "", "=", "~", "^" -> IntervalConstraint(token, low, true, high, false)
                ">=" -> IntervalConstraint(token, low, true, null, false)
                ">" -> IntervalConstraint(token, high, true, null, false)
                "<=" -> IntervalConstraint(token, null, false, high, false)
                "<" -> IntervalConstraint(token, null, false, low, false)
                else -> UnparsedConstraint(token)
            }
        }

        val v = ModVersion.parse(body)?.takeIf { it.comparable } ?: return UnparsedConstraint(token)
        return when (op) {
            "", "=" -> IntervalConstraint(token, v, true, v, true)
            ">=" -> IntervalConstraint(token, v, true, null, false)
            ">" -> IntervalConstraint(token, v, false, null, false)
            "<=" -> IntervalConstraint(token, null, false, v, true)
            "<" -> IntervalConstraint(token, null, false, v, false)
            // come in Fabric: «~» = stessa versione maggiore e minore; «^» = stessa versione maggiore
            "~" -> SameConstraint(token, v, 2)
            "^" -> SameConstraint(token, v, 1)
            else -> UnparsedConstraint(token)
        }
    }

    /** Versione con la componente [index] aumentata di uno e le successive azzerate (1.2 → 1.3, 1 → 2). */
    private fun bump(v: ModVersion, index: Int): ModVersion? {
        val parts = ArrayList<String>()
        for (k in 0..index) parts.add(if (k == index) (v.component(k) + 1).toString() else v.component(k).toString())
        return ModVersion.parse(parts.joinToString("."))
    }

    /** `~` e `^` di Fabric: non meno di [base] e con le prime [fixedParts] componenti uguali. */
    private class SameConstraint(override val text: String, val base: ModVersion, val fixedParts: Int) : VersionConstraint {
        override fun test(version: ModVersion?): Compat {
            if (version == null || !version.comparable) return Compat.UNKNOWN
            if (version.compareTo(base) < 0) return Compat.NO
            for (k in 0 until fixedParts) if (version.component(k) != base.component(k)) return Compat.NO
            return Compat.YES
        }

        override fun lowestVersion(): ModVersion? = base

        override fun describe(): String {
            val head = (0 until fixedParts).joinToString(".") { base.component(it).toString() }
            return "$base o successiva, restando nella serie $head"
        }
    }
}
