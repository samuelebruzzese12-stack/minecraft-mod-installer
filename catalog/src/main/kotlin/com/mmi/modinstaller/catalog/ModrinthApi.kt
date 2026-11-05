package com.mmi.modinstaller.catalog

import com.mmi.modinstaller.core.CancelCheck
import com.mmi.modinstaller.core.Json
import com.mmi.modinstaller.core.JsonParseException
import com.mmi.modinstaller.core.asJsonList
import com.mmi.modinstaller.core.asJsonObject
import com.mmi.modinstaller.core.bool
import com.mmi.modinstaller.core.list
import com.mmi.modinstaller.core.long
import com.mmi.modinstaller.core.obj
import com.mmi.modinstaller.core.str
import java.net.URLEncoder

/** Ciò che serve al resto dell'app dal catalogo. Le prove usano una versione in memoria al posto della rete. */
interface CatalogSource {
    fun search(
        query: String,
        kind: ProjectKind,
        loaders: List<String>,
        gameVersion: String?,
        sort: SearchSort,
        offset: Int,
        limit: Int,
        cancel: CancelCheck
    ): SearchPage

    /** I progetti con questi identificativi (quelli che non esistono mancano dall'elenco). */
    fun projects(ids: List<String>, cancel: CancelCheck): List<CatalogProject>

    /** Le versioni di un progetto adatte ai caricatori e alle versioni di Minecraft indicati (elenchi vuoti = tutte). */
    fun projectVersions(projectId: String, loaders: List<String>, gameVersions: List<String>, cancel: CancelCheck): List<CatalogVersion>

    /** Le versioni con questi identificativi. */
    fun versions(ids: List<String>, cancel: CancelCheck): List<CatalogVersion>
}

/**
 * Client dell'API pubblica di Modrinth (v2): https://docs.modrinth.com/api/ . Non serve nessuna chiave e non si invia
 * nulla dell'utente oltre a ciò che si cerca. Ogni risposta è trattata come non fidata: campi mancanti o di tipo
 * sbagliato non fanno mai arrestare l'app, e identificativi e quantità sono controllati e limitati.
 */
