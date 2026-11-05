package com.mmi.modinstaller.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DependencyPlannerTest {

    private val none = InstalledCheck { _, _ -> InstalledState.NO }
    private val target = Fx.FABRIC_1201
    private val noCancel: () -> Unit = {}

    private fun plan(source: FakeSource, root: String, installed: InstalledCheck = none, limits: PlanLimits = PlanLimits()): CatalogPlan =
        DependencyPlanner(source, limits).plan(source.projects.getValue(root), target, installed, noCancel)

    private fun src(): FakeSource = FakeSource()
    private fun FakeSource.mod(id: String, vararg deps: CatalogDependency, number: String = "1.0.0", title: String = "Mod $id"): FakeSource =
        add(Fx.project(id, title = title), Fx.version("v-$id", id, number, deps = deps.toList()))

    @Test fun aModWithoutDependenciesIsASingleItem() {
        val s = src().mod("AAAA")
        val p = plan(s, "AAAA")
        assertEquals(1, p.items.size)
        assertTrue(p.items[0].isRoot)
        assertEquals("v-AAAA", p.items[0].version.id)
        assertFalse(p.blocked)
        assertTrue(p.problems.isEmpty() && p.alreadyInstalled.isEmpty())
        assertEquals(1000L, p.totalBytes)
    }

    @Test fun requiredDependenciesAreFollowedRecursively() {
        val s = src().mod("AAAA", Fx.dep("BBBB")).mod("BBBB", Fx.dep("CCCC")).mod("CCCC")
        val p = plan(s, "AAAA")
        assertEquals(listOf("AAAA", "BBBB", "CCCC"), p.items.map { it.project.id })
        assertEquals(listOf(null, "Mod AAAA", "Mod BBBB"), p.items.map { it.requiredBy })
        assertEquals(3000L, p.totalBytes)
    }

    @Test fun aCycleDoesNotLoopAndEachModAppearsOnce() {
        val s = src().mod("AAAA", Fx.dep("BBBB")).mod("BBBB", Fx.dep("AAAA"))
        val p = plan(s, "AAAA")
        assertEquals(listOf("AAAA", "BBBB"), p.items.map { it.project.id })
    }

    @Test fun aSharedDependencyIsListedOnce() {
        val s = src().mod("AAAA", Fx.dep("BBBB"), Fx.dep("CCCC")).mod("BBBB", Fx.dep("DDDD")).mod("CCCC", Fx.dep("DDDD")).mod("DDDD")
        val p = plan(s, "AAAA")
        assertEquals(listOf("AAAA", "BBBB", "CCCC", "DDDD"), p.items.map { it.project.id })
        assertEquals(1, p.items.count { it.project.id == "DDDD" })
    }

    @Test fun embeddedDependenciesAreIgnoredAndOptionalOnesAreOnlyListed() {
        val s = src()
            .mod(
                "AAAA", Fx.dep("BBBB", DependencyType.EMBEDDED), Fx.dep("CCCC", DependencyType.OPTIONAL),
                Fx.dep("DDDD", DependencyType.INCOMPATIBLE)
            )
            .mod("BBBB").mod("CCCC", title = "Facoltativa").mod("DDDD", title = "Rovina Tutto")
        val p = plan(s, "AAAA")
        assertEquals(listOf("AAAA"), p.items.map { it.project.id })
        assertEquals(listOf("Facoltativa"), p.optional.map { it.title })
        assertEquals(listOf("Rovina Tutto"), p.incompatible.map { it.title })
        assertFalse("nulla è stato cercato per le dipendenze non richieste", s.calls.any { it == "versions-of:BBBB" || it == "versions-of:CCCC" })
    }

    @Test fun anOptionalModThatIsAlsoRequiredElsewhereIsNotListedAsOptional() {
        val s = src().mod("AAAA", Fx.dep("BBBB"), Fx.dep("CCCC", DependencyType.OPTIONAL)).mod("BBBB", Fx.dep("CCCC")).mod("CCCC")
        val p = plan(s, "AAAA")
        assertEquals(listOf("AAAA", "BBBB", "CCCC"), p.items.map { it.project.id })
        assertTrue(p.optional.isEmpty())
    }

    @Test fun aPinnedVersionIsUsedWhenItIsCompatible() {
        val s = src()
            .mod("AAAA", Fx.dep("BBBB", versionId = "pinned"))
            .add(Fx.project("BBBB"), Fx.version("newest", "BBBB", "2.0", date = "2024-06-01T00:00:00Z"), Fx.version("pinned", "BBBB", "1.5", date = "2024-02-01T00:00:00Z"))
        val p = plan(s, "AAAA")
        assertEquals("pinned", p.items[1].version.id)
    }

    @Test fun aPinnedVersionThatDoesNotFitFallsBackToTheBestCompatibleOne() {
        val s = src()
            .mod("AAAA", Fx.dep("BBBB", versionId = "pinned"))
            .add(
                Fx.project("BBBB"),
                Fx.version("pinned", "BBBB", "1.5", games = listOf("1.19")),
                Fx.version("good", "BBBB", "1.6")
            )
        val p = plan(s, "AAAA")
        assertEquals("good", p.items[1].version.id)
    }

    @Test fun aDependencyGivenOnlyByVersionIdIsResolvedThroughTheVersion() {
        val s = src()
            .mod("AAAA", Fx.dep(null, versionId = "pinned"))
            .add(Fx.project("BBBB"), Fx.version("pinned", "BBBB", "1.5"))
        val p = plan(s, "AAAA")
        assertEquals(listOf("AAAA", "BBBB"), p.items.map { it.project.id })
    }

    @Test fun aRootWithoutACompatibleVersionBlocksAndExplainsWhy() {
        val s = src().add(Fx.project("AAAA", gameVersions = listOf("1.19.2", "1.21")), Fx.version("v1", "AAAA", games = listOf("1.21")))
        val p = plan(s, "AAAA")
        assertTrue(p.blocked)
        assertTrue(p.items.isEmpty())
        val text = p.problems.single().text
        assertTrue(text, text.contains("fabric 1.20.1"))
        assertTrue("deve dire per quali versioni esiste: $text", text.contains("1.19.2") && text.contains("1.21"))
    }

    @Test fun aMissingDependencyIsAWarningAndTheRootIsStillOffered() {
        val s = src().mod("AAAA", Fx.dep("BBBB")).add(Fx.project("BBBB", title = "Libreria"), Fx.version("old", "BBBB", games = listOf("1.18")))
        val p = plan(s, "AAAA")
        assertFalse(p.blocked)
        assertEquals(listOf("AAAA"), p.items.map { it.project.id })
        val text = p.problems.single().text
        assertTrue(text, text.contains("Libreria") && text.contains("Mod AAAA"))
        assertFalse(p.problems.single().blocking)
    }

    @Test fun anExternalDependencyWithoutIdsIsReported() {
        val s = src().mod("AAAA", Fx.dep(null, fileName = "libreria-esterna.jar"))
        val p = plan(s, "AAAA")
        assertEquals(listOf("AAAA"), p.items.map { it.project.id })
        assertTrue(p.problems.single().text.contains("libreria-esterna.jar"))
    }

    @Test fun anInstalledDependencyIsSkippedAndItsOwnDependenciesAreNotFollowed() {
        val s = src().mod("AAAA", Fx.dep("BBBB")).mod("BBBB", Fx.dep("CCCC")).mod("CCCC")
        val installed = InstalledCheck { project, _ -> if (project.id == "BBBB") InstalledState.SAME_FILE else InstalledState.NO }
        val p = plan(s, "AAAA", installed)
        assertEquals(listOf("AAAA"), p.items.map { it.project.id })
        assertEquals("Mod BBBB", p.alreadyInstalled.single().title)
        assertFalse(s.calls.contains("versions-of:CCCC"))
    }

    @Test fun aDependencyOfAnotherVersionAlreadyInTheProfileIsSkippedWithANote() {
        val s = src().mod("AAAA", Fx.dep("BBBB")).mod("BBBB")
        val installed = InstalledCheck { project, _ -> if (project.id == "BBBB") InstalledState.SAME_PROJECT else InstalledState.NO }
        val p = plan(s, "AAAA", installed)
        assertEquals(listOf("AAAA"), p.items.map { it.project.id })
        assertTrue(p.alreadyInstalled.single().note.contains("altra versione"))
    }

    @Test fun theRootAlreadyInstalledWithTheSameFileIsNotOfferedAgain() {
        val s = src().mod("AAAA")
        val p = plan(s, "AAAA", InstalledCheck { _, _ -> InstalledState.SAME_FILE })
        assertTrue(p.items.isEmpty())
        assertEquals("Mod AAAA", p.alreadyInstalled.single().title)
        assertFalse("non è un errore: c'è già", p.blocked)
    }

    @Test fun anotherVersionOfTheRootAlreadyInstalledIsStillOffered() {
        val s = src().mod("AAAA")
        val p = plan(s, "AAAA", InstalledCheck { _, _ -> InstalledState.SAME_PROJECT })
        assertEquals(listOf("AAAA"), p.items.map { it.project.id })
    }

    @Test fun chainsAreCutAtTheDepthLimit() {
        val s = src().mod("AAAA", Fx.dep("BBBB")).mod("BBBB", Fx.dep("CCCC")).mod("CCCC", Fx.dep("DDDD")).mod("DDDD")
        val p = plan(s, "AAAA", limits = PlanLimits(maxDepth = 2, maxItems = 30))
        assertEquals(listOf("AAAA", "BBBB", "CCCC"), p.items.map { it.project.id })
        assertTrue(p.problems.any { it.text.contains("annidate") })
    }

    @Test fun theNumberOfFilesIsLimited() {
        val deps = (1..10).map { Fx.dep("D%03d".format(it)) }
        val s = src().mod("AAAA", *deps.toTypedArray())
        for (i in 1..10) s.mod("D%03d".format(i))
        val p = plan(s, "AAAA", limits = PlanLimits(maxDepth = 6, maxItems = 4))
        assertEquals(4, p.items.size)
        assertTrue(p.problems.any { it.text.contains("troppe") })
    }

    @Test fun ifNamesCannotBeFetchedTheIdsAreShownInstead() {
        val s = src().mod("AAAA", Fx.dep("BBBB"), Fx.dep("CCCC", DependencyType.OPTIONAL)).mod("BBBB").mod("CCCC")
        s.failProjects = CatalogException(CatalogError.NETWORK, "rete")
        val p = plan(s, "AAAA")
        assertEquals(listOf("AAAA", "BBBB"), p.items.map { it.project.id })
        assertTrue("senza nomi si mostra l'id", p.items[1].project.displayName == "BBBB")
        assertEquals(listOf("CCCC"), p.optional.map { it.title })
    }

    @Test fun aNetworkErrorWhileResolvingADependencyStopsThePlan() {
        val s = src().mod("AAAA", Fx.dep("BBBB")).mod("BBBB")
        s.failVersionsFor = mapOf("BBBB" to CatalogException(CatalogError.RATE_LIMITED, "piano", retryAfterSeconds = 30))
        try {
            plan(s, "AAAA")
            fail("un piano incompleto non deve sembrare completo")
        } catch (e: CatalogException) {
            assertEquals(CatalogError.RATE_LIMITED, e.kind)
            assertEquals(30, e.retryAfterSeconds)
        }
    }

    @Test fun cancellationIsNotSwallowed() {
        val s = src().mod("AAAA", Fx.dep("BBBB")).mod("BBBB")
        var calls = 0
        try {
            DependencyPlanner(s).plan(s.projects.getValue("AAAA"), target, none) { if (++calls > 2) throw IllegalStateException("annullato") }
            fail("l'annullamento doveva interrompere il piano")
        } catch (e: IllegalStateException) {
            assertEquals("annullato", e.message)
        }
    }

    @Test fun theChosenVersionIsTheBestOneNotJustTheFirst() {
        val s = src().add(
            Fx.project("AAAA"),
            Fx.version("beta", "AAAA", "2.0-beta", ReleaseChannel.BETA, date = "2024-08-01T00:00:00Z"),
            Fx.version("stable", "AAAA", "1.9", ReleaseChannel.RELEASE, date = "2024-03-01T00:00:00Z")
        )
        val p = plan(s, "AAAA")
        assertEquals("stable", p.items.single().version.id)
        assertNotNull(p.items.single().file)
    }

    @Test fun resourcePacksAreNotFilteredByLoader() {
        val s = src().add(
            Fx.project("PACK", kind = ProjectKind.RESOURCE_PACK),
            Fx.version("p1", "PACK", loaders = listOf("minecraft"), files = listOf(Fx.file("pack.zip")))
        )
        val rp = PlanTarget(ProjectKind.RESOURCE_PACK, listOf("fabric"), "1.20.1")
        val p = DependencyPlanner(s).plan(s.projects.getValue("PACK"), rp, none, noCancel)
        assertEquals("pack.zip", p.items.single().file.fileName)
        // Nessun filtro per caricatore è stato chiesto al servizio.
        assertEquals(listOf("versions-of:PACK"), s.calls)
    }
}
