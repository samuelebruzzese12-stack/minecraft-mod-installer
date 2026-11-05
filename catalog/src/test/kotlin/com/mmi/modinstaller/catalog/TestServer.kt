package com.mmi.modinstaller.catalog

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Piccolo server HTTP per i test: ogni risposta è decisa da un gestore, così si provano tagli della connessione,
 * Range ignorato, reindirizzamenti ed errori in modo ripetibile (su 127.0.0.1, porta assegnata dal sistema).
 */
class TestServer : AutoCloseable {

    class Request(val method: String, val path: String, val headers: Map<String, String>) {
        fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
    }

    class Response(
        val status: Int = 200,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
        /** Content-Length dichiarato (null = non dichiararlo). Diverso dalla lunghezza reale serve a simulare i tagli. */
        val declaredLength: Long? = body.size.toLong(),
        /** Invia solo questi byte del corpo e poi chiude di colpo la connessione. */
        val cutAfter: Int? = null,
        val chunkSize: Int = 16 * 1024,
        val delayPerChunkMs: Long = 0,
        /** Corpo a pezzi (Transfer-Encoding: chunked), senza dimensione dichiarata. */
        val chunked: Boolean = false,
        /** Il taglio avviene con un reset della connessione (errore per il client) invece che con una chiusura normale. */
        val resetOnCut: Boolean = false
    )

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val requests = CopyOnWriteArrayList<Request>()
    val active = AtomicInteger(0)
    val maxActive = AtomicInteger(0)

    @Volatile
    var handler: (Request) -> Response = { Response(404) }

    @Volatile
    private var closed = false

    init {
        thread(isDaemon = true, name = "test-server") {
            while (!closed) {
                val socket = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                thread(isDaemon = true, name = "test-conn") { serve(socket) }
            }
        }
    }

    fun url(path: String): String = "http://127.0.0.1:$port$path"

    private fun serve(socket: Socket) {
        val now = active.incrementAndGet()
        while (true) {
            val max = maxActive.get()
            if (now <= max || maxActive.compareAndSet(max, now)) break
        }
        try {
            socket.soTimeout = 20_000
            val input = BufferedInputStream(socket.getInputStream())
            val request = readRequest(input) ?: return
            requests.add(request)
            val response = handler(request)
            write(socket.getOutputStream(), response, socket, request.method == "HEAD")
        } catch (e: Exception) {
            // il client ha chiuso (pausa/annulla): normale nei test
        } finally {
            active.decrementAndGet()
            try {
                socket.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun readRequest(input: BufferedInputStream): Request? {
        val first = readLine(input) ?: return null
        val parts = first.split(" ")
        if (parts.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase(Locale.ROOT)] = line.substring(idx + 1).trim()
        }
        return Request(parts[0], parts[1], headers)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun write(out: OutputStream, r: Response, socket: Socket, headOnly: Boolean) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n")
        for ((k, v) in r.headers) head.append(k).append(": ").append(v).append("\r\n")
        if (r.chunked) {
            head.append("Transfer-Encoding: chunked\r\n")
        } else if (r.declaredLength != null) {
            head.append("Content-Length: ").append(r.declaredLength).append("\r\n")
        }
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
        if (headOnly) return
        val limit = r.cutAfter ?: r.body.size
        var sent = 0
        while (sent < limit) {
            val n = minOf(r.chunkSize, limit - sent)
            if (r.chunked) {
                out.write((Integer.toHexString(n) + "\r\n").toByteArray(Charsets.ISO_8859_1))
                out.write(r.body, sent, n)
                out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
            } else {
                out.write(r.body, sent, n)
            }
            out.flush()
            sent += n
            if (r.delayPerChunkMs > 0 && sent < limit) Thread.sleep(r.delayPerChunkMs)
        }
        if (r.cutAfter != null) {
            // Taglio: la risposta finisce prima di quanto dichiarato (con reset oppure con una chiusura normale).
            if (r.resetOnCut) {
                try {
                    socket.setSoLinger(true, 0)
                } catch (e: Exception) {
                }
            }
            return
        }
        if (r.chunked) out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        403 -> "Forbidden"
        404 -> "Not Found"
        416 -> "Range Not Satisfiable"
        429 -> "Too Many Requests"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    override fun close() {
        closed = true
        try {
            server.close()
        } catch (e: Exception) {
        }
    }

    companion object {
        fun tmpDir(): java.io.File = Files.createTempDirectory("mmi-dl").toFile()

        fun deleteTree(f: java.io.File) {
            if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
            f.delete()
        }

        /** Contenuto di prova non ripetitivo (un file di soli zeri nasconderebbe errori di offset). */
        fun bytes(size: Int, seed: Int = 7): ByteArray {
            val out = ByteArray(size)
            var x = seed
            for (i in 0 until size) {
                x = x * 1103515245 + 12345
                out[i] = (x ushr 16).toByte()
            }
            return out
        }

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { String.format("%02x", it.toInt() and 0xff) }

        fun sha256(data: ByteArray): String = hex(java.security.MessageDigest.getInstance("SHA-256").digest(data))
    }
}