class ModrinthApi(
    private val http: CatalogHttp,
    private val baseUrl: String = DEFAULT_BASE_URL
) : CatalogSource {

    override fun search(
        query: String,
        kind: ProjectKind,
        loaders: List<String>,
        gameVersion: String?,
        sort: SearchSort,
        offset: Int,
        limit: Int,
        cancel: CancelCheck
    ): SearchPage {
        val groups = ArrayList<List<String>>()
        groups.add(listOf("project_type:" + kind.apiName))
        if (kind.usesLoaders) {
            val loaderGroup = loaders.mapNotNull { token(it) }.distinct().map { "categories:$it" }
            if (loaderGroup.isNotEmpty()) groups.add(loaderGroup)
        }
        token(gameVersion)?.let { groups.add(listOf("versions:$it")) }

        val safeLimit = limit.coerceIn(1, MAX_SEARCH_LIMIT)
        val safeOffset = offset.coerceAtLeast(0)
        val path = "/search?query=" + enc(query.trim().take(MAX_QUERY_LENGTH)) +
            "&facets=" + enc(facetsJson(groups)) +
            "&index=" + sort.apiName +
            "&offset=" + safeOffset +
            "&limit=" + safeLimit
        val root = getJson(path, cancel).asJsonObject()
            ?: throw CatalogException(CatalogError.BAD_RESPONSE, "Modrinth ha risposto in un formato che non conosco.")
        val hits = (root.list("hits") ?: emptyList()).mapNotNull { parseProject(it.asJsonObject()) }
        return SearchPage(
            hits = hits,
            total = (root.long("total_hits") ?: hits.size.toLong()).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            offset = (root.long("offset") ?: safeOffset.toLong()).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            limit = (root.long("limit") ?: safeLimit.toLong()).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        )
    }

    override fun projects(ids: List<String>, cancel: CancelCheck): List<CatalogProject> {
        val out = ArrayList<CatalogProject>()
        for (chunk in cleanIds(ids).chunked(ID_CHUNK)) {
            val array = getJson("/projects?ids=" + enc(idsJson(chunk)), cancel).asJsonList()
                ?: throw CatalogException(CatalogError.BAD_RESPONSE, "Modrinth ha risposto in un formato che non conosco.")
            array.mapNotNullTo(out) { parseProject(it.asJsonObject()) }
        }
        return out
    }

    override fun projectVersions(
        projectId: String,
        loaders: List<String>,
        gameVersions: List<String>,
        cancel: CancelCheck
    ): List<CatalogVersion> {
        val id = cleanIds(listOf(projectId)).firstOrNull()
            ?: throw CatalogException(CatalogError.BAD_RESPONSE, "Identificativo del progetto non valido.")
        val params = ArrayList<String>()
        val loaderTokens = loaders.mapNotNull { token(it) }.distinct()
        if (loaderTokens.isNotEmpty()) params.add("loaders=" + enc(idsJson(loaderTokens)))
        val versionTokens = gameVersions.mapNotNull { token(it) }.distinct()
        if (versionTokens.isNotEmpty()) params.add("game_versions=" + enc(idsJson(versionTokens)))
        val path = "/project/" + enc(id) + "/version" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
        val array = getJson(path, cancel).asJsonList()
            ?: throw CatalogException(CatalogError.BAD_RESPONSE, "Modrinth ha risposto in un formato che non conosco.")
        return array.take(MAX_VERSIONS).mapNotNull { parseVersion(it.asJsonObject()) }
    }

    override fun versions(ids: List<String>, cancel: CancelCheck): List<CatalogVersion> {
        val out = ArrayList<CatalogVersion>()
        for (chunk in cleanIds(ids).chunked(ID_CHUNK)) {
            val array = getJson("/versions?ids=" + enc(idsJson(chunk)), cancel).asJsonList()
                ?: throw CatalogException(CatalogError.BAD_RESPONSE, "Modrinth ha risposto in un formato che non conosco.")
            array.mapNotNullTo(out) { parseVersion(it.asJsonObject()) }
        }
        return out
    }

    // ------------------------------------------------------------------ rete

    private fun getJson(path: String, cancel: CancelCheck): Any? {
        val reply = http.get(baseUrl + path, cancel)
        val status = reply.status
        if (status in 200..299) {
            return try {
                Json.parse(reply.body)
            } catch (e: JsonParseException) {
                throw CatalogException(CatalogError.BAD_RESPONSE, "Modrinth ha risposto con dati che non riesco a leggere.", cause = e)
            }
        }
        val detail = errorDetail(reply.body)
        when {
            status == 404 -> throw CatalogException(CatalogError.NOT_FOUND, "Modrinth non ha trovato quello che cerco.")
            status == 429 -> {
                val wait = reply.retryAfterSeconds
                throw CatalogException(
                    CatalogError.RATE_LIMITED,
                    "Modrinth chiede di rallentare" + (if (wait != null) ": riprova tra $wait secondi." else ": riprova tra un minuto."),
                    retryAfterSeconds = wait
                )
            }
            status >= 500 -> throw CatalogException(CatalogError.SERVER, "Modrinth ha un problema in questo momento (codice $status): riprova più tardi.")
            else -> throw CatalogException(CatalogError.BAD_RESPONSE, "Modrinth ha rifiutato la richiesta (codice $status)" + (if (detail != null) ": $detail" else "."))
        }
    }

    /** Il motivo dato dal servizio nel corpo dell'errore, accorciato; null se non c'è. */
    private fun errorDetail(body: String): String? = try {
        Json.parse(body).asJsonObject()?.str("description")?.trim()?.take(120)?.takeIf { it.isNotEmpty() }
    } catch (e: JsonParseException) {
        null
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.modrinth.com/v2"
        const val MAX_SEARCH_LIMIT = 50
        private const val MAX_QUERY_LENGTH = 100
        private const val MAX_VERSIONS = 400
        private const val ID_CHUNK = 50

        private val TOKEN = Regex("^[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}$")
        private val ID = Regex("^[A-Za-z0-9_\\-]{1,64}$")

        /** Valore ammesso nei filtri (versione di Minecraft, caricatore); altrimenti null: non entra mai nella richiesta. */
        internal fun token(value: String?): String? = value?.trim()?.takeIf { TOKEN.matches(it) }

        internal fun cleanIds(ids: List<String>): List<String> = ids.map { it.trim() }.filter { ID.matches(it) }.distinct()

        private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

        /** `["a","b"]` con soli valori già controllati (niente virgolette né barre dentro). */
        internal fun idsJson(values: List<String>): String = values.joinToString(",", "[", "]") { "\"" + it + "\"" }

        /** `[["a","b"],["c"]]`: i gruppi si combinano con E, i valori dentro un gruppo con O. */
        internal fun facetsJson(groups: List<List<String>>): String =
            groups.joinToString(",", "[", "]") { g -> g.joinToString(",", "[", "]") { "\"" + it + "\"" } }

        // ------------------------------------------------------------------ lettura delle risposte

        private fun strings(o: Map<String, Any?>, key: String, max: Int): List<String> =
            (o.list(key) ?: emptyList()).mapNotNull { it as? String }.take(max)

        private fun validId(value: String?): String? = value?.takeIf { ID.matches(it) }

        internal fun parseProject(o: Map<String, Any?>?): CatalogProject? {
            if (o == null) return null
            // Un risultato di ricerca ha «project_id»; un progetto vero e proprio ha «id».
            val isHit = o.containsKey("project_id")
            val id = validId(if (isHit) o.str("project_id") else o.str("id")) ?: return null
            val mcVersions = if (isHit) strings(o, "versions", 300) else strings(o, "game_versions", 300)
            return CatalogProject(
                id = id,
                slug = o.str("slug")?.takeIf { it.isNotBlank() && it.length <= 64 && !it.contains('/') },
                title = o.str("title")?.trim()?.take(120) ?: "",
                description = o.str("description")?.trim()?.take(400) ?: "",
                author = o.str("author")?.trim()?.take(80)?.takeIf { it.isNotEmpty() },
                downloads = (o.long("downloads") ?: 0L).coerceAtLeast(0L),
                follows = (o.long("follows") ?: 0L).coerceAtLeast(0L),
                iconUrl = o.str("icon_url")?.takeIf { it.length <= 500 },
                categories = strings(o, "categories", 40),
                gameVersions = mcVersions,
                kind = ProjectKind.fromApi(o.str("project_type"))
            )
        }

        internal fun parseFile(o: Map<String, Any?>?): CatalogFile? {
            if (o == null) return null
            val url = o.str("url")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2048 } ?: return null
            val name = o.str("filename")?.trim()?.take(255)?.takeIf { it.isNotEmpty() } ?: return null
            val hashes = o.obj("hashes")
            return CatalogFile(
                url = url,
                fileName = name,
                size = (o.long("size") ?: 0L).coerceAtLeast(0L),
                primary = o.bool("primary") ?: false,
                sha1 = hashes?.str("sha1"),
                sha512 = hashes?.str("sha512"),
                fileType = o.str("file_type")
            )
        }

        internal fun parseDependency(o: Map<String, Any?>?): CatalogDependency? {
            if (o == null) return null
            val type = DependencyType.fromApi(o.str("dependency_type")) ?: return null
            return CatalogDependency(
                projectId = validId(o.str("project_id")),
                versionId = validId(o.str("version_id")),
                fileName = o.str("file_name")?.take(255),
                type = type
            )
        }

        internal fun parseVersion(o: Map<String, Any?>?): CatalogVersion? {
            if (o == null) return null
            val id = validId(o.str("id")) ?: return null
            val projectId = validId(o.str("project_id")) ?: return null
            return CatalogVersion(
                id = id,
                projectId = projectId,
                name = o.str("name")?.trim()?.take(160) ?: "",
                versionNumber = o.str("version_number")?.trim()?.take(80) ?: "",
                channel = ReleaseChannel.fromApi(o.str("version_type")),
                gameVersions = strings(o, "game_versions", 500),
                loaders = strings(o, "loaders", 30),
                files = (o.list("files") ?: emptyList()).take(20).mapNotNull { parseFile(it.asJsonObject()) },
                dependencies = (o.list("dependencies") ?: emptyList()).take(100).mapNotNull { parseDependency(it.asJsonObject()) },
                status = o.str("status"),
                datePublished = o.str("date_published")?.take(40) ?: "",
                featured = o.bool("featured") ?: false
            )
        }
    }
}
