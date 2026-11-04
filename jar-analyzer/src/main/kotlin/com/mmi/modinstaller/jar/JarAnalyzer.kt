package com.mmi.modinstaller.jar

import com.mmi.modinstaller.core.ArchiveCorruptException
import com.mmi.modinstaller.core.ArchiveException
import com.mmi.modinstaller.core.ArchiveFormat
import com.mmi.modinstaller.core.ArchivePasswordException
import com.mmi.modinstaller.core.ArchiveUnsafeException
import com.mmi.modinstaller.core.ArchiveUnsupportedException
import com.mmi.modinstaller.core.CancelCheck
import com.mmi.modinstaller.core.Finding
import com.mmi.modinstaller.core.FindingCode
import com.mmi.modinstaller.core.FormatSniffer
import com.mmi.modinstaller.core.Json
import com.mmi.modinstaller.core.JsonParseException
import com.mmi.modinstaller.core.LimitExceededException
import com.mmi.modinstaller.core.PathSafety
import com.mmi.modinstaller.core.ProgressCallback
import com.mmi.modinstaller.core.SafetyLimits
import com.mmi.modinstaller.core.Severity
import com.mmi.modinstaller.core.ZipArchiveReader
import com.mmi.modinstaller.core.asJsonObject
import com.mmi.modinstaller.core.formatBytes
import com.mmi.modinstaller.core.list
import com.mmi.modinstaller.core.obj
import com.mmi.modinstaller.core.str
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

class JarLimits(
    val maxFileBytes: Long = 768L * 1024 * 1024,
    val maxMetadataBytes: Int = 1024 * 1024,
    val maxNestedJarBytes: Long = 64L * 1024 * 1024,
    val maxNestedTotalBytes: Long = 256L * 1024 * 1024,
    val maxNestedJars: Int = 400,
    val maxBundleJars: Int = 500,
    val safety: SafetyLimits = SafetyLimits()
)

/**
 * Analisi statica di un .jar o .zip di Java Edition: legge solo l'elenco dei file e i file di descrizione (fabric.mod.json,
 * quilt.mod.json, mods.toml, mcmod.info, pack.mcmeta, indici dei modpack) e l'intestazione delle classi. Nessuna parte del
 * codice della mod viene caricata o eseguita. Gli archivi sono letti con gli stessi controlli degli altri file dell'app
 * (percorsi pericolosi, CRC, dimensioni, bombe di compressione).
 */
