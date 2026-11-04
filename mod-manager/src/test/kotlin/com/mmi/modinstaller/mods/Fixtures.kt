package com.mmi.modinstaller.mods

import com.mmi.modinstaller.core.DocumentStore
import com.mmi.modinstaller.core.Finding
import com.mmi.modinstaller.core.MemoryDocumentStore
import com.mmi.modinstaller.jar.ContentKind
import com.mmi.modinstaller.jar.DependencyKind
import com.mmi.modinstaller.jar.JarReport
import com.mmi.modinstaller.jar.LoaderCertainty
import com.mmi.modinstaller.jar.ModDependency
import com.mmi.modinstaller.jar.ModEnvironment
import com.mmi.modinstaller.jar.ModLoader
import com.mmi.modinstaller.jar.ModMetadata
import com.mmi.modinstaller.jar.NestedJar
import com.mmi.modinstaller.jar.PackInfo
import com.mmi.modinstaller.jar.RangeSyntax
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Costruttori di dati di prova: niente file veri, solo le descrizioni che l'analizzatore produrrebbe. */
object Fx {

    fun dep(
        id: String,
        range: String? = null,
        kind: DependencyKind = DependencyKind.REQUIRED,
        syntax: RangeSyntax = RangeSyntax.SEMVER,
        side: String? = null,
        reason: String? = null
    ) = ModDependency(id, kind, range, syntax, side, reason)

    fun descriptorFor(loader: ModLoader) = when (loader) {
        ModLoader.FABRIC -> "fabric.mod.json"
        ModLoader.QUILT -> "quilt.mod.json"
        ModLoader.FORGE -> "META-INF/mods.toml"
        ModLoader.NEOFORGE -> "META-INF/neoforge.mods.toml"
    }

    fun mod(
        id: String,
        version: String? = "1.0.0",
        loader: ModLoader = ModLoader.FABRIC,
        deps: List<ModDependency> = emptyList(),
        certainty: LoaderCertainty = LoaderCertainty.CERTAIN,
        provides: List<String> = emptyList(),
        env: ModEnvironment = ModEnvironment.BOTH,
        descriptor: String = descriptorFor(loader),
        name: String? = null,
        mcHint: String? = null,
        language: String? = null
    ) = ModMetadata(
        loader = loader, certainty = certainty, id = id, name = name, version = version, environment = env,
        provides = provides, dependencies = deps, descriptor = descriptor, minecraftHint = mcHint, languageLoader = language
    )

    /** Dipendenza standard di una mod Fabric per Minecraft 1.20.1. */
    fun fabricDeps(extra: List<ModDependency> = emptyList()) =
        listOf(dep("minecraft", "~1.20.1"), dep("fabricloader", ">=0.14.0"), dep("java", ">=17")) + extra

    fun report(
        fileName: String,
        kind: ContentKind = ContentKind.MOD,
        mods: List<ModMetadata> = emptyList(),
        sha256: String = "sha-" + fileName,
        nested: List<NestedJar> = emptyList(),
        pack: PackInfo? = null,
        findings: List<Finding> = emptyList(),
        javaMajor: Int? = null,
        nativeLibraries: List<String> = emptyList()
    ) = JarReport(
        fileName = fileName, sizeBytes = 1000, sha256 = sha256, sha1 = "sha1-$fileName", kind = kind, mods = mods, pack = pack,
        nested = nested, entryCount = 10, classCount = 5, javaMajor = javaMajor, nativeLibraries = nativeLibraries,
        mixinConfigs = emptyList(), manifestAttributes = emptyMap(), signed = false, findings = findings
    )

    fun packInfo(format: Int? = 15, wrapper: String? = null, assets: Boolean = true, data: Boolean = false, shaders: Boolean = false) =
        PackInfo(null, format, null, null, assets, data, shaders, null, wrapper)

    fun profile(
        loader: ModLoader = ModLoader.FABRIC,
        minecraft: String = "1.20.1",
        loaderVersion: String? = null,
        java: Int? = null,
        id: String = "p1"
    ) = Profile(id, "Prova", minecraft, loader, loaderVersion, java, 0L)

    fun item(
        id: String,
        mods: List<ModMetadata> = emptyList(),
        fileName: String = "$id.jar",
        enabled: Boolean = true,
        category: ItemCategory = ItemCategory.MOD,
        kind: ContentKind = ContentKind.MOD,
        sha: String = "sha-$id",
        origin: String = fileName,
        nested: List<ModMetadata> = emptyList(),
        packFormat: Int? = null,
        javaMajor: Int? = null
    ) = ProfileItem(
        id = id, category = category, fileName = fileName, enabled = enabled, sha256 = sha, sha1 = "sha1-$id", sizeBytes = 100,
        installedAt = 1L, origin = origin, source = "file", kind = kind, mods = mods, nested = nested, javaMajor = javaMajor,
        packFormat = packFormat
    )

    fun plan(report: JarReport, profile: Profile = profile(), installed: List<ProfileItem> = emptyList()) =
        InstallPlanner.plan(report, profile, installed)

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Scrive un file di prova con questo contenuto. */
    fun file(dir: File, name: String, content: ByteArray): File {
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        f.writeBytes(content)
        return f
    }

    /** Uno ZIP vero con queste voci (testo). */
    fun zip(file: File, entries: Map<String, String>): File {
        file.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(file)).use { z ->
            for ((name, text) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return file
    }

    fun tempDir(prefix: String = "mm-test"): File = Files.createTempDirectory(prefix).toFile()

    /** Un archivio che si comporta come quello dell'app: la copia in memoria cambia anche se il disco non si salva. */
    class FlakyStore(private val inner: DocumentStore = MemoryDocumentStore()) : DocumentStore {
        @Volatile var failWrites = false
        override fun read(section: String): Any? = inner.read(section)
        override fun write(section: String, value: Any?): Boolean {
            inner.write(section, value)
            return !failWrites
        }

        override fun update(section: String, transform: (Any?) -> Any?): Boolean {
            inner.update(section, transform)
            return !failWrites
        }
    }
}
