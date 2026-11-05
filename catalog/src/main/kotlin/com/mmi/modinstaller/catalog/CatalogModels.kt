package com.mmi.modinstaller.catalog

import java.util.Locale

/** Che tipo di contenuto si cerca nel catalogo. */
enum class ProjectKind(val apiName: String, val label: String, val fileExtension: String, val usesLoaders: Boolean) {
    MOD("mod", "Mod", ".jar", true),
    RESOURCE_PACK("resourcepack", "Resource pack", ".zip", false),
    SHADER("shader", "Shader", ".zip", false),
    DATAPACK("datapack", "Data pack", ".zip", false),
    MODPACK("modpack", "Modpack", ".mrpack", true);

    companion object {
        fun fromApi(name: String?): ProjectKind? = values().firstOrNull { it.apiName == name }
    }
}

/** Ordine dei risultati della ricerca (nomi dell'API di Modrinth). */
enum class SearchSort(val apiName: String, val label: String) {
    RELEVANCE("relevance", "Rilevanza"),
    DOWNLOADS("downloads", "Più scaricati"),
    FOLLOWS("follows", "Più seguiti"),
    NEWEST("newest", "Più recenti"),
    UPDATED("updated", "Aggiornati di recente")
}

/** Canale di pubblicazione di una versione: l'app propone prima quelle stabili. */
enum class ReleaseChannel(val label: String) {
    RELEASE("stabile"),
    BETA("beta"),
    ALPHA("alpha");

    companion object {
        fun fromApi(name: String?): ReleaseChannel = when (name?.lowercase(Locale.ROOT)) {
            "release" -> RELEASE
            "beta" -> BETA
            // Un valore sconosciuto è trattato come il più instabile: non viene mai preferito a una versione stabile.
            else -> ALPHA
        }
    }
}

enum class DependencyType {
    REQUIRED,
    OPTIONAL,
    INCOMPATIBLE,
    EMBEDDED;

    companion object {
        fun fromApi(name: String?): DependencyType? = when (name?.lowercase(Locale.ROOT)) {
            "required" -> REQUIRED
            "optional" -> OPTIONAL
            "incompatible" -> INCOMPATIBLE
            "embedded" -> EMBEDDED
            else -> null
        }
    }
}

/** Un progetto del catalogo (una mod, un resource pack…), così come lo descrive il servizio. */
data class CatalogProject(
    val id: String,
    val slug: String?,
    val title: String,
    val description: String,
    val author: String?,
    val downloads: Long,
    val follows: Long,
    val iconUrl: String?,
    /** Categorie e caricatori (Modrinth li mette insieme: «fabric», «adventure»…). */
    val categories: List<String>,
    /** Versioni di Minecraft supportate (nei risultati di ricerca; nel resto può mancare). */
    val gameVersions: List<String>,
    val kind: ProjectKind?
) {
    /** Nome da mostrare: il titolo, o l'identificativo se il servizio non ne ha dato uno. */
    val displayName: String get() = title.ifBlank { slug ?: id }
}

data class SearchPage(val hits: List<CatalogProject>, val total: Int, val offset: Int, val limit: Int)

/** Un file scaricabile di una versione. Le impronte sono quelle pubblicate dal servizio. */
data class CatalogFile(
    val url: String,
    val fileName: String,
    val size: Long,
    val primary: Boolean,
    val sha1: String?,
    val sha512: String?,
    /** «required-resource-pack», «sources-jar»… oppure null per il file normale. */
    val fileType: String?
) {
    /**
     * L'impronta da far verificare al download, nella forma «sha512:…» (preferita) o «sha1:…». Null se il servizio non
     * ne ha data una valida: un file senza impronta non si scarica.
     */
    fun expectedHash(): String? = when {
        isHex(sha512, 128) -> "sha512:" + sha512!!.lowercase(Locale.ROOT)
        isHex(sha1, 40) -> "sha1:" + sha1!!.lowercase(Locale.ROOT)
        else -> null
    }

    private fun isHex(text: String?, length: Int): Boolean =
        text != null && text.length == length && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}

data class CatalogDependency(
    val projectId: String?,
    val versionId: String?,
    val fileName: String?,
    val type: DependencyType
)

data class CatalogVersion(
    val id: String,
    val projectId: String,
    val name: String,
    val versionNumber: String,
    val channel: ReleaseChannel,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val files: List<CatalogFile>,
    val dependencies: List<CatalogDependency>,
    /** «listed», «archived», «unlisted»… oppure null se il servizio non lo dice. */
    val status: String?,
    /** Data di pubblicazione in formato ISO-8601 (si confronta come testo), o vuoto. */
    val datePublished: String,
    val featured: Boolean
)

/** Perché una richiesta al catalogo non è riuscita. */
enum class CatalogError { NETWORK, TIMEOUT, RATE_LIMITED, SERVER, NOT_FOUND, BAD_RESPONSE, TOO_LARGE, BLOCKED, CANCELED }

/** Errore del catalogo, con un messaggio già pronto da mostrare. */
class CatalogException(
    val kind: CatalogError,
    message: String,
    val retryAfterSeconds: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause)