class JarAnalyzer(
    private val limits: JarLimits = JarLimits(),
    private val tempDir: File = File(System.getProperty("java.io.tmpdir") ?: ".")
) {

    private class Scan {
        val names = LinkedHashMap<String, Long>()
        val meta = HashMap<String, ByteArray>()
        val oversize = ArrayList<String>()
        val manifestProbes = ArrayList<Pair<String, ByteArray>>()
        val nested = ArrayList<NestedJar>()
        val nativeLibs = ArrayList<String>()
        val rootMixins = ArrayList<String>()
        val bundleJars = ArrayList<String>()
        var entryCount = 0
        var classCount = 0
        var maxClassMajor = 0
        var signed = false
        var nestedBytes = 0L
        var nestedSkipped = 0
        var optifineFiles = 0
    }

    fun analyze(
        file: File,
        displayName: String = file.name,
        cancel: CancelCheck = {},
        progress: ProgressCallback? = null
    ): JarReport {
        val size = file.length()
        if (size <= 0L) {
            return failure(displayName, size, "", "", Finding(Severity.ERROR, FindingCode.EMPTY_ARCHIVE, "Il file è vuoto"))
        }
        val (sha1, sha256) = hashes(file, cancel, progress)
        if (size > limits.maxFileBytes) {
            return failure(
                displayName, size, sha1, sha256,
                Finding(
                    Severity.ERROR, FindingCode.ARCHIVE_TOO_LARGE,
                    "File troppo grande (${formatBytes(size)})", "Il limite per mod e pacchetti è ${formatBytes(limits.maxFileBytes)}."
                )
            )
        }
        when (FormatSniffer.sniff(file)) {
            ArchiveFormat.ZIP -> {}
            ArchiveFormat.HTML -> return failure(
                displayName, size, sha1, sha256,
                Finding(
                    Severity.ERROR, FindingCode.HTML_PAGE, "Questo è una pagina web, non una mod",
                    "Il download non è andato a buon fine: il sito ha restituito una pagina HTML invece del file. Scaricalo di nuovo dal sito ufficiale."
                )
            )
            ArchiveFormat.SEVEN_Z, ArchiveFormat.RAR, ArchiveFormat.RAR5 -> return failure(
                displayName, size, sha1, sha256,
                Finding(
                    Severity.ERROR, FindingCode.UNSUPPORTED_FORMAT, "Archivio 7z o RAR",
                    "Qui si leggono direttamente i file .jar e .zip. Gli archivi 7z e RAR vanno aperti dal passaggio di importazione."
                )
            )
            else -> return failure(
                displayName, size, sha1, sha256,
                Finding(
                    Severity.ERROR, FindingCode.NOT_AN_ARCHIVE, "Il file non è un .jar o .zip valido",
                    "Non ha la struttura di un archivio ZIP: potrebbe essere danneggiato o di un altro tipo."
                )
            )
        }

        val scan = Scan()
        val reader = try {
            ZipArchiveReader.open(file, limits.safety)
        } catch (e: ArchiveException) {
            return failure(displayName, size, sha1, sha256, archiveFinding(e))
        }
        try {
            reader.use { r ->
                val hint = r.totalSizeHint
                if (hint > limits.safety.maxTotalUncompressedBytes) {
                    throw LimitExceededException(
                        FindingCode.UNCOMPRESSED_TOO_LARGE,
                        "Il contenuto decompresso (${formatBytes(hint)}) supera il limite di ${formatBytes(limits.safety.maxTotalUncompressedBytes)}"
                    )
                }
                if (hint >= limits.safety.ratioCheckMinBytes && hint / size > limits.safety.maxCompressionRatio) {
                    throw LimitExceededException(FindingCode.COMPRESSION_BOMB, "Il rapporto di compressione è sospetto: possibile «bomba» ZIP")
                }
                r.forEachEntry { entry, content ->
                    cancel()
                    scan.entryCount++
                    if (entry.isDirectory) return@forEachEntry
                    val path = (PathSafety.normalize(entry.rawName, limits.safety.maxPathLength) as? PathSafety.Normalized.Ok)?.path
                    if (path.isNullOrEmpty() || content == null) return@forEachEntry
                    visit(scan, path, entry.size, content)
                    progress?.invoke(scan.entryCount.toLong(), -1L, "Lettura del contenuto")
                }
            }
        } catch (e: ArchiveException) {
            return failure(displayName, size, sha1, sha256, archiveFinding(e))
        } catch (e: IOException) {
            return failure(
                displayName, size, sha1, sha256,
                Finding(Severity.ERROR, FindingCode.CORRUPT_ARCHIVE, "Impossibile leggere il file", e.message)
            )
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            return failure(
                displayName, size, sha1, sha256,
                Finding(Severity.ERROR, FindingCode.CORRUPT_ARCHIVE, "Archivio non leggibile", e.message)
            )
        }
        return build(displayName, size, sha1, sha256, scan)
    }

    // ------------------------------------------------------------------------------------------ lettura delle voci

    private fun visit(scan: Scan, path: String, declaredSize: Long, content: InputStream) {
        scan.names.putIfAbsent(path, declaredSize)
        val lower = path.lowercase()
        val ext = PathSafety.extension(path)
        val segments = path.split('/')

        if (path.startsWith("META-INF/")) {
            if (segments.size == 2 && ext in SIGNATURE_EXTENSIONS) scan.signed = true
        }

        when {
            isMetadataPath(path, segments) -> {
                val bytes = readLimited(content, limits.maxMetadataBytes)
                if (bytes == null) scan.oversize.add(path) else scan.meta[path] = bytes
                if (segments.last() == "manifest.json" && segments.size <= 2 && bytes != null && scan.manifestProbes.size < 6) {
                    scan.manifestProbes.add(path to bytes)
                }
            }
            ext == "class" -> {
                if (path.startsWith("META-INF/versions/")) return
                scan.classCount++
                val major = classMajor(content)
                if (major > scan.maxClassMajor) scan.maxClassMajor = major
            }
            ext == "jar" && (path.startsWith("META-INF/jars/") || path.startsWith("META-INF/jarjar/")) -> {
                readNested(scan, path, declaredSize, content)
            }
            ext == "jar" && !path.startsWith("META-INF/") -> {
                if (scan.bundleJars.size < limits.maxBundleJars) scan.bundleJars.add(path)
            }
            ext in NATIVE_EXTENSIONS -> if (scan.nativeLibs.size < 12) scan.nativeLibs.add(path)
        }

        if (segments.size == 1 && ext == "json" && (lower.contains("mixins.") || lower.endsWith(".mixins.json") || lower == "mixins.json")) {
            scan.rootMixins.add(path)
        }
        if (path.startsWith("optifine/") || path.startsWith("net/optifine/") || path.startsWith("notch/net/optifine/")) scan.optifineFiles++
    }

    /** File di descrizione cercati nella radice, oppure dentro una sola cartella che avvolge tutto. */
    private fun isMetadataPath(path: String, segments: List<String>): Boolean {
        if (path in METADATA_FILES) return true
        if (segments.size == 2 && segments[1] in METADATA_FILES) return true
        if (segments.size == 3 && "${segments[1]}/${segments[2]}" in METADATA_FILES) return true
        return segments.last() == "manifest.json" && segments.size <= 2
    }

    private fun readNested(scan: Scan, path: String, declaredSize: Long, content: InputStream) {
        val size = if (declaredSize >= 0) declaredSize else 0L
        if (scan.nested.size >= limits.maxNestedJars ||
            scan.nestedBytes + size > limits.maxNestedTotalBytes ||
            size > limits.maxNestedJarBytes
        ) {
            scan.nested.add(NestedJar(path, size, emptyList()))
            scan.nestedSkipped++
            return
        }
        val tmp = try {
            tempDir.mkdirs()
            File.createTempFile("nested", ".jar", tempDir)
        } catch (e: IOException) {
            scan.nested.add(NestedJar(path, size, emptyList()))
            scan.nestedSkipped++
            return
        }
        try {
            var copied = 0L
            tmp.outputStream().buffered(64 * 1024).use { out ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = content.read(buf)
                    if (n < 0) break
                    copied += n
                    if (copied > limits.maxNestedJarBytes) {
                        scan.nested.add(NestedJar(path, size, emptyList()))
                        scan.nestedSkipped++
                        return
                    }
                    out.write(buf, 0, n)
                }
            }
            scan.nestedBytes += copied
            scan.nested.add(NestedJar(path, copied, readNestedMods(tmp)))
        } catch (e: IOException) {
            scan.nested.add(NestedJar(path, size, emptyList()))
        } finally {
            tmp.delete()
        }
    }

    /** Legge solo i file di descrizione di un .jar annidato (un solo livello). */
    private fun readNestedMods(file: File): List<ModMetadata> {
        return try {
            ZipFile(file).use { zip ->
                fun bytes(name: String): ByteArray? {
                    val e = zip.getEntry(name) ?: return null
                    if (e.isDirectory || e.size > limits.maxMetadataBytes) return null
                    return zip.getInputStream(e).use { readLimited(it, limits.maxMetadataBytes) }
                }
                val manifest = bytes("META-INF/MANIFEST.MF")?.let { JarManifestReader.parseMain(String(it, Charsets.UTF_8)) }.orEmpty()
                extractMods(
                    Descriptors(
                        fabric = bytes("fabric.mod.json"),
                        quilt = bytes("quilt.mod.json"),
                        neoToml = bytes("META-INF/neoforge.mods.toml"),
                        forgeToml = bytes("META-INF/mods.toml"),
                        mcmod = null,
                        manifest = manifest
                    )
                ).mods
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------------------------------ costruzione del rapporto

    private class Descriptors(
        val fabric: ByteArray?,
        val quilt: ByteArray?,
        val neoToml: ByteArray?,
        val forgeToml: ByteArray?,
        val mcmod: ByteArray?,
        val manifest: Map<String, String>
    )

    private class Extracted(
        val mods: List<ModMetadata>,
        val mixins: List<String>,
        val declaredNested: List<String>,
        val findings: List<Finding>,
        val anyDescriptor: Boolean
    )

    private fun extractMods(d: Descriptors): Extracted {
        val mods = ArrayList<ModMetadata>()
        val mixins = ArrayList<String>()
        val nested = ArrayList<String>()
        val problems = ArrayList<Finding>()
        val errors = ArrayList<Finding>()
        var any = false

        fun attempt(present: Boolean, label: String, block: () -> ReadResult) {
            if (!present) return
            any = true
            try {
                val r = block()
                mods.addAll(r.mods)
                mixins.addAll(r.mixins)
                nested.addAll(r.nestedPaths)
            } catch (e: MetadataException) {
                errors.add(Finding(Severity.ERROR, FindingCode.JAVA_METADATA_INVALID, "$label non utilizzabile", e.message))
            }
        }
        attempt(d.fabric != null, "fabric.mod.json") { FabricReader.read(d.fabric!!, problems) }
        attempt(d.quilt != null, "quilt.mod.json") { QuiltReader.read(d.quilt!!, problems) }
        attempt(d.neoToml != null, "neoforge.mods.toml") {
            ForgeTomlReader.read(d.neoToml!!, "META-INF/neoforge.mods.toml", d.manifest, problems)
        }
        attempt(d.forgeToml != null, "mods.toml") { ForgeTomlReader.read(d.forgeToml!!, "META-INF/mods.toml", d.manifest, problems) }
        if (mods.isEmpty() && errors.isEmpty()) {
            attempt(d.mcmod != null, "mcmod.info") { LegacyForgeReader.read(d.mcmod!!) }
        }
        // un descrittore rotto accanto a uno valido non impedisce l'uso del file: diventa un avviso
        val all = ArrayList<Finding>(problems)
        for (e in errors) {
            all.add(if (mods.isEmpty()) e else Finding(Severity.WARNING, e.code, e.message, e.detail))
        }
        return Extracted(mods, mixins, nested, all, any)
    }

    private fun build(displayName: String, size: Long, sha1: String, sha256: String, scan: Scan): JarReport {
        val findings = ArrayList<Finding>()
        val wrapper = wrapperFolder(scan)

        fun bytesOf(name: String): ByteArray? = scan.meta[name] ?: wrapper?.let { scan.meta["$it/$name"] }

        for (p in scan.oversize) {
            findings.add(Finding(Severity.WARNING, FindingCode.JAVA_METADATA_INVALID, "Il file «${PathSafety.fileName(p)}» è troppo grande per essere letto", p))
        }

        val manifest = bytesOf("META-INF/MANIFEST.MF")?.let { JarManifestReader.parseMain(String(it, Charsets.UTF_8)) }.orEmpty()
        val manifestAttributes = LinkedHashMap<String, String>()
        for (k in MANIFEST_KEYS) manifest[k]?.takeIf { it.isNotBlank() }?.let { manifestAttributes[k] = it.take(200) }

        val extracted = extractMods(
            Descriptors(
                fabric = scan.meta["fabric.mod.json"],
                quilt = scan.meta["quilt.mod.json"],
                neoToml = scan.meta["META-INF/neoforge.mods.toml"],
                forgeToml = scan.meta["META-INF/mods.toml"],
                mcmod = scan.meta["mcmod.info"],
                manifest = manifest
            )
        )
        findings.addAll(extracted.findings)

        val javaMajor = if (scan.maxClassMajor >= 45) scan.maxClassMajor - 44 else null
        val mixinConfigs = (extracted.mixins + scan.rootMixins).distinct()

        var kind: ContentKind
        var pack: PackInfo? = null
        var otherLoader: String? = null
        var modpack: ModpackSummary? = null

        val hasServerPlugin = listOf("plugin.yml", "paper-plugin.yml", "bungee.yml", "velocity-plugin.json").any { bytesOf(it) != null }
        val modrinthIndex = bytesOf("modrinth.index.json")
        val rootManifestBytes = scan.manifestProbes.firstOrNull { it.first == "manifest.json" }?.second
            ?: wrapper?.let { w -> scan.manifestProbes.firstOrNull { it.first == "$w/manifest.json" }?.second }

        when {
            extracted.mods.isNotEmpty() -> kind = ContentKind.MOD
            extracted.anyDescriptor -> kind = ContentKind.MOD // descrittore presente ma rotto: l'errore è già nei findings
            hasServerPlugin -> {
                kind = ContentKind.SERVER_PLUGIN
                findings.add(
                    Finding(
                        Severity.ERROR, FindingCode.JAVA_SERVER_PLUGIN, "È un plugin per server (Bukkit, Spigot, Paper…), non una mod",
                        "Funziona solo su un server con quel software e non si può usare nel gioco su questo telefono."
                    )
                )
            }
            modrinthIndex != null -> {
                kind = ContentKind.MODPACK_MODRINTH
                modpack = ModpackIndexReader.readModrinth(modrinthIndex, scan.names.keys)
                if (modpack == null) {
                    findings.add(Finding(Severity.ERROR, FindingCode.JAVA_METADATA_INVALID, "modrinth.index.json non è valido"))
                }
            }
            rootManifestBytes != null && isCurseForge(rootManifestBytes) -> {
                kind = ContentKind.MODPACK_CURSEFORGE
                modpack = ModpackIndexReader.readCurseForge(rootManifestBytes, scan.names.keys)
                if (modpack == null) {
                    findings.add(Finding(Severity.ERROR, FindingCode.JAVA_METADATA_INVALID, "Il manifest del modpack non è valido"))
                }
            }
            scan.manifestProbes.any { isBedrockManifest(it.second) } -> {
                kind = ContentKind.BEDROCK_PACK
                findings.add(
                    Finding(
                        Severity.ERROR, FindingCode.JAVA_BEDROCK_FILE, "È un pacchetto per Minecraft Bedrock, non per Java",
                        "Aprilo da INSTALLER BEDROCK."
                    )
                )
            }
            bytesOf("litemod.json") != null -> {
                kind = ContentKind.UNSUPPORTED_LOADER
                otherLoader = "LiteLoader"
            }
            bytesOf("riftmod.json") != null -> {
                kind = ContentKind.UNSUPPORTED_LOADER
                otherLoader = "Rift"
            }
            scan.optifineFiles > 0 -> kind = ContentKind.OPTIFINE
            bytesOf("pack.mcmeta") != null || (scan.classCount == 0 && hasFolder(scan, wrapper, "shaders")) -> {
                val packResult = buildPack(scan, wrapper, ::bytesOf, findings)
                pack = packResult.first
                kind = packResult.second
            }
            looksLikeJavaWorld(scan, wrapper) -> {
                kind = ContentKind.JAVA_WORLD
                findings.add(
                    Finding(
                        Severity.ERROR, FindingCode.JAVA_WORLD_FILE, "È un mondo di Minecraft Java",
                        "I mondi si gestiscono da WORLD MANAGER, non da qui."
                    )
                )
            }
            scan.classCount > 0 -> {
                kind = ContentKind.LIBRARY
                findings.add(
                    Finding(
                        Severity.WARNING, FindingCode.JAVA_NO_METADATA, "Nessuna descrizione di mod trovata",
                        "Contiene codice Java ma non dice di quale mod o caricatore fa parte: potrebbe essere una libreria, " +
                            "una mod per un sistema non supportato o un file incompleto."
                    )
                )
            }
            scan.bundleJars.isNotEmpty() -> {
                kind = ContentKind.MOD_BUNDLE
                findings.add(
                    Finding(
                        Severity.INFO, FindingCode.JAVA_BUNDLE, "Raccolta di ${scan.bundleJars.size} file .jar",
                        "Ogni file verrà analizzato separatamente prima dell'installazione."
                    )
                )
            }
            else -> {
                kind = ContentKind.UNKNOWN
                findings.add(Finding(Severity.ERROR, FindingCode.NO_CONTENT, "Non riconosco il contenuto di questo file", "Non è una mod, un resource pack, uno shader o un modpack di Java Edition."))
            }
        }

        // ---- osservazioni specifiche per tipo
        when (kind) {
            ContentKind.MOD -> modFindings(extracted, javaMajor, scan, findings)
            ContentKind.OPTIFINE -> findings.add(
                Finding(
                    Severity.WARNING, FindingCode.JAVA_OPTIFINE, "Sembra OptiFine",
                    "OptiFine non è una mod normale: funziona con Forge solo per versioni precise e non con Fabric. Con Fabric o Quilt di solito si usano Sodium e Iris."
                )
            )
            ContentKind.UNSUPPORTED_LOADER -> findings.add(
                Finding(
                    Severity.ERROR, FindingCode.JAVA_UNSUPPORTED_LOADER, "Mod per $otherLoader",
                    "Questo sistema non è supportato: l'app gestisce Fabric, Quilt, Forge e NeoForge."
                )
            )
            ContentKind.LIBRARY -> if (javaMajor != null) {
                findings.add(Finding(Severity.INFO, FindingCode.JAVA_BYTECODE, "Richiede Java $javaMajor o superiore"))
            }
            ContentKind.MODPACK_MODRINTH, ContentKind.MODPACK_CURSEFORGE -> findings.add(
                Finding(
                    Severity.INFO, FindingCode.JAVA_MODPACK, "È un modpack",
                    "Un modpack contiene molte mod e impostazioni insieme: si importa da MODPACK MANAGER."
                )
            )
            else -> {}
        }

        return JarReport(
            fileName = displayName,
            sizeBytes = size,
            sha256 = sha256,
            sha1 = sha1,
            kind = kind,
            mods = extracted.mods,
            pack = pack,
            nested = scan.nested,
            entryCount = scan.entryCount,
            classCount = scan.classCount,
            javaMajor = javaMajor,
            nativeLibraries = scan.nativeLibs,
            mixinConfigs = mixinConfigs,
            manifestAttributes = manifestAttributes,
            signed = scan.signed,
            findings = findings,
            bundleJars = if (kind == ContentKind.MOD_BUNDLE) scan.bundleJars else emptyList(),
            otherLoader = otherLoader,
            modpack = modpack
        )
    }

    private fun modFindings(extracted: Extracted, javaMajor: Int?, scan: Scan, findings: MutableList<Finding>) {
        val loaders = extracted.mods.map { it.loader }.distinct()
        if (loaders.size > 1) {
            findings.add(
                Finding(
                    Severity.INFO, FindingCode.JAVA_MULTI_LOADER, "Funziona con più caricatori: " + loaders.joinToString { it.label },
                    "Il file contiene le descrizioni per ognuno; verrà usata quella del caricatore del profilo."
                )
            )
        }
        val first = extracted.mods.firstOrNull()
        if (first != null && first.descriptor == "mcmod.info") {
            findings.add(
                Finding(
                    Severity.WARNING, FindingCode.JAVA_LEGACY_FORGE, "Mod per Forge precedente alla 1.13",
                    "Le sue dipendenze sono scritte nel codice e non vengono lette: l'app non può controllarle. Funziona solo con Minecraft 1.12.2 o precedenti."
                )
            )
        } else if (extracted.mods.any { it.certainty == LoaderCertainty.GUESSED }) {
            findings.add(
                Finding(
                    Severity.INFO, FindingCode.JAVA_LOADER_GUESSED, "Non è indicato con certezza se è per Forge o NeoForge",
                    "Il file non nomina nessuno dei due: lo considero per Forge."
                )
            )
        }
        val sameIds = extracted.mods.groupBy { it.loader to it.id }.filter { it.value.size > 1 }
        if (sameIds.isNotEmpty()) {
            findings.add(Finding(Severity.WARNING, FindingCode.JAVA_ID_INVALID, "Lo stesso identificatore compare più volte: " + sameIds.keys.joinToString { it.second }))
        }
        if (javaMajor != null) {
            findings.add(Finding(Severity.INFO, FindingCode.JAVA_BYTECODE, "Richiede Java $javaMajor o superiore"))
        }
        if (scan.nativeLibs.isNotEmpty()) {
            findings.add(
                Finding(
                    Severity.WARNING, FindingCode.JAVA_NATIVE_LIBS, "Contiene librerie native (${scan.nativeLibs.size})",
                    "File come " + scan.nativeLibs.take(3).joinToString { PathSafety.fileName(it) } +
                        ": sono programmi compilati per un sistema preciso e possono non funzionare su Android."
                )
            )
        }
        if (scan.nested.isNotEmpty()) {
            val mods = scan.nested.count { it.mods.isNotEmpty() }
            findings.add(
                Finding(
                    Severity.INFO, FindingCode.JAVA_NESTED_JARS, "Include ${scan.nested.size} librerie o moduli",
                    if (mods > 0) "$mods sono mod a sé, che contano per le dipendenze di altre mod." else null
                )
            )
        }
        if (scan.nestedSkipped > 0) {
            findings.add(
                Finding(
                    Severity.INFO, FindingCode.JAVA_NESTED_JARS, "${scan.nestedSkipped} file interni non sono stati letti",
                    "Sono troppo grandi o numerosi: le loro dipendenze non sono considerate."
                )
            )
        }
    }

    // ------------------------------------------------------------------------------------------ pack

    private fun buildPack(
        scan: Scan,
        wrapper: String?,
        bytesOf: (String) -> ByteArray?,
        findings: MutableList<Finding>
    ): Pair<PackInfo, ContentKind> {
        val prefix = wrapper?.let { "$it/" } ?: ""
        val shaderAtRoot = scan.names.keys.any { it.startsWith("${prefix}shaders/") && !it.startsWith("${prefix}shaders/..") }
        val assets = scan.names.keys.count { it.startsWith("${prefix}assets/") }
        val data = scan.names.keys.count { it.startsWith("${prefix}data/") }
        val shaders = scan.names.keys.count { it.startsWith("${prefix}shaders/") }
        val metaBytes = bytesOf("pack.mcmeta")
        var meta: PackMeta? = null
        if (metaBytes != null) {
            try {
                meta = PackMetaReader.read(metaBytes)
            } catch (e: MetadataException) {
                findings.add(Finding(Severity.ERROR, FindingCode.JAVA_METADATA_INVALID, "pack.mcmeta non utilizzabile", e.message))
            }
        }
        val kind = when {
            shaderAtRoot && assets == 0 && data == 0 -> ContentKind.SHADER_PACK
            assets > 0 -> ContentKind.RESOURCE_PACK
            data > 0 -> ContentKind.DATA_PACK
            else -> ContentKind.RESOURCE_PACK
        }
        if (metaBytes == null && kind != ContentKind.SHADER_PACK) {
            findings.add(
                Finding(
                    Severity.ERROR, FindingCode.JAVA_METADATA_INVALID, "Manca il file pack.mcmeta",
                    "Senza questo file Minecraft non riconosce il pacchetto."
                )
            )
        }
        if (assets == 0 && data == 0 && shaders == 0) {
            findings.add(Finding(Severity.WARNING, FindingCode.NO_CONTENT, "Il pacchetto non contiene file di risorse né dati"))
        }
        if (wrapper != null) {
            findings.add(
                Finding(
                    Severity.WARNING, FindingCode.JAVA_WRAPPED_PACK, "Il contenuto è dentro la cartella «$wrapper»",
                    "Minecraft non lo caricherebbe così: all'installazione l'app crea una copia con la struttura corretta."
                )
            )
        }
        val known = PackMetaReader.minecraftFor(meta?.packFormat)
        if (meta?.packFormat != null) {
            findings.add(
                if (known != null) {
                    Finding(Severity.INFO, FindingCode.JAVA_PACK_FORMAT, "Formato ${meta.packFormat}: Minecraft $known", "Indicativo, per i resource pack.")
                } else {
                    Finding(Severity.INFO, FindingCode.JAVA_PACK_FORMAT, "Formato ${meta.packFormat}", "Non so a quale versione di Minecraft corrisponda.")
                }
            )
        }
        if (kind == ContentKind.SHADER_PACK) {
            findings.add(
                Finding(
                    Severity.INFO, FindingCode.JAVA_PACK_FORMAT, "Gli shader servono di una mod",
                    "Funzionano solo con OptiFine o con Iris (Fabric e Quilt) o Oculus (Forge)."
                )
            )
        }
        val info = PackInfo(
            description = meta?.description,
            packFormat = meta?.packFormat,
            minFormat = meta?.minFormat,
            maxFormat = meta?.maxFormat,
            hasAssets = assets > 0,
            hasData = data > 0,
            hasShaders = shaders > 0,
            knownMinecraft = known,
            wrapperFolder = wrapper
        )
        return info to kind
    }

    /**
     * Se tutti i file sono dentro un'unica cartella, e nella radice non c'è nulla, restituisce il suo nome. Le cartelle che
     * hanno un significato nella radice di un pacchetto (assets, data, shaders…) non contano come «involucro».
     */
    private fun wrapperFolder(scan: Scan): String? {
        var top: String? = null
        for (p in scan.names.keys) {
            if (PathSafety.isJunk(p)) continue
            val i = p.indexOf('/')
            if (i < 0) return null // un file nella radice
            val first = p.substring(0, i)
            if (top == null) top = first else if (top != first) return null
        }
        if (top != null && top.lowercase() in RESERVED_ROOTS) return null
        return top
    }

    private fun hasFolder(scan: Scan, wrapper: String?, folder: String): Boolean {
        val prefix = (wrapper?.let { "$it/" } ?: "") + folder + "/"
        return scan.names.keys.any { it.startsWith(prefix) }
    }

    private fun looksLikeJavaWorld(scan: Scan, wrapper: String?): Boolean {
        val prefix = wrapper?.let { "$it/" } ?: ""
        if (!scan.names.containsKey("${prefix}level.dat")) return false
        if (scan.names.keys.any { it.startsWith("${prefix}db/") }) return false // Bedrock
        return scan.names.keys.any { it.startsWith("${prefix}region/") || it.startsWith("${prefix}DIM-1/") || it.startsWith("${prefix}playerdata/") }
    }

    private fun isBedrockManifest(bytes: ByteArray): Boolean {
        val o = parseObject(bytes) ?: return false
        return o.obj("header") != null && o.containsKey("format_version")
    }

    private fun isCurseForge(bytes: ByteArray): Boolean {
        val o = parseObject(bytes) ?: return false
        return o.obj("minecraft") != null && o.list("files") != null && o.obj("header") == null
    }

    private fun parseObject(bytes: ByteArray): Map<String, Any?>? = try {
        Json.parse(String(bytes, Charsets.UTF_8)).asJsonObject()
    } catch (e: JsonParseException) {
        null
    }

    // ------------------------------------------------------------------------------------------ utilità

    private fun failure(name: String, size: Long, sha1: String, sha256: String, finding: Finding): JarReport = JarReport(
        fileName = name,
        sizeBytes = size,
        sha256 = sha256,
        sha1 = sha1,
        kind = ContentKind.UNKNOWN,
        mods = emptyList(),
        pack = null,
        nested = emptyList(),
        entryCount = 0,
        classCount = 0,
        javaMajor = null,
        nativeLibraries = emptyList(),
        mixinConfigs = emptyList(),
        manifestAttributes = emptyMap(),
        signed = false,
        findings = listOf(finding)
    )

    private fun archiveFinding(e: ArchiveException): Finding = when (e) {
        is ArchiveCorruptException -> Finding(
            Severity.ERROR, FindingCode.CORRUPT_ARCHIVE, "File danneggiato o incompleto", e.message
        )
        is ArchivePasswordException -> Finding(Severity.ERROR, FindingCode.PASSWORD_PROTECTED, "L'archivio è protetto da password")
        is ArchiveUnsupportedException -> Finding(Severity.ERROR, e.code, "Formato non supportato", e.message)
        is ArchiveUnsafeException -> Finding(Severity.ERROR, FindingCode.UNSAFE_PATH, "Contiene percorsi pericolosi", e.message)
        is LimitExceededException -> Finding(Severity.ERROR, e.code, "Il file supera i limiti di sicurezza", e.message)
        else -> Finding(Severity.ERROR, FindingCode.CORRUPT_ARCHIVE, "Archivio non leggibile", e.message)
    }

    private fun hashes(file: File, cancel: CancelCheck, progress: ProgressCallback?): Pair<String, String> {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        val total = file.length()
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var done = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sha1.update(buf, 0, n)
                sha256.update(buf, 0, n)
                done += n
                cancel()
                progress?.invoke(done, total, "Calcolo dell'impronta del file")
            }
        }
        return hex(sha1.digest()) to hex(sha256.digest())
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 15])
        }
        return sb.toString()
    }

    /** Legge al massimo [limit] byte; restituisce null se il contenuto è più grande. */
    private fun readLimited(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Versione del formato della classe (52 = Java 8), o -1 se l'intestazione non è valida. */
    private fun classMajor(input: InputStream): Int {
        val h = ByteArray(8)
        var off = 0
        while (off < 8) {
            val n = input.read(h, off, 8 - off)
            if (n < 0) return -1
            off += n
        }
        if (h[0] != 0xCA.toByte() || h[1] != 0xFE.toByte() || h[2] != 0xBA.toByte() || h[3] != 0xBE.toByte()) return -1
        val major = ((h[6].toInt() and 0xFF) shl 8) or (h[7].toInt() and 0xFF)
        return if (major in 45..100) major else -1
    }

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()
        private val RESERVED_ROOTS = setOf("assets", "data", "shaders", "meta-inf", "region", "db", "playerdata", "optifine")
        private val SIGNATURE_EXTENSIONS = setOf("sf", "rsa", "dsa", "ec")
        private val NATIVE_EXTENSIONS = setOf("so", "dll", "dylib", "jnilib")
        private val MANIFEST_KEYS = listOf(
            "Implementation-Title", "Implementation-Version", "Implementation-Vendor", "Specification-Version",
            "Automatic-Module-Name", "Main-Class", "Multi-Release", "FMLCorePlugin", "TweakClass", "MixinConfigs", "ModSide"
        )
        private val METADATA_FILES = setOf(
            "fabric.mod.json", "quilt.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml", "mcmod.info",
            "pack.mcmeta", "META-INF/MANIFEST.MF", "modrinth.index.json", "plugin.yml", "paper-plugin.yml", "bungee.yml",
            "velocity-plugin.json", "litemod.json", "riftmod.json"
        )

        /**
         * Copia in [target] il contenuto di una voce dell'archivio, trovata per percorso normalizzato (il nome della voce non
         * viene mai usato per costruire il percorso di destinazione). Restituisce false se la voce non esiste.
         */
        fun extractEntry(archive: File, entryPath: String, target: File, maxBytes: Long, cancel: CancelCheck = {}): Boolean {
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    val p = (PathSafety.normalize(e.name) as? PathSafety.Normalized.Ok)?.path ?: continue
                    if (p != entryPath) continue
                    if (e.size > maxBytes) throw LimitExceededException(FindingCode.ENTRY_TOO_LARGE, "Il file interno supera ${formatBytes(maxBytes)}")
                    var copied = 0L
                    try {
                        zip.getInputStream(e).use { input ->
                            target.outputStream().buffered(64 * 1024).use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    copied += n
                                    if (copied > maxBytes) throw LimitExceededException(FindingCode.ENTRY_TOO_LARGE, "Il file interno supera ${formatBytes(maxBytes)}")
                                    out.write(buf, 0, n)
                                    cancel()
                                }
                            }
                        }
                    } catch (ex: Throwable) {
                        target.delete()
                        throw ex
                    }
                    return true
                }
            }
            return false
        }
    }
}
