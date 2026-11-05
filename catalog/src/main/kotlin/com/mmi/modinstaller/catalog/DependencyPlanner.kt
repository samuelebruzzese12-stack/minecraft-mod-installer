package com.mmi.modinstaller.catalog

import com.mmi.modinstaller.core.CancelCheck

/** Se una cosa del catalogo è già nel profilo scelto. */
enum class InstalledState { NO, SAME_FILE, SAME_PROJECT }

/** Lo decide l'app guardando i file del profilo; il catalogo non sa dove stanno. */
fun interface InstalledCheck {
    fun check(project: CatalogProject, file: CatalogFile): InstalledState
}

/** Un file da scaricare. [requiredBy] = nome della mod che lo richiede; null per quello scelto dall'utente. */
class PlanItem(
    val project: CatalogProject,
    val version: CatalogVersion,
    val file: CatalogFile,
    val requiredBy: String?
) {
    val isRoot: Boolean get() = requiredBy == null
}

/** Qualcosa che non si scarica perché c'è già nel profilo. */
class SkippedItem(val title: String, val note: String)

/** Un altro progetto citato (consigliato o incompatibile). */
class LinkedProject(val id: String, val title: String)

class PlanProblem(val text: String, val blocking: Boolean)

/** Cosa scaricare per avere una mod funzionante: la mod, le dipendenze richieste, e le avvertenze. */
class CatalogPlan(
    val items: List<PlanItem>,
    val alreadyInstalled: List<SkippedItem>,
    val optional: List<LinkedProject>,
    val incompatible: List<LinkedProject>,
    val problems: List<PlanProblem>
) {
    /** Non c'è nulla da scaricare perché la versione scelta non esiste per il profilo. */
    val blocked: Boolean get() = problems.any { it.blocking }

    val totalBytes: Long get() = items.sumOf { it.file.size }
}

class PlanLimits(val maxDepth: Int = 6, val maxItems: Int = 30)

/**
 * Costruisce il piano di download: la versione migliore per il profilo e, ricorsivamente, quelle delle dipendenze
 * obbligatorie. Le dipendenze «incluse» nel file non si scaricano; quelle facoltative si elencano ma non si scaricano;
 * quelle incompatibili si segnalano. Cicli, doppioni e catene troppo lunghe sono fermati. Non scarica nulla.
 */
class DependencyPlanner(private val source: CatalogSource, private val limits: PlanLimits = PlanLimits()) {

