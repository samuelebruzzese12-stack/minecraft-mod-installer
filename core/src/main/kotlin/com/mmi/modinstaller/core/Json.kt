package com.mmi.modinstaller.core

class JsonParseException(message: String, val position: Int) : Exception("$message (posizione $position)")

/**
 * Parser JSON tollerante, in stile Minecraft: accetta BOM, commenti `//` e `/* */` e virgole finali.
 * I valori sono restituiti come Map / List / String / Long / Double / Boolean / null.
 * Contiene anche un serializzatore minimale usato per la persistenza locale.
 */
object Json {
    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? = Parser(text).parseDocument()

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> {
        val value = parse(text)
        return value as? Map<String, Any?> ?: throw JsonParseException("La radice del JSON non è un oggetto", 0)
    }

    fun stringify(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        write(sb, value, pretty, 0)
        return sb.toString()
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun parseDocument(): Any? {
            if (s.isNotEmpty() && s[0] == '﻿') pos = 1
            skipWs()
            if (pos >= s.length) throw JsonParseException("Documento JSON vuoto", pos)
            val v = readValue(0)
            skipWs()
            if (pos < s.length) throw JsonParseException("Contenuto inatteso dopo la fine del JSON", pos)
            return v
        }

        private fun skipWs() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '﻿') {
                    pos++
                } else if (c == '/' && pos + 1 < s.length && s[pos + 1] == '/') {
                    pos += 2
                    while (pos < s.length && s[pos] != '\n') pos++
                } else if (c == '/' && pos + 1 < s.length && s[pos + 1] == '*') {
                    val end = s.indexOf("*/", pos + 2)
                    if (end < 0) throw JsonParseException("Commento non chiuso", pos)
                    pos = end + 2
                } else {
                    return
                }
            }
        }

        private fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonParseException("JSON troppo annidato", pos)
            skipWs()
            if (pos >= s.length) throw JsonParseException("Fine inattesa del JSON", pos)
            val c = s[pos]
            return when {
                c == '{' -> readObject(depth)
                c == '[' -> readArray(depth)
                c == '"' -> readString()
                c == 't' -> readLiteral("true", true)
                c == 'f' -> readLiteral("false", false)
                c == 'n' -> readLiteral("null", null)
                c == '-' || c in '0'..'9' -> readNumber()
                else -> throw JsonParseException("Carattere inatteso '$c'", pos)
            }
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonParseException("Valore non valido", pos)
            pos += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // '{'
            while (true) {
                skipWs()
                if (pos >= s.length) throw JsonParseException("Oggetto non chiuso", pos)
                if (s[pos] == '}') {
                    pos++
                    return map
                }
                if (s[pos] != '"') throw JsonParseException("Nome di campo previsto", pos)
                val key = readString()
                skipWs()
                if (pos >= s.length || s[pos] != ':') throw JsonParseException("Carattere ':' previsto", pos)
                pos++
                map[key] = readValue(depth + 1)
                skipWs()
                if (pos >= s.length) throw JsonParseException("Oggetto non chiuso", pos)
                val c = s[pos]
                if (c == ',') {
                    pos++
                } else if (c == '}') {
                    pos++
                    return map
                } else {
                    throw JsonParseException("Carattere ',' o '}' previsto", pos)
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // '['
            while (true) {
                skipWs()
                if (pos >= s.length) throw JsonParseException("Array non chiuso", pos)
                if (s[pos] == ']') {
                    pos++
                    return list
                }
                list.add(readValue(depth + 1))
                skipWs()
                if (pos >= s.length) throw JsonParseException("Array non chiuso", pos)
                val c = s[pos]
                if (c == ',') {
                    pos++
                } else if (c == ']') {
                    pos++
                    return list
                } else {
                    throw JsonParseException("Carattere ',' o ']' previsto", pos)
                }
            }
        }

        private fun readString(): String {
            val start = pos
            pos++ // virgolette iniziali
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw JsonParseException("Stringa non chiusa", start)
                val c = s[pos++]
                if (c == '"') return sb.toString()
                if (c != '\\') {
                    sb.append(c)
                    continue
                }
                if (pos >= s.length) throw JsonParseException("Sequenza di escape incompleta", pos)
                val e = s[pos++]
                when (e) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (pos + 4 > s.length) throw JsonParseException("Escape unicode incompleto", pos)
                        val code = s.substring(pos, pos + 4).toIntOrNull(16)
                            ?: throw JsonParseException("Escape unicode non valido", pos)
                        sb.append(code.toChar())
                        pos += 4
                    }
                    else -> throw JsonParseException("Sequenza di escape non valida", pos - 1)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var isFloat = false
            if (pos < s.length && s[pos] == '.') {
                isFloat = true
                pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val text = s.substring(start, pos)
            if (isFloat) {
                return text.toDoubleOrNull() ?: throw JsonParseException("Numero non valido '$text'", start)
            }
            val asLong = text.toLongOrNull()
            if (asLong != null) return asLong
            return text.toDoubleOrNull() ?: throw JsonParseException("Numero non valido '$text'", start)
        }
    }

    private fun write(sb: StringBuilder, v: Any?, pretty: Boolean, indent: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(v.toString())
            is Double -> sb.append(if (v.isNaN() || v.isInfinite()) "null" else v.toString())
            is Float -> sb.append(if (v.isNaN() || v.isInfinite()) "null" else v.toString())
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                if (v.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) newline(sb, indent + 1)
                    writeString(sb, k.toString())
                    sb.append(if (pretty) ": " else ":")
                    write(sb, value, pretty, indent + 1)
                }
                if (pretty) newline(sb, indent)
                sb.append('}')
            }
            is Iterable<*> -> {
                val items = v.toList()
                if (items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                var first = true
                for (item in items) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) newline(sb, indent + 1)
                    write(sb, item, pretty, indent + 1)
                }
                if (pretty) newline(sb, indent)
                sb.append(']')
            }
            else -> writeString(sb, v.toString())
        }
    }

    private fun newline(sb: StringBuilder, indent: Int) {
        sb.append('\n')
        for (i in 0 until indent) sb.append("  ")
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch.code < 0x20 -> sb.append(String.format("\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }
}

@Suppress("UNCHECKED_CAST")
fun Any?.asJsonObject(): Map<String, Any?>? = this as? Map<String, Any?>

fun Any?.asJsonList(): List<Any?>? = this as? List<Any?>

fun Map<String, Any?>.str(key: String): String? = this[key] as? String

fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key].asJsonObject()

fun Map<String, Any?>.list(key: String): List<Any?>? = this[key].asJsonList()

fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()

fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()

fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
