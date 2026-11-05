package com.mmi.modinstaller.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionPickerTest {

    private val target = Fx.FABRIC_1201

    private fun v(id: String, number: String = id, channel: ReleaseChannel = ReleaseChannel.RELEASE, date: String = "2024-01-01T00:00:00Z",
                  loaders: List<String> = listOf("fabric"), games: List<String> = listOf("1.20.1"),
                  files: List<CatalogFile> = listOf(Fx.file("m-$id.jar")), status: String? = "listed") =
        Fx.version(id, "PPPP", number, channel, loaders, games, files, emptyList(), status, date)

    @Test fun picksTheNewestStableVersion() {
        val pick = VersionPicker.best(
            listOf(v("old", date = "2023-01-01T00:00:00Z"), v("new", date = "2024-06-01T00:00:00Z"), v("mid", date = "2023-09-01T00:00:00Z")), target
        )
        assertEquals("new", pick!!.version.id)
    }

    @Test fun aStableVersionBeatsANewerBetaOrAlpha() {
        val pick = VersionPicker.best(
            listOf(
                v("alpha", channel = ReleaseChannel.ALPHA, date = "2025-03-01T00:00:00Z"),
                v("beta", channel = ReleaseChannel.BETA, date = "2025-02-01T00:00:00Z"),
                v("stable", channel = ReleaseChannel.RELEASE, date = "2023-01-01T00:00:00Z")
            ), target
        )
        assertEquals("stable", pick!!.version.id)
    }

    @Test fun withoutStableVersionsTheNewestBetaIsUsedBeforeAlpha() {
        val pick = VersionPicker.best(
            listOf(
                v("alpha", channel = ReleaseChannel.ALPHA, date = "2025-03-01T00:00:00Z"),
                v("beta1", channel = ReleaseChannel.BETA, date = "2024-02-01T00:00:00Z"),
                v("beta2", channel = ReleaseChannel.BETA, date = "2024-05-01T00:00:00Z")
            ), target
        )
        assertEquals("beta2", pick!!.version.id)
        assertEquals(ReleaseChannel.BETA, pick.version.channel)
    }

    @Test fun versionsForAnotherLoaderOrMinecraftVersionAreNotUsed() {
        assertNull(VersionPicker.best(listOf(v("forge", loaders = listOf("forge"))), target))
        assertNull(VersionPicker.best(listOf(v("other", games = listOf("1.21"))), target))
        assertEquals("ok", VersionPicker.best(listOf(v("forge", loaders = listOf("forge")), v("ok", loaders = listOf("fabric", "quilt"))), target)!!.version.id)
    }

    @Test fun matchingIsNotCaseSensitive() {
        assertNotNull(VersionPicker.best(listOf(v("a", loaders = listOf("Fabric"))), target))
    }

    @Test fun quiltProfilePrefersQuiltBuildsAtTheSameDateButAcceptsFabric() {
        val quilt = PlanTarget(ProjectKind.MOD, listOf("quilt", "fabric"), "1.20.1")
        val fabricOnly = v("fab", loaders = listOf("fabric"), date = "2024-01-01T00:00:00Z")
        val quiltBuild = v("qui", loaders = listOf("quilt"), date = "2024-01-01T00:00:00Z")
        assertEquals("qui", VersionPicker.best(listOf(fabricOnly, quiltBuild), quilt)!!.version.id)
        // Se c'è solo la build Fabric, un profilo Quilt la può usare.
        assertEquals("fab", VersionPicker.best(listOf(fabricOnly), quilt)!!.version.id)
        // Ma un profilo Fabric non usa mai una build solo Quilt.
        assertNull(VersionPicker.best(listOf(quiltBuild), target))
    }

    @Test fun notDownloadableStatesAreSkipped() {
        for (status in listOf("draft", "scheduled", "rejected", "withheld", "unknown")) {
            assertNull(status, VersionPicker.best(listOf(v("x", status = status)), target))
        }
        assertNotNull(VersionPicker.best(listOf(v("x", status = null)), target))
        assertNotNull(VersionPicker.best(listOf(v("x", status = "unlisted")), target))
    }

    @Test fun aListedVersionBeatsAnArchivedOneOfTheSameDate() {
        val pick = VersionPicker.best(listOf(v("arch", status = "archived"), v("list", status = "listed")), target)
        assertEquals("list", pick!!.version.id)
    }

    @Test fun primaryFileIsChosenAndSideFilesAreIgnored() {
        val pick = VersionPicker.best(
            listOf(
                v(
                    "a", files = listOf(
                        Fx.file("mod-sources.jar", primary = false, fileType = "sources-jar"),
                        Fx.file("mod-dev.jar", primary = false, fileType = "dev-jar"),
                        Fx.file("mod-extra.jar", primary = false),
                        Fx.file("mod.jar", primary = true)
                    )
                )
            ), target
        )
        assertEquals("mod.jar", pick!!.file.fileName)
    }

    @Test fun withoutAPrimaryTheFirstUsableFileIsChosen() {
        val pick = VersionPicker.best(
            listOf(v("a", files = listOf(Fx.file("src.jar", primary = false, fileType = "sources-jar"), Fx.file("first.jar", primary = false), Fx.file("second.jar", primary = false)))),
            target
        )
        assertEquals("first.jar", pick!!.file.fileName)
    }

    @Test fun filesWithoutAValidHashAreNeverOffered() {
        assertNull(VersionPicker.best(listOf(v("a", files = listOf(Fx.file("mod.jar", sha512 = null, sha1 = null)))), target))
        assertNull(VersionPicker.best(listOf(v("a", files = listOf(Fx.file("mod.jar", sha512 = "zz".repeat(64), sha1 = "short")))), target))
        // Con la sola SHA-1 valida il file è ammesso e l'impronta richiesta è quella.
        val pick = VersionPicker.best(listOf(v("a", files = listOf(Fx.file("mod.jar", sha512 = null, sha1 = Fx.HASH1.uppercase())))), target)
        assertEquals("sha1:" + Fx.HASH1, pick!!.file.expectedHash())
    }

    @Test fun theStrongerHashIsPreferredWhenBothArePresent() {
        val f = Fx.file("mod.jar", sha512 = Fx.HASH512.uppercase(), sha1 = Fx.HASH1)
        assertEquals("sha512:" + Fx.HASH512, f.expectedHash())
    }

    @Test fun aFileWithTheRightExtensionIsPreferredOverAPrimaryOfAnotherKind() {
        val pick = VersionPicker.best(
            listOf(v("a", files = listOf(Fx.file("readme.txt", primary = true), Fx.file("mod.jar", primary = false)))), target
        )
        assertEquals("mod.jar", pick!!.file.fileName)
    }

    @Test fun resourcePacksIgnoreLoadersButStillFilterTheGameVersion() {
        val rp = PlanTarget(ProjectKind.RESOURCE_PACK, listOf("fabric"), "1.20.1")
        val pack = v("rp", loaders = listOf("minecraft"), files = listOf(Fx.file("pack.zip")))
        assertEquals("rp", VersionPicker.best(listOf(pack), rp)!!.version.id)
        assertNull(VersionPicker.best(listOf(v("old", loaders = listOf("minecraft"), games = listOf("1.19"), files = listOf(Fx.file("p.zip")))), rp))
    }

    @Test fun noGameVersionMeansAnyVersion() {
        val any = PlanTarget(ProjectKind.MOD, listOf("fabric"), null)
        assertNotNull(VersionPicker.best(listOf(v("a", games = listOf("1.8.9"))), any))
    }

    @Test fun emptyListGivesNothing() {
        assertNull(VersionPicker.best(emptyList(), target))
        assertTrue(target.description.contains("fabric") && target.description.contains("1.20.1"))
    }
}