    fun plan(root: CatalogProject, target: PlanTarget, installed: InstalledCheck, cancel: CancelCheck): CatalogPlan {
        val items = ArrayList<PlanItem>()
        val skipped = ArrayList<SkippedItem>()
        val problems = ArrayList<PlanProblem>()
        val optionalIds = LinkedHashSet<String>()
        val incompatibleIds = LinkedHashSet<String>()
        val gameVersions = listOfNotNull(target.gameVersion)
        val loaders = if (target.kind.usesLoaders) target.loaders else emptyList()

        val rootPick = VersionPicker.best(source.projectVersions(root.id, loaders, gameVersions, cancel), target)
        if (rootPick == null) {
            problems.add(
                PlanProblem(
                    "«" + root.displayName + "» non ha una versione adatta a " + target.description +
                        (if (root.gameVersions.isNotEmpty()) ". Il servizio la elenca per Minecraft " + summarize(root.gameVersions) + "." else "."),
                    blocking = true
                )
            )
            return CatalogPlan(emptyList(), skipped, emptyList(), emptyList(), problems)
        }
        when (installed.check(root, rootPick.file)) {
            InstalledState.SAME_FILE -> skipped.add(SkippedItem(root.displayName, "è già nel profilo (stesso file)"))
            InstalledState.SAME_PROJECT -> {
                // Un'altra versione della stessa mod: si propone comunque, perché l'utente l'ha chiesta (il MOD MANAGER la sostituisce).
                items.add(PlanItem(root, rootPick.version, rootPick.file, null))
            }
            InstalledState.NO -> items.add(PlanItem(root, rootPick.version, rootPick.file, null))
        }
        collectSideDependencies(rootPick.version, optionalIds, incompatibleIds)

        val seen = HashSet<String>()
        seen.add(root.id)
        var level: List<Wanted> = requiredOf(rootPick.version, root.displayName)
        var depth = 1
        while (level.isNotEmpty()) {
            cancel()
            if (depth > limits.maxDepth) {
                problems.add(PlanProblem("Le dipendenze sono troppo annidate: mi fermo a " + limits.maxDepth + " livelli.", blocking = false))
                break
            }
            val pinned = source.versions(level.mapNotNull { it.dependency.versionId }.distinct(), cancel).associateBy { it.id }
            val projectIds = level.mapNotNull { it.dependency.projectId ?: pinned[it.dependency.versionId]?.projectId }.distinct()
            val titles = titlesOf(projectIds, cancel)
            val next = ArrayList<Wanted>()
            for (wanted in level) {
                cancel()
                val dep = wanted.dependency
                val pinnedVersion = dep.versionId?.let { pinned[it] }
                val projectId = dep.projectId ?: pinnedVersion?.projectId
                if (projectId == null) {
                    problems.add(
                        PlanProblem(
                            "«" + wanted.requiredBy + "» richiede " + (dep.fileName?.let { "«$it»" } ?: "un altro file") +
                                ", che non è nel catalogo: va cercato a mano.",
                            blocking = false
                        )
                    )
                    continue
                }
                if (!seen.add(projectId)) continue
                val project = titles[projectId] ?: placeholder(projectId)
                var pick = pinnedVersion?.let { VersionPicker.validate(it, target) }
                if (pick == null) pick = VersionPicker.best(source.projectVersions(projectId, loaders, gameVersions, cancel), target)
                if (pick == null) {
                    problems.add(
                        PlanProblem(
                            "«" + wanted.requiredBy + "» richiede «" + project.displayName + "», che non ha una versione adatta a " +
                                target.description + ".",
                            blocking = false
                        )
                    )
                    continue
                }
                when (installed.check(project, pick.file)) {
                    InstalledState.SAME_FILE -> skipped.add(SkippedItem(project.displayName, "già nel profilo"))
                    InstalledState.SAME_PROJECT -> skipped.add(SkippedItem(project.displayName, "già nel profilo (forse un'altra versione)"))
                    InstalledState.NO -> {
                        if (items.size >= limits.maxItems) {
                            problems.add(PlanProblem("Le dipendenze sono troppe: mi fermo a " + limits.maxItems + " file.", blocking = false))
                            return finish(items, skipped, optionalIds, incompatibleIds, problems, cancel)
                        }
                        items.add(PlanItem(project, pick.version, pick.file, wanted.requiredBy))
                        collectSideDependencies(pick.version, optionalIds, incompatibleIds)
                        next.addAll(requiredOf(pick.version, project.displayName))
                    }
                }
            }
            level = next
            depth++
        }
        return finish(items, skipped, optionalIds, incompatibleIds, problems, cancel)
    }

    private fun finish(
        items: List<PlanItem>,
        skipped: List<SkippedItem>,
        optionalIds: Set<String>,
        incompatibleIds: Set<String>,
        problems: List<PlanProblem>,
        cancel: CancelCheck
    ): CatalogPlan {
        // Una mod che si scarica non è né «facoltativa» né «incompatibile» con se stessa.
        val inPlan = items.map { it.project.id }.toSet()
        val optional = optionalIds.filter { it !in inPlan }
        val incompatible = incompatibleIds.filter { it !in inPlan }
        val titles = titlesOf((optional + incompatible).distinct(), cancel)
        return CatalogPlan(
            items = items,
            alreadyInstalled = skipped,
            optional = optional.map { LinkedProject(it, titles[it]?.displayName ?: it) },
            incompatible = incompatible.map { LinkedProject(it, titles[it]?.displayName ?: it) },
            problems = problems
        )
    }

    private class Wanted(val dependency: CatalogDependency, val requiredBy: String)

    private fun requiredOf(version: CatalogVersion, requiredBy: String): List<Wanted> =
        version.dependencies.filter { it.type == DependencyType.REQUIRED }.map { Wanted(it, requiredBy) }

    private fun collectSideDependencies(version: CatalogVersion, optional: MutableSet<String>, incompatible: MutableSet<String>) {
        for (dep in version.dependencies) {
            val id = dep.projectId ?: continue
            when (dep.type) {
                DependencyType.OPTIONAL -> optional.add(id)
                DependencyType.INCOMPATIBLE -> incompatible.add(id)
                else -> {}
            }
        }
    }

    /** I nomi dei progetti (una sola richiesta). Se il servizio non risponde si usano gli identificativi: non è un errore. */
    private fun titlesOf(ids: List<String>, cancel: CancelCheck): Map<String, CatalogProject> {
        if (ids.isEmpty()) return emptyMap()
        return try {
            source.projects(ids, cancel).associateBy { it.id }
        } catch (e: CatalogException) {
            if (e.kind == CatalogError.CANCELED) throw e
            emptyMap()
        }
    }

    private fun placeholder(id: String) = CatalogProject(id, null, "", "", null, 0, 0, null, emptyList(), emptyList(), null)

    private fun summarize(versions: List<String>): String =
        if (versions.size <= 4) versions.joinToString(", ") else versions.take(3).joinToString(", ") + " … " + versions.last()
}
