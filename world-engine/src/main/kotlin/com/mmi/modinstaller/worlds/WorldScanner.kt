package com.mmi.modinstaller.worlds

import com.mmi.modinstaller.core.ArchiveCorruptException
import com.mmi.modinstaller.core.ArchiveException
import com.mmi.modinstaller.core.ArchivePasswordException
import com.mmi.modinstaller.core.ArchiveUnsafeException
import com.mmi.modinstaller.core.ArchiveUnsupportedException
import com.mmi.modinstaller.core.CancelCheck
import com.mmi.modinstaller.core.LimitExceededException
import com.mmi.modinstaller.core.PathSafety
import com.mmi.modinstaller.core.SafetyLimits
import com.mmi.modinstaller.core.ZipArchiveReader
import com.mmi.modinstaller.core.formatBytes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Trova i mondi dentro un archivio (`.zip`, `.mcworld`) e li descrive senza estrarli: legge l'elenco dei file e `level.dat`,
 * nient'altro. Un mondo è una cartella con `level.dat`; se è Bedrock ha anche `db/` (LevelDB), se è Java ha le regioni
 * (`region/`, `DIM-1/`, `DIM1/`, `dimensions/…`).
 */
object WorldScanner {

    private const val MAX_WORLDS = 20

    /** Una voce dell'archivio con il percorso già controllato. */
    internal class Item(val path: String, val size: Long)

    fun scan(archive: File, limits: SafetyLimits = WorldLimits.DEFAULT, cancel: CancelCheck = {}): WorldScanResult {
        // Prima i controlli di sicurezza dell'archivio (percorsi pericolosi, password, compressione, limiti, file troncato).
        try {
            ZipArchiveReader.open(archive, limits).close()
        } catch (e: ArchiveUnsafeException) {
            return WorldScanResult.Failed("L'archivio contiene percorsi pericolosi: non lo apro.", e.paths.take(3).joinToString(", "))
        } catch (e: ArchivePasswordException) {
            return WorldScanResult.Failed("L'archivio è protetto da password.")
        } catch (e: ArchiveUnsupportedException) {
            return WorldScanResult.Failed(e.message ?: "Formato dell'archivio non supportato.")
        } catch (e: LimitExceededException) {
            return WorldScanResult.Failed(e.message ?: "L'archivio supera i limiti consentiti.")
        } catch (e: ArchiveCorruptException) {
            return WorldScanResult.Failed("L'archivio non è uno ZIP valido o è danneggiato (download interrotto?).", e.message)
        } catch (e: ArchiveException) {
            return WorldScanResult.Failed(e.message ?: "Archivio non leggibile.")
        } catch (e: IOException) {
            return WorldScanResult.Failed("Archivio non leggibile.", e.message)
        }

        return try {
            ZipFile(archive).use { zip -> scanZip(zip, archive.length(), limits, cancel) }
        } catch (e: IOException) {
            WorldScanResult.Failed("Archivio non leggibile.", e.message)
        }
    }

