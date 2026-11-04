package com.mmi.modinstaller.core

import java.text.Normalizer

/** Normalizzazione dei percorsi interni agli archivi e rilevamento di voci pericolose o inutili. */
object PathSafety {

    val EXECUTABLE_EXTENSIONS: Set<String> = setOf(
        "exe", "dll", "so", "dylib", "apk", "jar", "class", "dex", "bat", "cmd", "com", "scr",
        "msi", "vbs", "ps1", "sh", "lnk", "hta", "reg", "pif", "wsf", "appimage"
    )

    private val JUNK_NAMES = setOf(".ds_store", "thumbs.db", "desktop.ini")

    sealed class Normalized {
        class Ok(val path: String) : Normalized()
        class Unsafe(val reason: String) : Normalized()
    }

    /**
     * Converte `\` in `/`, elimina segmenti vuoti e `.`, rifiuta `..`, percorsi assoluti e lettere di unità.
     * Un risultato [Normalized.Ok] con percorso vuoto indica la radice dell'archivio.
     */
    fun normalize(raw: String, maxLength: Int = 512): Normalized {
        if (raw.indexOf('\u0000') >= 0) return Normalized.Unsafe("contiene caratteri nulli")
        val s = raw.replace('\\', '/')
        if (s.startsWith("/")) return Normalized.Unsafe("percorso assoluto")
        if (s.length >= 2 && s[1] == ':' && s[0].isLetter()) return Normalized.Unsafe("percorso con unità disco")
        val parts = ArrayList<String>()
        for (seg in s.split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") return Normalized.Unsafe("contiene '..' (path traversal)")
            parts.add(seg)
        }
        val joined = parts.joinToString("/")
        if (joined.length > maxLength) return Normalized.Unsafe("percorso troppo lungo")
        return Normalized.Ok(joined)
    }

    fun fileName(path: String): String = path.substringAfterLast('/')

    fun parentOf(path: String): String {
        val i = path.lastIndexOf('/')
        return if (i < 0) "" else path.substring(0, i)
    }

    fun extension(path: String): String {
        val name = fileName(path)
        val i = name.lastIndexOf('.')
        return if (i < 0) "" else name.substring(i + 1).lowercase()
    }

    fun isExecutable(path: String): Boolean = extension(path) in EXECUTABLE_EXTENSIONS

    fun isJunk(path: String): Boolean {
        val segs = path.split('/')
        for (seg in segs) {
            if (seg == "__MACOSX" || seg == ".git" || seg == ".svn") return true
        }
        val name = segs.last()
        return name.lowercase() in JUNK_NAMES || name.startsWith("._")
    }

    /** True se [path] è dentro la cartella [root] ("" = radice). */
    fun isUnder(path: String, root: String): Boolean =
        if (root.isEmpty()) path.isNotEmpty() else path.startsWith("$root/")

    fun isUnderOrEqual(path: String, root: String): Boolean = path == root || isUnder(path, root)

    fun join(root: String, name: String): String = if (root.isEmpty()) name else "$root/$name"

    fun depthOf(root: String): Int = if (root.isEmpty()) 0 else root.count { it == '/' } + 1

    /**
     * Nome file sicuro per Android/Minecraft: solo `[A-Za-z0-9_-]` (alcuni caratteri, come `&`,
     * impediscono a Minecraft di importare il file).
     */
    fun sanitizeFileName(name: String, fallback: String = "contenuto", maxLen: Int = 64): String {
        val decomposed = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val sb = StringBuilder()
        for (ch in decomposed) {
            val ok = (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '-'
            sb.append(if (ok) ch else '_')
        }
        var out = sb.toString().replace(Regex("_+"), "_").trim('_', '-')
        if (out.length > maxLen) out = out.substring(0, maxLen).trim('_', '-')
        return if (out.isEmpty()) fallback else out
    }
}
