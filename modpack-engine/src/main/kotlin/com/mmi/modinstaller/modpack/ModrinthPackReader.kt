package com.mmi.modinstaller.modpack

import com.mmi.modinstaller.core.ArchiveCorruptException
import com.mmi.modinstaller.core.ArchiveException
import com.mmi.modinstaller.core.ArchivePasswordException
import com.mmi.modinstaller.core.ArchiveUnsafeException
import com.mmi.modinstaller.core.ArchiveUnsupportedException
import com.mmi.modinstaller.core.BoundedInputStream
import com.mmi.modinstaller.core.FindingCode
import com.mmi.modinstaller.core.Json
import com.mmi.modinstaller.core.JsonParseException
import com.mmi.modinstaller.core.LimitExceededException
import com.mmi.modinstaller.core.PathSafety
import com.mmi.modinstaller.core.SafetyLimits
import com.mmi.modinstaller.core.ZipArchiveReader
import com.mmi.modinstaller.core.asJsonObject
import com.mmi.modinstaller.core.formatBytes
import com.mmi.modinstaller.core.int
import com.mmi.modinstaller.core.list
import com.mmi.modinstaller.core.long
import com.mmi.modinstaller.core.obj
import com.mmi.modinstaller.core.str
import com.mmi.modinstaller.jar.ModLoader
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Legge un modpack Modrinth (`.mrpack`, formato 1): l'archivio deve contenere `modrinth.index.json` alla radice. Non scarica
 * nulla e non estrae nulla: controlla l'archivio (percorsi pericolosi, password, dimensioni, voci dichiarate che non
 * tornano) e ogni voce dell'indice. Una voce non valida non entra mai nel piano: finisce in [Pack.problems], così
 * un modpack con voci scartate non può essere dato per completo.
 */
object ModrinthPackReader {

    const val INDEX_NAME = "modrinth.index.json"

    /** Un indice più grande di così non è un modpack vero (i più grandi, con centinaia di mod, hanno poche centinaia di KB). */
    const val MAX_INDEX_BYTES = 16 * 1024 * 1024

    const val MAX_FILES = 5_000

    /** Totale dei file da scaricare oltre il quale il modpack non viene accettato. */
    const val MAX_DOWNLOAD_BYTES = 8L * 1024 * 1024 * 1024

    private const val MAX_URLS = 8
    private const val MAX_URL_CHARS = 2048
    private const val MAX_DEPTH = 16

    /** Cartelle dell'istanza che l'app usa per i propri fini: un modpack non può scriverci. */
    private val RESERVED_ROOTS = setOf(".trash")

    /** Tipi di file eseguibili fuori da Minecraft: mai scritti da un modpack, né scaricati né negli «overrides». */
    val REFUSED_EXTENSIONS: Set<String> = PathSafety.EXECUTABLE_EXTENSIONS - setOf("jar", "class", "dll", "so", "dylib")

    private val SHA1 = Regex("[0-9a-f]{40}")
    private val SHA512 = Regex("[0-9a-f]{128}")

    fun read(archive: File, limits: SafetyLimits = SafetyLimits()): PackReadResult {
        if (!archive.isFile) return PackReadResult.Failed("Il file non c'è più", "Scegli di nuovo il modpack.")
        if (archive.length() > limits.maxArchiveBytes) {
            return PackReadResult.Failed("Il file è troppo grande", "Il limite è ${formatBytes(limits.maxArchiveBytes)}.")
        }
        var indexBytes: ByteArray? = null
        var common = 0
        var client = 0
        var server = 0
        try {
            ZipArchiveReader.open(archive, limits).use { reader ->
                val declared = reader.totalSizeHint
                if (declared > limits.maxTotalUncompressedBytes) {
                    return PackReadResult.Failed("Il contenuto è troppo grande", "Decompresso supera ${formatBytes(limits.maxTotalUncompressedBytes)}.")
                }
                if (declared > limits.ratioCheckMinBytes && archive.length() > 0 && declared / archive.length() > limits.maxCompressionRatio) {
                    return PackReadResult.Failed("Il file è sospetto", "Il rapporto di compressione è anomalo (possibile «bomba» ZIP): non lo apro.")
                }
                reader.forEachEntry { entry, content ->
                    if (entry.isDirectory || content == null) return@forEachEntry
                    val name = (PathSafety.normalize(entry.rawName, limits.maxPathLength) as? PathSafety.Normalized.Ok)?.path ?: return@forEachEntry
                    when {
                        name == INDEX_NAME ->
                            indexBytes = BoundedInputStream(content, MAX_INDEX_BYTES.toLong(), FindingCode.ENTRY_TOO_LARGE, "L'indice del modpack").readBytes()
                        name.startsWith("overrides/") -> common++
                        name.startsWith("client-overrides/") -> client++
                        name.startsWith("server-overrides/") -> server++
                    }
                }
            }
        } catch (e: ArchiveException) {
            return PackReadResult.Failed(describe(e), null)
        } catch (e: IOException) {
            return PackReadResult.Failed("Non riesco a leggere il file", e.message)
        }
        val bytes = indexBytes ?: return PackReadResult.Failed(
            "Non è un modpack Modrinth", "Manca il file $INDEX_NAME alla radice dell'archivio."
        )
        return parse(bytes, OverrideCounts(common, client, server), limits)
    }

