package com.mmi.modinstaller.jar

class TomlException(message: String, val line: Int) : Exception("$message (riga $line)")

/**
 * Lettore TOML per i file di metadati di Forge e NeoForge (`mods.toml`, `neoforge.mods.toml`). Copre ciò che serve:
 * commenti, chiavi semplici, tra apici e con punti, tabelle `[a.b]`, array di tabelle `[[a.b]]`, stringhe base, letterali
 * e su più righe, numeri, booleani, array (anche su più righe) e tabelle in linea. Date e orari non servono ai metadati
 * e vengono lette come testo.
 *
 * Restituisce mappe, liste, stringhe, Long, Double e Boolean. Il file è dato in input da altri: nessuna parte viene eseguita.
 */
object Toml {

    private const val MAX_DEPTH = 64
    private val DATE_LIKE = Regex("^(\\d{4}-\\d{2}-\\d{2}([Tt]\\S*)?|\\d{2}:\\d{2}:\\d{2}\\S*)$")

    fun parse(text: String): Map<String, Any?> = Parser(text.removePrefix("﻿")).parseDocument()

    private class Parser(private val s: String) {
        private var i = 0
        private var line = 1

        fun parseDocument(): Map<String, Any?> {
            val root = LinkedHashMap<String, Any?>()
            var current: MutableMap<String, Any?> = root
            while (true) {
                skipBlank()
                if (i >= s.length) break
                if (s[i] == '[') {
                    val isArray = i + 1 < s.length && s[i + 1] == '['
                    i += if (isArray) 2 else 1
                    val path = parseKeyPath()
                    skipInlineSpace()
                    expect(']')
                    if (isArray) expect(']')
                    endOfLine()
                    current = if (isArray) openArrayTable(root, path) else openTable(root, path)
                } else {
                    val path = parseKeyPath()
                    skipInlineSpace()
                    expect('=')
                    skipInlineSpace()
                    val value = parseValue()
                    endOfLine()
                    assign(current, path, value)
                }
            }
            return root
        }

        // ---------------------------------------------------------------- tabelle

        @Suppress("UNCHECKED_CAST")
        private fun descend(from: MutableMap<String, Any?>, key: String): MutableMap<String, Any?> {
            return when (val existing = from[key]) {
                null -> LinkedHashMap<String, Any?>().also { from[key] = it }
                is MutableMap<*, *> -> existing as MutableMap<String, Any?>
                is MutableList<*> -> {
                    // dentro un array di tabelle si scende nell'ultimo elemento
                    val last = existing.lastOrNull() as? MutableMap<String, Any?> ?: fail("La chiave '$key' non è una tabella")
                    last
                }
                else -> fail("La chiave '$key' è già usata per un valore")
            }
        }

        private fun openTable(root: MutableMap<String, Any?>, path: List<String>): MutableMap<String, Any?> {
            var t = root
            for (k in path) t = descend(t, k)
            return t
        }

        @Suppress("UNCHECKED_CAST")
        private fun openArrayTable(root: MutableMap<String, Any?>, path: List<String>): MutableMap<String, Any?> {
            var t = root
            for (k in path.dropLast(1)) t = descend(t, k)
            val last = path.last()
            val list = when (val existing = t[last]) {
                null -> ArrayList<Any?>().also { t[last] = it }
                is MutableList<*> -> existing as MutableList<Any?>
                else -> fail("La chiave '$last' non è un array di tabelle")
            }
            val item = LinkedHashMap<String, Any?>()
            list.add(item)
            return item
        }

        private fun assign(table: MutableMap<String, Any?>, path: List<String>, value: Any?) {
            var t = table
            for (k in path.dropLast(1)) t = descend(t, k)
            val key = path.last()
            if (t.containsKey(key)) fail("La chiave '$key' compare due volte")
            t[key] = value
        }

        // ---------------------------------------------------------------- chiavi

        private fun parseKeyPath(): List<String> {
            val parts = ArrayList<String>()
            while (true) {
                skipInlineSpace()
                parts.add(parseKeySegment())
                skipInlineSpace()
                if (i < s.length && s[i] == '.') {
                    i++
                    continue
                }
                break
            }
            return parts
        }

