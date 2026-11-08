package com.mmi.modinstaller.modpack

import com.mmi.modinstaller.core.ArchiveException
import com.mmi.modinstaller.core.BoundedInputStream
import com.mmi.modinstaller.core.FindingCode
import com.mmi.modinstaller.core.Json
import com.mmi.modinstaller.core.JsonParseException
import com.mmi.modinstaller.core.PathSafety
import com.mmi.modinstaller.core.SafetyLimits
import com.mmi.modinstaller.core.ZipArchiveReader
import com.mmi.modinstaller.core.asJsonList
import com.mmi.modinstaller.core.asJsonObject
import com.mmi.modinstaller.core.list
import com.mmi.modinstaller.core.obj
import com.mmi.modinstaller.core.str
import com.mmi.modinstaller.jar.ModLoader
import java.io.File
import java.io.IOException
import java.util.Locale

/** Una mod dell'elenco di un modpack CurseForge: dal manifest i numeri, da modlist.html (se c'è) nome e indirizzo. */
class CurseForgeMod(val projectId: Long, val fileId: Long, val name: String?, val slug: String?, val author: String?) {
    /** Il nome pulito per cercarlo altrove: senza «[TaCZ]», «(by autore)» e simili. */
    val searchName: String?
        get() = name?.replace(Regex("\\[[^\\]]*\\]"), " ")?.replace(Regex("\\(by [^)]*\\)", RegexOption.IGNORE_CASE), " ")
            ?.replace(Regex("[|/\\-–—:]+"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
}

/** Quello che un modpack CurseForge dice di sé (senza i file: quelli vanno trovati, vedi [CurseForgeResolver]). */
class CurseForgePack(
    val name: String,
    val version: String?,
    val author: String?,
    val minecraft: String?,
    val loader: ModLoader?,
    val loaderVersion: String?,
    val mods: List<CurseForgeMod>,
    val overrides: OverrideCounts
)

/**
 * Legge un modpack di CurseForge (archivio con `manifest.json` di tipo `minecraftModpack` alla radice e, di solito,
 * `modlist.html`). Il manifest non ha né indirizzi né impronte: solo i numeri dei progetti e dei file di CurseForge, che
 * l'app non può scaricare (serve una chiave personale). Da qui si ricavano i nomi, per cercare le stesse mod su Modrinth.
 */
object CurseForgePackReader {

    const val MANIFEST = "manifest.json"
    const val MODLIST = "modlist.html"
    private const val MAX_BYTES = 8 * 1024 * 1024

    /** È un archivio di CurseForge? Guarda solo i nomi delle voci alla radice. */
    fun looksLike(archive: File, limits: SafetyLimits = SafetyLimits()): Boolean = try {
        var found = false
        ZipArchiveReader.open(archive, limits).use { reader ->
            reader.forEachEntry { entry, content ->
                if (entry.isDirectory || content == null) return@forEachEntry
                val name = (PathSafety.normalize(entry.rawName, limits.maxPathLength) as? PathSafety.Normalized.Ok)?.path ?: return@forEachEntry
                if (name == MANIFEST) {
                    val text = String(BoundedInputStream(content, MAX_BYTES.toLong(), FindingCode.ENTRY_TOO_LARGE, "Il manifest").readBytes(), Charsets.UTF_8)
                    found = text.contains("minecraftModpack")
                }
            }
        }
        found
    } catch (e: Exception) {
        false
    }

    sealed class Result {
        class Ok(val pack: CurseForgePack) : Result()
        class Failed(val message: String, val detail: String? = null) : Result()
    }

    fun read(archive: File, limits: SafetyLimits = SafetyLimits()): Result {
        if (!archive.isFile) return Result.Failed("Il file non c'è più", "Scegli di nuovo il modpack.")
        var manifest: ByteArray? = null
        var modlist: ByteArray? = null
        var common = 0
        try {
            ZipArchiveReader.open(archive, limits).use { reader ->
                reader.forEachEntry { entry, content ->
                    if (entry.isDirectory || content == null) return@forEachEntry
                    val name = (PathSafety.normalize(entry.rawName, limits.maxPathLength) as? PathSafety.Normalized.Ok)?.path ?: return@forEachEntry
                    when {
                        name == MANIFEST -> manifest = BoundedInputStream(content, MAX_BYTES.toLong(), FindingCode.ENTRY_TOO_LARGE, "Il manifest").readBytes()
                        name == MODLIST -> modlist = BoundedInputStream(content, MAX_BYTES.toLong(), FindingCode.ENTRY_TOO_LARGE, "L'elenco delle mod").readBytes()
                        name.startsWith("overrides/") -> common++
                    }
                }
            }
        } catch (e: ArchiveException) {
            return Result.Failed(e.message ?: "Archivio non leggibile.")
        } catch (e: IOException) {
            return Result.Failed("Non riesco a leggere il file", e.message)
        }
        val bytes = manifest ?: return Result.Failed("Non è un modpack CurseForge", "Manca $MANIFEST alla radice dell'archivio.")
        return parse(bytes, modlist?.let { String(it, Charsets.UTF_8) }, OverrideCounts(common, 0, 0))
    }

    internal fun parse(manifest: ByteArray, modlist: String?, overrides: OverrideCounts): Result {
        val root = try {
            Json.parse(String(manifest, Charsets.UTF_8)).asJsonObject()
        } catch (e: JsonParseException) {
            return Result.Failed("Il manifest del modpack non è un JSON valido", e.message)
        } ?: return Result.Failed("Il manifest del modpack non è valido")
        if (root.str("manifestType") != "minecraftModpack") return Result.Failed("Non è un modpack CurseForge", "manifestType diverso da minecraftModpack.")
        val mc = root.obj("minecraft")
        val loaderId = mc?.list("modLoaders")?.firstNotNullOfOrNull { it.asJsonObject()?.str("id") }
        val loader = when {
            loaderId == null -> null
            loaderId.startsWith("neoforge") -> ModLoader.NEOFORGE
            loaderId.startsWith("forge") -> ModLoader.FORGE
            loaderId.startsWith("fabric") -> ModLoader.FABRIC
            loaderId.startsWith("quilt") -> ModLoader.QUILT
            else -> null
        }
        // modlist.html: <li><a href="https://www.curseforge.com/minecraft/mc-mods/slug">Nome (by Autore)</a></li>, nell'ordine del manifest.
        val links = modlist?.let {
            Regex("<a href=\"([^\"]+)\">([^<]+)</a>").findAll(it).map { m -> m.groupValues[1] to unescape(m.groupValues[2]) }.toList()
        }.orEmpty()
        val files = root.list("files").orEmpty().mapNotNull { it.asJsonObject() }
        val mods = files.mapIndexed { i, f ->
            val link = links.getOrNull(i)
            val label = link?.second?.let { clean(it).take(120) }
            val author = label?.let { Regex("\\(by ([^)]*)\\)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.trim() }
            CurseForgeMod(
                (f["projectID"] as? Number)?.toLong() ?: -1L, (f["fileID"] as? Number)?.toLong() ?: -1L,
                label, link?.first?.trimEnd('/')?.substringAfterLast('/')?.takeIf { s -> s.matches(Regex("[a-z0-9_.-]{1,80}")) }, author
            )
        }
        return Result.Ok(
            CurseForgePack(
                clean(root.str("name") ?: "").take(100).ifEmpty { "Modpack CurseForge" }, root.str("version")?.let { clean(it).take(60) },
                root.str("author")?.let { clean(it).take(60) }?.takeIf { it.isNotEmpty() }, mc?.str("version"), loader,
                loaderId?.substringAfter('-', "")?.takeIf { it.isNotEmpty() }, mods, overrides
            )
        )
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

    private fun clean(text: String): String = text.filter { !it.isISOControl() }.replace(Regex("\\s+"), " ").trim()
}

/** Un modo per trovare le mod su un altro servizio: il catalogo dell'app (Modrinth) nella build vera, una finta nelle prove. */
interface ModFinder {
    class Found(val title: String, val fileName: String, val url: String, val size: Long, val sha1: String?, val sha512: String?)

    /** La versione di una mod (per nome o indirizzo «slug») adatta a questa Minecraft e a questo caricatore, o null. */
    fun find(mod: CurseForgeMod, minecraft: String?, loader: ModLoader?): Found?
}

/**
 * Trasforma un modpack CurseForge in un [Pack] scaricabile cercando ogni mod su Modrinth, con la stessa versione di
 * Minecraft e lo stesso caricatore. Le mod che su Modrinth non ci sono (o non hanno una versione adatta) finiscono tra i
 * problemi del pacchetto, con il nome: il risultato non viene mai dato per completo. Shader e librerie di sola grafica
 * si cercano lo stesso: è il motore dei download a dire cosa è arrivato.
 */
object CurseForgeResolver {

    fun resolve(cf: CurseForgePack, finder: ModFinder, cancel: () -> Unit = {}, progress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> }): Pack {
        val files = ArrayList<PackFile>()
        val problems = ArrayList<String>()
        val seen = HashSet<String>()
        problems.add("Modpack di CurseForge: le mod si scaricano da Modrinth (CurseForge non lo permette senza una chiave personale)")
        for ((i, mod) in cf.mods.withIndex()) {
            cancel()
            val label = mod.name ?: "progetto CurseForge ${mod.projectId}"
            progress(i, cf.mods.size, label)
            val found = try {
                finder.find(mod, cf.minecraft, cf.loader)
            } catch (e: Exception) {
                problems.add("$label: ricerca su Modrinth non riuscita (${e.message ?: "errore"})")
                continue
            }
            if (found == null) {
                problems.add("$label: non è su Modrinth per Minecraft ${cf.minecraft ?: "?"} con ${cf.loader?.label ?: "questo caricatore"}")
                continue
            }
            val fileName = found.fileName.substringAfterLast('/').substringAfterLast('\\')
            val ext = PathSafety.extension(fileName)
            val folder = when (ext) {
                "jar" -> "mods"
                "zip" -> if (found.title.lowercase(Locale.ROOT).contains("shader")) "shaderpacks" else "resourcepacks"
                else -> {
                    problems.add("$label: file di tipo «$ext» non ammesso")
                    continue
                }
            }
            val path = ModrinthPackReader.checkPath("$folder/$fileName", 240)
            if (path.second != null) {
                problems.add("$label: ${path.second}")
                continue
            }
            if (!seen.add(path.first!!.lowercase(Locale.ROOT))) continue
            if (found.sha1 == null && found.sha512 == null) {
                problems.add("$label: Modrinth non dà l'impronta del file")
                continue
            }
            if (!found.url.startsWith("https://")) {
                problems.add("$label: indirizzo di download non sicuro")
                continue
            }
            files.add(PackFile(path.first!!, found.sha1, found.sha512, if (found.size > 0) found.size else -1L, listOf(found.url), Side.REQUIRED, Side.OPTIONAL))
        }
        progress(cf.mods.size, cf.mods.size, "")
        return Pack(
            name = cf.name, versionId = cf.version, summary = cf.author?.let { "di $it (CurseForge)" } ?: "modpack CurseForge",
            minecraft = cf.minecraft, loader = cf.loader, loaderVersion = cf.loaderVersion, files = files, overrides = cf.overrides, problems = problems
        )
    }
}
