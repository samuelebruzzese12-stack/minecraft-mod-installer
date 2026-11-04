package com.mmi.modinstaller.mods

import com.mmi.modinstaller.core.CancelCheck
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class FileDigests(val sha1: String, val sha256: String, val size: Long)

/** Impronte e copie di file con controllo della cancellazione. */
object Digests {

    private const val BUFFER = 64 * 1024

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    fun of(file: File, cancel: CancelCheck = {}): FileDigests {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sha1.update(buf, 0, n)
                sha256.update(buf, 0, n)
                size += n
                cancel()
            }
        }
        return FileDigests(hex(sha1.digest()), hex(sha256.digest()), size)
    }

    /** Copia [input] in [target] (scrivendo e forzando su disco) e restituisce le impronte di ciò che è stato scritto. */
    fun copy(input: InputStream, target: File, total: Long, cancel: CancelCheck, progress: ((Long, Long) -> Unit)?): FileDigests {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        var size = 0L
        java.io.FileOutputStream(target).use { fos ->
            val out: OutputStream = fos.buffered(BUFFER)
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sha1.update(buf, 0, n)
                sha256.update(buf, 0, n)
                out.write(buf, 0, n)
                size += n
                cancel()
                progress?.invoke(size, total)
            }
            out.flush()
            fos.fd.sync()
        }
        return FileDigests(hex(sha1.digest()), hex(sha256.digest()), size)
    }

    /** Sposta un file: prima con una rinomina atomica, poi (altro volume) con copia e cancellazione. [to] non deve esistere. */
    fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (to.exists()) throw IOException("Esiste già: ${to.name}")
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            // continua sotto
        } catch (e: IOException) {
            if (!from.exists()) throw e
        }
        if (from.renameTo(to)) return
        val tmp = File(to.parentFile, "." + to.name + ".mv")
        try {
            from.inputStream().use { input -> copy(input, tmp, from.length(), {}, null) }
            if (!tmp.renameTo(to)) throw IOException("Non riesco a spostare ${from.name}")
            from.delete()
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun deleteRecursively(file: File) {
        if (!file.exists()) return
        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }
}
