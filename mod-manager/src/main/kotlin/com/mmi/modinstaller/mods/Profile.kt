package com.mmi.modinstaller.mods

import com.mmi.modinstaller.core.McVersion
import com.mmi.modinstaller.core.JavaRequirements as CoreJavaRequirements
import com.mmi.modinstaller.jar.ContentKind
import com.mmi.modinstaller.jar.ModLoader
import com.mmi.modinstaller.jar.ModMetadata

/** Cartelle di un profilo, nella stessa disposizione usata dai programmi di avvio di Minecraft Java. */
enum class ItemCategory(val folder: String, val label: String, val extension: String) {
    MOD("mods", "Mod", "jar"),
    RESOURCE_PACK("resourcepacks", "Resource pack", "zip"),
    SHADER_PACK("shaderpacks", "Shader", "zip"),
    DATA_PACK("datapacks", "Data pack", "zip");

    val key: String get() = name.lowercase()

    companion object {
        fun fromKey(key: String?): ItemCategory? = values().firstOrNull { it.key == key }

        fun forKind(kind: ContentKind): ItemCategory? = when (kind) {
            ContentKind.MOD, ContentKind.LIBRARY, ContentKind.OPTIFINE -> MOD
            ContentKind.RESOURCE_PACK -> RESOURCE_PACK
            ContentKind.SHADER_PACK -> SHADER_PACK
            ContentKind.DATA_PACK -> DATA_PACK
            else -> null
        }
    }
}

/**
 * Un profilo è una raccolta indipendente di mod e pacchetti per una versione di Minecraft e un caricatore: quello che
 * c'è in un profilo non tocca gli altri.
 */
data class Profile(
    val id: String,
    val name: String,
    val minecraft: String,
    val loader: ModLoader,
    /** Versione del caricatore (es. 0.15.7 per Fabric, 47.2.0 per Forge): serve a controllare i requisiti delle mod. */
    val loaderVersion: String? = null,
    /** Versione di Java scelta; se manca si ricava dalla versione di Minecraft. */
    val javaMajor: Int? = null,
    val createdAt: Long = 0L
) {
    val effectiveJava: Int? get() = javaMajor ?: ProfileJava.forMinecraft(minecraft)

    val title: String get() = "$name · ${loader.label} · Minecraft $minecraft"
}

/** Java richiesto da una versione di Minecraft Java Edition: usa la stessa tabella della schermata JAVA EDITION. */
object ProfileJava {
    /** La prima versione di Minecraft per cui questa app non sa dire quale Java serve (invece di indovinare). */
    private val UNKNOWN_FROM = McVersion(listOf(1, 22))

    /** Versione di Java per [minecraft]; null se la versione non è una 1.x conosciuta, così non si indovina. */
    fun forMinecraft(minecraft: String?): Int? {
        val v = McVersion.parse(minecraft) ?: return null
        if (v.parts.first() != 1 || v >= UNKNOWN_FROM) return null
        return CoreJavaRequirements.javaFor(v)
    }
}

/** Un file gestito in un profilo, con le informazioni lette quando è stato installato. */
data class ProfileItem(
    val id: String,
    val category: ItemCategory,
    /** Nome del file nel profilo, senza il suffisso «.disabled». */
    val fileName: String,
    val enabled: Boolean,
    val sha256: String,
    val sha1: String,
    val sizeBytes: Long,
    val installedAt: Long,
    /** Nome del file scelto dall'utente. */
    val origin: String,
    /** Da dove viene: "file", "download", "modrinth"… */
    val source: String,
    val kind: ContentKind,
    val mods: List<ModMetadata> = emptyList(),
    /** Mod contenute nel file (solo identificatori e versioni): contano per le dipendenze di altre mod. */
    val nested: List<ModMetadata> = emptyList(),
    val javaMajor: Int? = null,
    val packFormat: Int? = null,
    val note: String? = null,
    /** Impronta del file scelto dall'utente, quando il pacchetto è stato risistemato e quindi [sha256] è diversa. */
    val sourceSha256: String? = null
) {
    /** True se [sha] è quella del file nel profilo o del file originale da cui è stato ricavato. */
    fun hasSameContentAs(sha: String): Boolean = sha.isNotEmpty() && (sha256 == sha || sourceSha256 == sha)

    /** Nome del file sul disco: le mod disattivate prendono il suffisso «.disabled», che i caricatori ignorano. */
    val physicalName: String get() = if (enabled) fileName else "$fileName$DISABLED_SUFFIX"

    val displayName: String
        get() = mods.firstOrNull()?.let { m -> m.displayName + (m.version?.let { " $it" } ?: "") }
            ?: origin.substringBeforeLast('.').ifEmpty { fileName }

    /** Tutti gli identificatori messi a disposizione da questo file. */
    val providedIds: Set<String>
        get() {
            val ids = LinkedHashSet<String>()
            for (m in mods) ids.addAll(m.allIds)
            for (m in nested) ids.addAll(m.allIds)
            return ids
        }

    companion object {
        const val DISABLED_SUFFIX = ".disabled"
    }
}

/** Un'operazione già eseguita, con quanto serve per annullarla. */
data class JournalEntry(
    val id: String,
    val time: Long,
    val label: String,
    val steps: List<JournalStep>
)

sealed class JournalStep {
    /** Il file è stato aggiunto: annullare significa eliminarlo. */
    data class Added(val item: ProfileItem) : JournalStep()

    /** Il file è stato messo da parte nel cestino del profilo: annullare significa rimetterlo al suo posto. */
    data class Removed(val item: ProfileItem, val trashName: String) : JournalStep()

    /** Il file è stato attivato o disattivato: annullare significa ripristinare lo stato precedente. */
    data class Toggled(val itemId: String, val wasEnabled: Boolean) : JournalStep()
}