    private fun describe(e: ArchiveException): String = when (e) {
        is ArchiveUnsafeException -> "Il file contiene percorsi pericolosi (come «../»): non lo apro. ${e.message}"
        is ArchivePasswordException -> "Il file è protetto da password: non posso leggerlo."
        is LimitExceededException -> "Il file supera i limiti di sicurezza: ${e.message}"
        is ArchiveCorruptException -> "Il file è danneggiato o incompleto: ${e.message}"
        is ArchiveUnsupportedException -> e.message ?: "Formato dell'archivio non supportato."
        else -> e.message ?: "Archivio non leggibile."
    }

    /** Legge il testo dell'indice (esposto per le prove). */
    internal fun parse(bytes: ByteArray, overrides: OverrideCounts, limits: SafetyLimits = SafetyLimits()): PackReadResult {
        val root = try {
            Json.parse(String(bytes, Charsets.UTF_8)).asJsonObject()
        } catch (e: JsonParseException) {
            return PackReadResult.Failed("L'indice del modpack non è un JSON valido", e.message)
        } ?: return PackReadResult.Failed("L'indice del modpack non è valido", "La radice del JSON non è un oggetto.")

        val formatVersion = root.int("formatVersion")
            ?: return PackReadResult.Failed("L'indice del modpack non è valido", "Manca formatVersion.")
        if (formatVersion != 1) {
            return PackReadResult.Failed("Versione del formato non supportata", "Il modpack usa il formato $formatVersion, questa app legge solo il formato 1.")
        }
        val game = root.str("game")
        if (game != null && game != "minecraft") {
            return PackReadResult.Failed("Non è un modpack di Minecraft Java", "Il campo game dice «$game».")
        }
        val filesJson = root.list("files")
            ?: return PackReadResult.Failed("L'indice del modpack non è valido", "Manca l'elenco dei file.")
        if (filesJson.size > MAX_FILES) {
            return PackReadResult.Failed("Il modpack ha troppi file", "${filesJson.size} file: il limite è $MAX_FILES.")
        }

        val problems = ArrayList<String>()
        val deps = root.obj("dependencies").orEmpty()
        val minecraft = (deps["minecraft"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        var loader: ModLoader? = null
        var loaderVersion: String? = null
        for ((key, value) in deps) {
            val l = when (key) {
                "fabric-loader" -> ModLoader.FABRIC
                "quilt-loader" -> ModLoader.QUILT
                "forge" -> ModLoader.FORGE
                "neoforge" -> ModLoader.NEOFORGE
                else -> {
                    if (key != "minecraft") problems.add("Dipendenza sconosciuta nell'indice: «${clean(key)}»")
                    null
                }
            } ?: continue
            if (loader != null) {
                problems.add("L'indice indica più di un caricatore: uso ${loader.label} e ignoro ${l.label}")
                continue
            }
            loader = l
            loaderVersion = (value as? String)?.trim()?.takeIf { it.isNotEmpty() }
        }

        val files = ArrayList<PackFile>()
        val seen = HashSet<String>()
        var totalBytes = 0L
        for ((i, raw) in filesJson.withIndex()) {
            val m = raw.asJsonObject()
            if (m == null) {
                problems.add("Voce ${i + 1} dell'elenco file non valida")
                continue
            }
            val rawPath = m.str("path")
            val label = clean(rawPath ?: "voce ${i + 1}")
            val path = checkPath(rawPath, limits.maxPathLength)
            if (path.second != null) {
                problems.add("$label: ${path.second}")
                continue
            }
            val ok = path.first!!
            if (!seen.add(ok.lowercase(Locale.ROOT))) {
                problems.add("$label: percorso ripetuto nell'indice")
                continue
            }
            val hashes = m.obj("hashes").orEmpty()
            val sha1 = (hashes["sha1"] as? String)?.trim()?.lowercase(Locale.ROOT)
            val sha512 = (hashes["sha512"] as? String)?.trim()?.lowercase(Locale.ROOT)
            if ((hashes["sha1"] != null && (sha1 == null || !SHA1.matches(sha1))) ||
                (hashes["sha512"] != null && (sha512 == null || !SHA512.matches(sha512)))
            ) {
                problems.add("$label: impronta non valida")
                continue
            }
            if (sha1 == null && sha512 == null) {
                problems.add("$label: manca l'impronta (SHA-1 o SHA-512), il file non si potrebbe verificare")
                continue
            }
            val urls = m.list("downloads").orEmpty().mapNotNull { (it as? String)?.trim()?.takeIf { u -> u.isNotEmpty() } }
            if (urls.isEmpty()) {
                problems.add("$label: nessun indirizzo da cui scaricarlo")
                continue
            }
            if (urls.size > MAX_URLS || urls.any { it.length > MAX_URL_CHARS }) {
                problems.add("$label: indirizzi di download non validi")
                continue
            }
            val size = m.long("fileSize") ?: -1L
            if (size > limits.maxEntryBytes) {
                problems.add("$label: dichiarato troppo grande (${formatBytes(size)})")
                continue
            }
            val env = m.obj("env").orEmpty()
            files.add(
                PackFile(
                    path = ok, sha1 = sha1, sha512 = sha512, size = if (size >= 0) size else -1L, urls = urls,
                    client = side(env["client"]), server = side(env["server"])
                )
            )
            if (size > 0) totalBytes += size
        }
        if (totalBytes > MAX_DOWNLOAD_BYTES) {
            return PackReadResult.Failed("Il modpack è troppo grande", "Da scaricare ci sono ${formatBytes(totalBytes)}: il limite è ${formatBytes(MAX_DOWNLOAD_BYTES)}.")
        }
        val name = clean(root.str("name") ?: "").take(100).ifEmpty { "Modpack senza nome" }
        return PackReadResult.Ok(
            Pack(
                name = name,
                versionId = root.str("versionId")?.let { clean(it).take(60) }?.takeIf { it.isNotEmpty() },
                summary = root.str("summary")?.let { clean(it).take(300) }?.takeIf { it.isNotEmpty() },
                minecraft = minecraft,
                loader = loader,
                loaderVersion = loaderVersion,
                files = files,
                overrides = overrides,
                problems = problems
            )
        )
    }

    private fun side(value: Any?): Side = when ((value as? String)?.lowercase(Locale.ROOT)) {
        "optional" -> Side.OPTIONAL
        "unsupported" -> Side.UNSUPPORTED
        else -> Side.REQUIRED
    }

    /** Testo dell'indice reso innocuo per essere mostrato: niente caratteri di controllo, spazi ridotti. */
    private fun clean(text: String): String = text.filter { !it.isISOControl() }.replace(Regex("\\s+"), " ").trim()

    /** Percorso di un file dell'indice: (percorso normalizzato, null) oppure (null, motivo del rifiuto). */
    internal fun checkPath(raw: String?, maxLength: Int): Pair<String?, String?> {
        if (raw == null || raw.isBlank()) return null to "manca il percorso"
        if (raw.any { it.isISOControl() }) return null to "il percorso contiene caratteri di controllo"
        if (raw.indexOf('\\') >= 0) return null to "il percorso usa la barra rovesciata"
        if (raw.endsWith("/")) return null to "il percorso indica una cartella"
        val n = PathSafety.normalize(raw, maxLength)
        if (n is PathSafety.Normalized.Unsafe) return null to "percorso non sicuro (${n.reason})"
        val path = (n as PathSafety.Normalized.Ok).path
        if (path.isEmpty()) return null to "manca il percorso"
        val segments = path.split('/')
        if (segments.size > MAX_DEPTH) return null to "il percorso è troppo profondo"
        if (segments.first() in RESERVED_ROOTS) return null to "cartella riservata all'app"
        if (segments.any { it.startsWith(".") }) return null to "nome nascosto o riservato"
        if (PathSafety.extension(path) in REFUSED_EXTENSIONS) return null to "tipo di file non ammesso (eseguibile)"
        return path to null
    }
}
