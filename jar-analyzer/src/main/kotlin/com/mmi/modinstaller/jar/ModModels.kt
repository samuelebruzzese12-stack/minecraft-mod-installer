package com.mmi.modinstaller.jar

import com.mmi.modinstaller.core.Finding
import com.mmi.modinstaller.core.Severity

enum class ModLoader(val label: String) {
    FABRIC("Fabric"),
    QUILT("Quilt"),
    FORGE("Forge"),
    NEOFORGE("NeoForge");

    /** Identificatore stabile, usato nei file salvati. */
    val key: String get() = name.lowercase()

    /** Caricatori che possono leggere le mod di questo tipo: Quilt carica quasi sempre le mod Fabric, il contrario no. */
    fun canLoadModsFor(modLoader: ModLoader): Boolean = when (this) {
        QUILT -> modLoader == QUILT || modLoader == FABRIC
        else -> this == modLoader
    }

    companion object {
        fun fromKey(key: String?): ModLoader? = values().firstOrNull { it.key == key?.lowercase() }
    }
}

/** Quanto è sicuro che la mod sia per quel caricatore. */
enum class LoaderCertainty { CERTAIN, GUESSED }

enum class ModEnvironment(val label: String) {
    BOTH("client e server"),
    CLIENT("solo client"),
    SERVER("solo server")
}

/** Tipo di contenuto riconosciuto in un file. */
enum class ContentKind(val label: String) {
    MOD("Mod"),
    MOD_BUNDLE("Raccolta di mod"),
    RESOURCE_PACK("Resource pack"),
    DATA_PACK("Data pack"),
    SHADER_PACK("Shader pack"),
    MODPACK_MODRINTH("Modpack Modrinth"),
    MODPACK_CURSEFORGE("Modpack CurseForge"),
    LIBRARY("Libreria Java"),
    OPTIFINE("OptiFine"),
    SERVER_PLUGIN("Plugin per server"),
    UNSUPPORTED_LOADER("Mod per un caricatore non supportato"),
    BEDROCK_PACK("Pacchetto Bedrock"),
    JAVA_WORLD("Mondo Java"),
    UNKNOWN("Non riconosciuto")
}

enum class DependencyKind(val label: String) {
    REQUIRED("obbligatoria"),
    OPTIONAL("facoltativa"),
    RECOMMENDED("consigliata"),
    SUGGESTED("suggerita"),
    CONFLICTS("in conflitto"),
    BREAKS("incompatibile")
}

/** Sintassi del vincolo di versione: dipende dal caricatore che ha scritto il file. */
enum class RangeSyntax { MAVEN, SEMVER }

data class ModDependency(
    val id: String,
    val kind: DependencyKind,
    /** Testo originale del vincolo di versione; null = qualsiasi versione. */
    val range: String? = null,
    val syntax: RangeSyntax = RangeSyntax.SEMVER,
    /** BOTH / CLIENT / SERVER (solo Forge e NeoForge). */
    val side: String? = null,
    val reason: String? = null
) {
    val constraint: VersionConstraint by lazy(LazyThreadSafetyMode.PUBLICATION) {
        when (syntax) {
            RangeSyntax.MAVEN -> VersionRanges.parseMaven(range)
            RangeSyntax.SEMVER -> VersionRanges.parseSemver(range)
        }
    }

    /** True per Minecraft, Java e il caricatore: non sono mod da installare. */
    val isPlatform: Boolean get() = id in PLATFORM_IDS

    fun test(version: String?): Compat = constraint.test(version)

    companion object {
        val PLATFORM_IDS: Set<String> = setOf(
            "minecraft", "java", "fabricloader", "fabric-loader", "quilt_loader", "quilt-loader", "forge", "neoforge", "fml"
        )
    }
}

data class ModMetadata(
    val loader: ModLoader,
    val certainty: LoaderCertainty,
    val id: String,
    val name: String? = null,
    val version: String? = null,
    val description: String? = null,
    val authors: List<String> = emptyList(),
    val license: String? = null,
    val homepage: String? = null,
    val environment: ModEnvironment = ModEnvironment.BOTH,
    /** Altri identificatori con cui questa mod si presenta alle altre (campo `provides`). */
    val provides: List<String> = emptyList(),
    val dependencies: List<ModDependency> = emptyList(),
    /** File di descrizione da cui provengono i dati (es. `fabric.mod.json`). */
    val descriptor: String,
    /** Versione di Minecraft dichiarata nei vecchi `mcmod.info`. */
    val minecraftHint: String? = null,
    /** Linguaggio/modulo richiesto da Forge (`javafml`, `kotlinforforge`…). */
    val languageLoader: String? = null
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: id

    /** Gli identificatori con cui le altre mod possono dipendere da questa. */
    val allIds: List<String> get() = listOf(id) + provides
}

/** Un .jar dentro un altro .jar (Fabric `META-INF/jars`, Forge e NeoForge `META-INF/jarjar`). */
data class NestedJar(val path: String, val sizeBytes: Long, val mods: List<ModMetadata>)

data class PackInfo(
    val description: String?,
    val packFormat: Int?,
    val minFormat: Int?,
    val maxFormat: Int?,
    val hasAssets: Boolean,
    val hasData: Boolean,
    val hasShaders: Boolean,
    /** Versioni di Minecraft corrispondenti al formato, quando note a questa app (indicative). */
    val knownMinecraft: String?,
    /** Cartella che avvolge tutto il contenuto, se presente: Minecraft non la riconoscerebbe. */
    val wrapperFolder: String?
)

/** Riassunto dell'indice di un modpack (Modrinth `.mrpack` o CurseForge). */
data class ModpackSummary(
    /** "modrinth" oppure "curseforge". */
    val format: String,
    val name: String?,
    val version: String?,
    val author: String?,
    val minecraft: String?,
    val loader: ModLoader?,
    val loaderVersion: String?,
    val fileCount: Int,
    val overrideCount: Int
)

class JarReport(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val sha1: String,
    val kind: ContentKind,
    val mods: List<ModMetadata>,
    val pack: PackInfo?,
    val nested: List<NestedJar>,
    val entryCount: Int,
    val classCount: Int,
    /** Versione di Java necessaria secondo il bytecode (8, 17, 21…), o null se non ci sono classi. */
    val javaMajor: Int?,
    val nativeLibraries: List<String>,
    val mixinConfigs: List<String>,
    val manifestAttributes: Map<String, String>,
    val signed: Boolean,
    val findings: List<Finding>,
    /** Percorsi dei .jar contenuti, per [ContentKind.MOD_BUNDLE]. */
    val bundleJars: List<String> = emptyList(),
    /** Nome leggibile del caricatore per i tipi non supportati (LiteLoader, Rift…). */
    val otherLoader: String? = null,
    val modpack: ModpackSummary? = null
) {
    val primary: ModMetadata? get() = mods.firstOrNull()

    val hasErrors: Boolean get() = findings.any { it.severity == Severity.ERROR }

    /** Tutti gli identificatori messi a disposizione dal file, compresi quelli dei .jar annidati. */
    val providedIds: Set<String>
        get() {
            val ids = LinkedHashSet<String>()
            for (m in mods) ids.addAll(m.allIds)
            for (n in nested) for (m in n.mods) ids.addAll(m.allIds)
            return ids
        }

    /** I caricatori per cui il file dichiara di funzionare. */
    val loaders: Set<ModLoader> get() = mods.map { it.loader }.toSet()
}