    private fun scanZip(zip: ZipFile, archiveBytes: Long, limits: SafetyLimits, cancel: CancelCheck): WorldScanResult {
        val items = ArrayList<Item>()
        val levelDats = HashMap<String, java.util.zip.ZipEntry>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (items.size % 2048 == 0) cancel()
            if (e.isDirectory || e.name.endsWith("\\")) continue
            val path = (PathSafety.normalize(e.name, limits.maxPathLength) as? PathSafety.Normalized.Ok)?.path ?: continue
            if (path.isEmpty() || PathSafety.isJunk(path)) continue
            items.add(Item(path, e.size.coerceAtLeast(0L)))
            if (PathSafety.fileName(path) == "level.dat") levelDats[PathSafety.parentOf(path)] = e
        }
        if (levelDats.isEmpty()) {
            return WorldScanResult.Failed(
                "Nell'archivio non c'è nessun mondo: manca il file level.dat.",
                "Un mondo è una cartella con level.dat (e, per Bedrock, la cartella db)."
            )
        }
        // Le cartelle con level.dat dentro un altro mondo (copie di sicurezza, cartelle annidate) non sono mondi a sé.
        val roots = levelDats.keys.filter { r -> levelDats.keys.none { o -> o != r && PathSafety.isUnder(r, o) } }.sorted()
        val worlds = ArrayList<WorldInfo>()
        for (root in roots.take(MAX_WORLDS)) {
            cancel()
            val own = items.filter { PathSafety.isUnder(it.path, root) }
            val levelBytes = readBounded(zip, levelDats.getValue(root), limits.maxLevelDatBytes)
            worlds.add(describe(root, own, levelBytes, limits))
        }
        if (roots.size > MAX_WORLDS) {
            return WorldScanResult.Failed("L'archivio contiene più di $MAX_WORLDS mondi: dividilo in archivi più piccoli.")
        }
        // File fuori da tutti i mondi (un LEGGIMI, una copia dell'archivio…): non si importano, ma lo si dice.
        val outside = items.count { item -> roots.none { PathSafety.isUnder(item.path, it) } }
        if (outside > 0 && worlds.size == 1) {
            val w = worlds[0]
            worlds[0] = w.withNote(WorldNote(WorldNoteLevel.INFO, "Nell'archivio ci sono $outside file fuori dal mondo: non fanno parte del mondo e non vengono copiati."))
        }
        return WorldScanResult.Ok(worlds, archiveBytes)
    }

    /**
     * Descrive un mondo già estratto (una cartella con `level.dat`, per esempio dentro `saves` di un profilo). Null se la
     * cartella non è un mondo. Non cambia nulla.
     */
    fun scanFolder(dir: File, limits: SafetyLimits = WorldLimits.DEFAULT, cancel: CancelCheck = {}): WorldInfo? {
        val level = File(dir, "level.dat")
        if (!dir.isDirectory || !level.isFile) return null
        val items = ArrayList<Item>()
        val base = dir.canonicalFile
        for (f in base.walkTopDown().onEnter { !it.name.startsWith(".") || it == base }) {
            if (items.size % 2048 == 0) cancel()
            if (!f.isFile || f.name == "session.lock" || f.name.startsWith(".")) continue
            items.add(Item(dir.name + "/" + f.relativeTo(base).path.replace(File.separatorChar, '/'), f.length()))
        }
        val bytes = if (level.length() <= limits.maxLevelDatBytes) level.readBytes() else null
        return describe(dir.name, items, bytes, limits)
    }

    private fun readBounded(zip: ZipFile, entry: java.util.zip.ZipEntry, max: Int): ByteArray? = try {
        if (entry.size > max) {
            null
        } else {
            zip.getInputStream(entry).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) return null
                }
                out.toByteArray()
            }
        }
    } catch (e: IOException) {
        null
    }

    private fun WorldInfo.withNote(note: WorldNote) = WorldInfo(
        root, edition, levelName, version, dataVersion, gameType, hardcore, files, bytes, dimensions, players, datapacks, hasIcon,
        notes + note
    )

    /** Il percorso di [item] dentro il mondo (senza la cartella del mondo). */
    private fun rel(root: String, path: String) = if (root.isEmpty()) path else path.substring(root.length + 1)

    internal fun describe(root: String, own: List<Item>, levelBytes: ByteArray?, limits: SafetyLimits): WorldInfo {
        val notes = ArrayList<WorldNote>()
        val rels = own.map { rel(root, it.path) }
        val hasDb = rels.any { it == "db/CURRENT" || (it.startsWith("db/") && PathSafety.fileName(it).startsWith("MANIFEST-")) }
        val bedrock = hasDb || (levelBytes != null && LevelDat.looksBedrock(levelBytes))
        val copied = own.filter { PathSafety.fileName(it.path) != "session.lock" }
        val files = copied.size
        val bytes = copied.sumOf { it.size }

        if (bedrock) {
            val level = levelBytes?.let { LevelDat.readBedrock(it) }
            if (level == null) notes.add(WorldNote(WorldNoteLevel.ERROR, "level.dat non leggibile: il mondo è danneggiato o incompleto."))
            if (!hasDb) notes.add(WorldNote(WorldNoteLevel.ERROR, "Manca la cartella db (i dati del mondo): il mondo è incompleto."))
            notes.add(WorldNote(WorldNoteLevel.INFO, "È un mondo Bedrock: si importa in Minecraft Bedrock dall'INSTALLER BEDROCK."))
            return WorldInfo(
                root, WorldEdition.BEDROCK, level?.levelName, level?.version, null, level?.gameType, false, files, bytes,
                emptyList(), 0, emptyList(), rels.contains("world_icon.jpeg") || rels.contains("world_icon.png"), notes
            )
        }

        val level = levelBytes?.let { LevelDat.readJava(it, limits.maxLevelDatBytes * 4) }
        if (level == null) {
            notes.add(
                WorldNote(
                    WorldNoteLevel.ERROR,
                    if (levelBytes == null) "level.dat è troppo grande o non leggibile: il mondo non si può importare."
                    else "level.dat non è un file di Minecraft Java leggibile: il mondo è danneggiato o non è un mondo."
                )
            )
        } else if (level.version == null) {
            notes.add(WorldNote(WorldNoteLevel.INFO, "level.dat non indica la versione di Minecraft (mondo creato prima della 1.9?)."))
        }

        val dims = ArrayList<Dimension>()
        fun regions(prefix: String) = rels.count { it.startsWith(prefix) && it.indexOf('/', prefix.length) < 0 && it.endsWith(".mca") }
        dims.add(Dimension("minecraft:overworld", "Mondo principale", regions("region/")))
        regions("DIM-1/region/").takeIf { it > 0 }?.let { dims.add(Dimension("minecraft:the_nether", "Nether", it)) }
        regions("DIM1/region/").takeIf { it > 0 }?.let { dims.add(Dimension("minecraft:the_end", "End", it)) }
        // dimensions/<spazio>/<nome…>/region/r.x.z.mca (Minecraft 1.16 e successivi, dimensioni dei data pack)
        val custom = LinkedHashMap<String, Int>()
        for (r in rels) {
            if (!r.startsWith("dimensions/") || !r.endsWith(".mca")) continue
            val parts = r.split('/')
            val at = parts.lastIndexOf("region")
            if (at < 3 || at != parts.size - 2) continue
            val id = parts[1] + ":" + parts.subList(2, at).joinToString("/")
            custom[id] = (custom[id] ?: 0) + 1
        }
        for ((id, n) in custom) dims.add(Dimension(id, id, n))
        if (dims.sumOf { it.regionFiles } == 0) {
            notes.add(WorldNote(WorldNoteLevel.WARNING, "Il mondo non ha regioni salvate: non è mai stato esplorato o l'archivio è incompleto."))
        }
        if (rels.any { it.endsWith(".mcr") }) {
            notes.add(WorldNote(WorldNoteLevel.WARNING, "Ci sono regioni nel formato molto vecchio McRegion (prima di Minecraft 1.2): le converte il gioco all'apertura."))
        }
        if (rels.contains("session.lock")) {
            notes.add(WorldNote(WorldNoteLevel.INFO, "session.lock non viene copiato: lo ricrea il gioco quando apre il mondo."))
        }
        val players = rels.count { it.startsWith("playerdata/") && it.indexOf('/', "playerdata/".length) < 0 && it.endsWith(".dat") }
        val datapacks = rels.filter { it.startsWith("datapacks/") }
            .map { it.removePrefix("datapacks/").substringBefore('/') }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
        if (bytes > 2L * 1024 * 1024 * 1024) {
            notes.add(WorldNote(WorldNoteLevel.INFO, "Mondo grande (" + formatBytes(bytes) + "): l'importazione richiede tempo e spazio."))
        }
        return WorldInfo(
            root, WorldEdition.JAVA, level?.levelName, level?.version, level?.dataVersion, level?.gameType, level?.hardcore == true,
            files, bytes, dims, players, datapacks, rels.contains("icon.png"), notes
        )
    }
}
