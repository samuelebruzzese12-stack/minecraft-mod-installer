package com.mmi.modinstaller.catalog

import com.mmi.modinstaller.core.CancelCheck
import java.security.MessageDigest

/** Dati di prova: progetti, versioni e file del catalogo senza passare dalla rete. */
object Fx {

    const val HASH512 = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    const val HASH1 = "0123456789abcdef0123456789abcdef01234567"

    fun project(
        id: String,
        slug: String? = id.lowercase(),
        title: String = "Mod $id",
        gameVersions: List<String> = listOf("1.20.1"),
        kind: ProjectKind? = ProjectKind.MOD,
        downloads: Long = 1000
    ) = CatalogProject(id, slug, title, "Descrizione di $title", "autore", downloads, 10, null, listOf("fabric"), gameVersions, kind)

    fun file(
        name: String,
        size: Long = 1000,
        primary: Boolean = true,
        sha512: String? = HASH512,
        sha1: String? = null,
        fileType: String? = null,
        url: String = "https://cdn.modrinth.com/data/x/versions/y/$name"
    ) = CatalogFile(url, name, size, primary, sha1, sha512, fileType)

    fun dep(projectId: String?, type: DependencyType = DependencyType.REQUIRED, versionId: String? = null, fileName: String? = null) =
        CatalogDependency(projectId, versionId, fileName, type)

    fun version(
        id: String,
        projectId: String,
        number: String = "1.0.0",
        channel: ReleaseChannel = ReleaseChannel.RELEASE,
        loaders: List<String> = listOf("fabric"),
        games: List<String> = listOf("1.20.1"),
        files: List<CatalogFile> = listOf(file("$projectId-$number.jar")),
        deps: List<CatalogDependency> = emptyList(),
        status: String? = "listed",
        date: String = "2024-01-01T00:00:00Z"
    ) = CatalogVersion(id, projectId, "Versione $number", number, channel, games, loaders, files, deps, status, date, false)

    val FABRIC_1201 = PlanTarget(ProjectKind.MOD, listOf("fabric"), "1.20.1")

    fun sha512Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-512").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha1Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * Catalogo in memoria. Si comporta come il servizio: l'elenco delle versioni di un progetto è già filtrato per
 * caricatori e versioni di Minecraft (basta che uno dei valori richiesti coincida), dalla più recente.
 */
class FakeSource : CatalogSource {
    val projects = LinkedHashMap<String, CatalogProject>()
    val byProject = LinkedHashMap<String, MutableList<CatalogVersion>>()
    val calls = ArrayList<String>()
    var failProjects: CatalogException? = null
    var failVersionsFor: Map<String, CatalogException> = emptyMap()

    fun add(project: CatalogProject, vararg versions: CatalogVersion): FakeSource {
        projects[project.id] = project
        byProject.getOrPut(project.id) { ArrayList() }.addAll(versions)
        return this
    }

    override fun search(
        query: String, kind: ProjectKind, loaders: List<String>, gameVersion: String?,
        sort: SearchSort, offset: Int, limit: Int, cancel: CancelCheck
    ): SearchPage {
        calls.add("search:$query")
        val hits = projects.values.filter { it.title.contains(query, ignoreCase = true) || it.slug.orEmpty().contains(query, true) }
        return SearchPage(hits.drop(offset).take(limit), hits.size, offset, limit)
    }

    override fun projects(ids: List<String>, cancel: CancelCheck): List<CatalogProject> {
        calls.add("projects:" + ids.joinToString(","))
        cancel()
        failProjects?.let { throw it }
        return ids.mapNotNull { projects[it] }
    }

    override fun projectVersions(projectId: String, loaders: List<String>, gameVersions: List<String>, cancel: CancelCheck): List<CatalogVersion> {
        calls.add("versions-of:$projectId")
        cancel()
        failVersionsFor[projectId]?.let { throw it }
        return (byProject[projectId] ?: emptyList())
            .filter { v -> loaders.isEmpty() || v.loaders.any { it in loaders } }
            .filter { v -> gameVersions.isEmpty() || v.gameVersions.any { it in gameVersions } }
            .sortedByDescending { it.datePublished }
    }

    override fun versions(ids: List<String>, cancel: CancelCheck): List<CatalogVersion> {
        calls.add("versions:" + ids.joinToString(","))
        cancel()
        return byProject.values.flatten().filter { it.id in ids }
    }
}
