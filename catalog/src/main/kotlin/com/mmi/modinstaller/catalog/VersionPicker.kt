package com.mmi.modinstaller.catalog

import java.util.Locale

/**
 * Dove deve funzionare ciò che si sceglie: il tipo di contenuto, i caricatori accettati (in ordine di preferenza: per
 * un profilo Quilt «quilt» e poi «fabric») e la versione di Minecraft. Un elenco vuoto o un valore null = nessun vincolo.
 */
class PlanTarget(val kind: ProjectKind, val loaders: List<String>, val gameVersion: String?) {
    /** Descrizione per i messaggi, es. «fabric 1.20.1». */
    val description: String
        get() = (if (kind.usesLoaders) loaders.firstOrNull() else null).let { l ->
            listOfNotNull(l, gameVersion).joinToString(" ").ifEmpty { "il profilo" }
        }
}

/** Una versione scelta e il file da scaricare. */
class Pick(val version: CatalogVersion, val file: CatalogFile)

/** Sceglie la versione giusta tra quelle di un progetto. */
object VersionPicker {

    /** Stati in cui una versione è scaricabile. «draft», «scheduled» e simili non lo sono. */
    private val DOWNLOADABLE_STATUS = setOf("listed", "archived", "unlisted")

    /** File accessori che non sono mai quello da installare. */
    private val SIDE_FILES = setOf("sources-jar", "dev-jar", "javadoc-jar", "signature", "required-resource-pack", "optional-resource-pack")

    /**
     * La versione da proporre: prima le stabili, poi le beta e le alpha; a parità, la più recente; a parità ancora,
     * quella fatta proprio per il caricatore del profilo (non per uno compatibile). Null se nessuna è adatta.
     */
    fun best(versions: List<CatalogVersion>, target: PlanTarget): Pick? =
        versions.mapNotNull { validate(it, target) }
            .sortedWith(
                compareBy<Pick> { it.version.channel.ordinal }
                    .thenByDescending { it.version.datePublished }
                    .thenBy { loaderRank(it.version, target) }
                    .thenBy { if (it.version.status == "archived") 1 else 0 }
            )
            .firstOrNull()

    /** La versione e il suo file, se è adatta al profilo e scaricabile; altrimenti null. */
    fun validate(version: CatalogVersion, target: PlanTarget): Pick? {
        val status = version.status
        if (status != null && status.lowercase(Locale.ROOT) !in DOWNLOADABLE_STATUS) return null
        if (target.kind.usesLoaders && target.loaders.isNotEmpty() && loaderRank(version, target) == NO_LOADER) return null
        val wanted = target.gameVersion
        if (wanted != null && version.gameVersions.none { it.equals(wanted, ignoreCase = true) }) return null
        val file = chooseFile(version, target.kind) ?: return null
        return Pick(version, file)
    }

    /** 0 = il caricatore preferito, 1 = il successivo… ; [NO_LOADER] se la versione non è per nessuno di quelli accettati. */
    private fun loaderRank(version: CatalogVersion, target: PlanTarget): Int {
        if (!target.kind.usesLoaders || target.loaders.isEmpty()) return 0
        for ((index, wanted) in target.loaders.withIndex()) {
            if (version.loaders.any { it.equals(wanted, ignoreCase = true) }) return index
        }
        return NO_LOADER
    }

    private fun chooseFile(version: CatalogVersion, kind: ProjectKind): CatalogFile? {
        // Senza impronta il download non si potrebbe verificare: il file non si propone.
        val usable = version.files.filter { it.expectedHash() != null && it.fileType?.lowercase(Locale.ROOT) !in SIDE_FILES }
        if (usable.isEmpty()) return null
        val matching = usable.filter { it.fileName.lowercase(Locale.ROOT).endsWith(kind.fileExtension) }
        val pool = if (matching.isNotEmpty()) matching else usable
        return pool.firstOrNull { it.primary } ?: pool.first()
    }

    private const val NO_LOADER = Int.MAX_VALUE
}