        private fun parseKeySegment(): String {
            if (i >= s.length) fail("Chiave mancante")
            val c = s[i]
            if (c == '"') return parseBasicString()
            if (c == '\'') return parseLiteralString()
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '-')) i++
            if (i == start) fail("Chiave non valida")
            return s.substring(start, i)
        }

        // ---------------------------------------------------------------- valori

        private fun parseValue(depth: Int = 0): Any? {
            if (depth > MAX_DEPTH) fail("Annidamento troppo profondo")
            if (i >= s.length) fail("Valore mancante")
            val c = s[i]
            return when {
                c == '"' -> if (s.startsWith("\"\"\"", i)) parseMultiline(true) else parseBasicString()
                c == '\'' -> if (s.startsWith("'''", i)) parseMultiline(false) else parseLiteralString()
                c == '[' -> parseArray(depth)
                c == '{' -> parseInlineTable(depth)
                else -> parseBare()
            }
        }

        private fun parseBare(): Any? {
            val start = i
            while (i < s.length && s[i] != ',' && s[i] != ']' && s[i] != '}' && s[i] != '#' &&
                s[i] != '\n' && s[i] != '\r' && s[i] != ' ' && s[i] != '\t'
            ) i++
            val token = s.substring(start, i)
            if (token.isEmpty()) fail("Valore mancante")
            return when (token) {
                "true" -> true
                "false" -> false
                "inf", "+inf" -> Double.POSITIVE_INFINITY
                "-inf" -> Double.NEGATIVE_INFINITY
                "nan", "+nan", "-nan" -> Double.NaN
                else -> {
                    val clean = token.replace("_", "")
                    clean.toLongOrNull()
                        ?: clean.toDoubleOrNull()
                        ?: parseRadix(clean)
                        ?: if (DATE_LIKE.matches(token)) token else fail("Valore non valido: $token")
                }
            }
        }

        private fun parseRadix(t: String): Long? = when {
            t.startsWith("0x") -> t.substring(2).toLongOrNull(16)
            t.startsWith("0o") -> t.substring(2).toLongOrNull(8)
            t.startsWith("0b") -> t.substring(2).toLongOrNull(2)
            else -> null
        }

        private fun parseArray(depth: Int): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            while (true) {
                skipBlank()
                if (i >= s.length) fail("Array non chiuso")
                if (s[i] == ']') {
                    i++
                    return list
                }
                list.add(parseValue(depth + 1))
                skipBlank()
                if (i < s.length && s[i] == ',') i++
            }
        }

        private fun parseInlineTable(depth: Int): Map<String, Any?> {
            expect('{')
            val table = LinkedHashMap<String, Any?>()
            while (true) {
                skipInlineSpace()
                if (i >= s.length) fail("Tabella in linea non chiusa")
                if (s[i] == '}') {
                    i++
                    return table
                }
                val path = parseKeyPath()
                skipInlineSpace()
                expect('=')
                skipInlineSpace()
                assign(table, path, parseValue(depth + 1))
                skipInlineSpace()
                if (i < s.length && s[i] == ',') i++
            }
        }

        private fun parseBasicString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length || s[i] == '\n') fail("Stringa non chiusa")
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c == '\\') sb.append(parseEscape()) else sb.append(c)
            }
        }

        private fun parseLiteralString(): String {
            expect('\'')
            val start = i
            while (i < s.length && s[i] != '\'' && s[i] != '\n') i++
            if (i >= s.length || s[i] != '\'') fail("Stringa non chiusa")
            val out = s.substring(start, i)
            i++
            return out
        }

        private fun parseMultiline(basic: Boolean): String {
            val quote = if (basic) "\"\"\"" else "'''"
            i += 3
            // un a capo subito dopo l'apertura viene ignorato
            if (i < s.length && s[i] == '\r') i++
            if (i < s.length && s[i] == '\n') {
                i++
                line++
            }
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Stringa su più righe non chiusa")
                if (s.startsWith(quote, i)) {
                    i += 3
                    // fino a due apici subito prima della chiusura appartengono al testo
                    var extra = 0
                    while (extra < 2 && i < s.length && s[i] == quote[0]) {
                        sb.append(quote[0])
                        i++
                        extra++
                    }
                    return sb.toString()
                }
                val c = s[i++]
                if (c == '\n') line++
                if (basic && c == '\\') {
                    // «\» a fine riga: salta gli spazi e gli a capo successivi
                    if (i < s.length && (s[i] == '\n' || s[i] == '\r' || s[i] == ' ' || s[i] == '\t')) {
                        var j = i
                        while (j < s.length && (s[j] == ' ' || s[j] == '\t')) j++
                        if (j < s.length && (s[j] == '\n' || s[j] == '\r')) {
                            while (j < s.length && (s[j] == ' ' || s[j] == '\t' || s[j] == '\n' || s[j] == '\r')) {
                                if (s[j] == '\n') line++
                                j++
                            }
                            i = j
                            continue
                        }
                    }
                    sb.append(parseEscape())
                } else {
                    sb.append(c)
                }
            }
        }

        private fun parseEscape(): String {
            if (i >= s.length) fail("Sequenza di escape incompleta")
            return when (val e = s[i++]) {
                'n' -> "\n"
                't' -> "\t"
                'r' -> "\r"
                'b' -> "\b"
                'f' -> "\u000C"
                '"' -> "\""
                '\\' -> "\\"
                'u' -> hex(4)
                'U' -> hex(8)
                else -> fail("Sequenza di escape non valida: \\$e")
            }
        }

        private fun hex(n: Int): String {
            if (i + n > s.length) fail("Sequenza di escape incompleta")
            val code = s.substring(i, i + n).toIntOrNull(16) ?: fail("Codice Unicode non valido")
            i += n
            return try {
                String(Character.toChars(code))
            } catch (e: IllegalArgumentException) {
                fail("Codice Unicode non valido")
            }
        }

        // ---------------------------------------------------------------- spazi

        private fun skipInlineSpace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        }

        /** Spazi, a capo e commenti. */
        private fun skipBlank() {
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\n' -> {
                        line++
                        i++
                    }
                    c == ' ' || c == '\t' || c == '\r' -> i++
                    c == '#' -> while (i < s.length && s[i] != '\n') i++
                    else -> return
                }
            }
        }

        private fun endOfLine() {
            skipInlineSpace()
            if (i < s.length && s[i] == '#') while (i < s.length && s[i] != '\n') i++
            if (i < s.length && s[i] == '\r') i++
            if (i < s.length) {
                if (s[i] != '\n') fail("Dopo il valore c'è altro testo")
                i++
                line++
            }
        }

        private fun expect(c: Char) {
            if (i >= s.length || s[i] != c) fail("Atteso '$c'")
            i++
        }

        private fun fail(message: String): Nothing = throw TomlException(message, line)
    }
}
