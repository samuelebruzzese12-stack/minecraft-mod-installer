package com.mmi.modinstaller.core

import java.io.File

enum class ContentKind(val label: String) {
    RESOURCE_PACK("Resource Pack (texture)"),
    BEHAVIOR_PACK("Behavior Pack"),
    SKIN_PACK("Pacchetto di skin"),
    ADDON("Add-on"),
    WORLD("Mappa / mondo"),
    WORLD_TEMPLATE("Template di mondo"),
    JAVA_CONTENT("Contenuto per Java Edition"),
    MANIFEST_ONLY("Solo manifest"),
    UNKNOWN("Non riconosciuto")
}

enum class OutputFormat(val extension: String) {
    MCPACK("mcpack"),
    MCADDON("mcaddon"),
    MCWORLD("mcworld"),
    MCTEMPLATE("mctemplate")
}

enum class ItemStatus { READY, WARNINGS, OVERRIDE, BLOCKED }

enum class PartRole { PACK, WORLD, TEMPLATE }

enum class DependencyState { IN_ITEM, IN_ARCHIVE, IN_LIBRARY, BUILTIN, SCRIPT_MODULE, MISSING }

class DependencyStatus(
    val uuid: String?,
    val moduleName: String?,
    val versionText: String,
    val state: DependencyState,
    val resolvedName: String?
)

/** Una cartella dell'archivio che diventerà un pacchetto o un mondo. */
class PartInfo(
    val role: PartRole,
    /** Cartella dentro l'archivio ("" = radice), senza barra finale. */
    val rootPath: String,
    val displayName: String,
    val packKind: PackKind?,
    val manifest: PackManifest?,
    val level: LevelInfo?,
    val fileCount: Int,
    val sizeBytes: Long
)

/** Un contenuto installabile (o non installabile, con le ragioni) trovato in un archivio. */
class ContentItem(
    val id: String,
    val kind: ContentKind,
    val title: String,
    val description: String?,
    val author: String?,
    val version: String?,
    val parts: List<PartInfo>,
    val sizeBytes: Long,
    val fileCount: Int,
    dependencies: List<DependencyStatus>,
    initialFindings: List<Finding>,
    val output: OutputFormat?,
    val iconBytes: ByteArray?,
    val previewImages: List<ByteArray>,
    /** UUID (minuscoli) dei pacchetti contenuti. */
    val uuids: List<String>,
    /** File che contiene davvero i dati: l'archivio scelto oppure un archivio annidato estratto. */
    val archiveFile: File,
    val archiveFormat: ArchiveFormat,
    /** Percorso leggibile, es. "Pack.zip › Addon.mcaddon". */
    val container: String,
    /**
     * False solo se [archiveFile] è già un ZIP pulito con il contenuto alla radice (nessuna cartella da
     * togliere, nessun file inutile o eseguibile): in quel caso il file può essere passato a Minecraft com'è.
     */
    val repackRequired: Boolean = true
) {
    private val mutableFindings = ArrayList<Finding>(initialFindings)

    val findings: List<Finding> get() = mutableFindings

    internal fun addFinding(f: Finding) {
        mutableFindings.add(f)
    }

    internal fun removeFindings(predicate: (Finding) -> Boolean) {
        mutableFindings.removeAll(predicate)
    }

    /** Dipendenze dichiarate dai pacchetti e dove sono state trovate. */
    var dependencies: List<DependencyStatus> = dependencies
        internal set

    /**
     * Id dei contenuti dello stesso file da cui questo dipende (ad esempio il resource pack di un behavior pack
     * distribuiti come due .mcpack separati): vanno importati insieme, altrimenti il contenuto resta incompleto.
     */
    var requires: List<String> = emptyList()
        internal set

    /**
     * Titolo del contenuto che ha lo stesso UUID e compare prima nel rapporto: Minecraft ne importerebbe uno solo,
     * quindi questo non viene selezionato in automatico.
     */
    var sameUuidAs: String? = null
        internal set

    val status: ItemStatus
        get() {
            var warnings = false
            var overridable = false
            for (f in mutableFindings) {
                when (f.severity) {
                    Severity.ERROR -> if (f.overridable) overridable = true else return ItemStatus.BLOCKED
                    Severity.WARNING -> warnings = true
                    Severity.INFO -> Unit
                }
            }
            if (output == null) return ItemStatus.BLOCKED
            return when {
                overridable -> ItemStatus.OVERRIDE
                warnings -> ItemStatus.WARNINGS
                else -> ItemStatus.READY
            }
        }

    /** True se non c'è alcun errore che impedisca l'installazione (neppure con "forza"). */
    val installable: Boolean get() = status != ItemStatus.BLOCKED

    /** True se la compatibilità con la versione di Minecraft installata non è stata verificata. */
    val compatibilityUnverified: Boolean
        get() = mutableFindings.any { it.code == FindingCode.ENGINE_UNKNOWN }
}

class SourceInfo(val displayName: String, val sizeBytes: Long, val format: ArchiveFormat)

class AnalysisReport(
    val source: SourceInfo,
    val items: List<ContentItem>,
    /** Osservazioni che riguardano l'intero file e non un singolo contenuto. */
    val findings: List<Finding>
) {
    val hasInstallableItems: Boolean get() = items.any { it.installable }
}

data class MinecraftInfo(val packageName: String, val versionName: String?, val version: McVersion?)

data class KnownPack(val uuid: String, val name: String, val versionText: String, val version: McVersion?)

fun interface KnownPacks {
    fun lookup(uuid: String): KnownPack?
}

class AnalyzeContext(
    /** Cartella di lavoro dove vengono estratti gli archivi annidati; va eliminata dal chiamante. */
    val workDir: File,
    val opener: ArchiveOpener = ZipOnlyOpener(),
    val limits: SafetyLimits = SafetyLimits(),
    val minecraft: MinecraftInfo? = null,
    val known: KnownPacks? = null,
    val cancel: CancelCheck = {},
    val progress: ProgressCallback? = null
)
