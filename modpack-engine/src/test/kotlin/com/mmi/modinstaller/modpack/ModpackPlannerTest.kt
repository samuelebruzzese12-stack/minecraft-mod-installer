package com.mmi.modinstaller.modpack

import com.mmi.modinstaller.download.UrlPolicy
import com.mmi.modinstaller.jar.ModLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ModpackPlannerTest {

    private lateinit var dir: File

    @Before fun setUp() {
        dir = Fx.tempDir()
    }

    @After fun tearDown() {
        TestServer.deleteTree(dir)
    }

    private val bytes = Fx.fabricJar("demo", "Demo", "1.0.0")
    private val cdn = "https://cdn.modrinth.com/data/AAAA/versions/BBBB/demo.jar"
    private val policy = UrlPolicy()

    private fun file(path: String, urls: List<String> = listOf(cdn), client: Side = Side.REQUIRED, content: ByteArray = bytes) =
        PackFile(path, Fx.sha1Hex(content), Fx.sha512Hex(content), content.size.toLong(), urls, client, Side.REQUIRED)

    private fun pack(files: List<PackFile>, minecraft: String? = "1.20.1", loader: ModLoader? = ModLoader.FABRIC, overrides: Int = 0) =
        Pack("Prova", "1", null, minecraft, loader, "0.15.7", files, OverrideCounts(overrides, 0, 0), emptyList())

    @Test fun allowedFilesAreToBeDownloadedAndTotalsAreSummed() {
        val plan = ModpackPlanner.plan(pack(listOf(file("mods/a.jar"), file("resourcepacks/b.zip"))), policy)
        assertTrue(plan.canInstall && plan.complete)
        assertEquals(2, plan.toDownload.size)
        assertEquals(bytes.size * 2L, plan.downloadBytes)
        assertTrue(plan.hostsToApprove.isEmpty())
        assertEquals(listOf(cdn), plan.toDownload[0].urls)
    }

    @Test fun unsafeAddressesAreBlockedWithTheReason() {
        val plan = ModpackPlanner.plan(
            pack(
                listOf(
                    file("mods/http.jar", listOf("http://cdn.modrinth.com/a.jar")),
                    file("mods/ip.jar", listOf("https://93.184.216.34/a.jar")),
                    file("mods/user.jar", listOf("https://user:pw@cdn.modrinth.com/a.jar")),
                    file("mods/port.jar", listOf("https://cdn.modrinth.com:8443/a.jar")),
                    file("mods/local.jar", listOf("https://localhost/a.jar"))
                )
            ),
            policy
        )
        assertEquals(5, plan.blocked.size)
        assertTrue(plan.toDownload.isEmpty())
        assertFalse("con file bloccati il modpack non è completo", plan.complete)
        assertTrue(plan.canInstall)
        assertTrue(plan.blocked.all { !it.reason.isNullOrBlank() && it.hostToApprove == null })
    }

    @Test fun anUnknownHostIsBlockedUntilTheUserApprovesIt() {
        val approved = HashSet<String>()
        val checker = UrlPolicy(userHosts = { approved })
        val f = file("mods/gl.jar", listOf("https://gitlab.com/x/y/-/raw/main/a.jar"))
        val before = ModpackPlanner.plan(pack(listOf(f)), checker)
        assertEquals(FileAction.BLOCKED, before.files.single().action)
        assertEquals(listOf("gitlab.com"), before.hostsToApprove)
        approved.add("gitlab.com")
        val after = ModpackPlanner.plan(pack(listOf(f)), checker)
        assertEquals(FileAction.DOWNLOAD, after.files.single().action)
        assertTrue(after.complete)
    }

    @Test fun theFirstAllowedAddressWinsAndTheOthersAreKeptAsFallback() {
        val f = file("mods/a.jar", listOf("http://insicuro.example/a.jar", cdn, "https://github.com/x/a.jar"))
        val planned = ModpackPlanner.plan(pack(listOf(f)), policy).files.single()
        assertEquals(FileAction.DOWNLOAD, planned.action)
        assertEquals(listOf(cdn, "https://github.com/x/a.jar"), planned.urls)
    }

    @Test fun clientUnsupportedFilesAreSkippedAndListed() {
        val plan = ModpackPlanner.plan(pack(listOf(file("mods/server-only.jar", client = Side.UNSUPPORTED), file("mods/a.jar"))), policy)
        assertEquals(listOf("mods/server-only.jar"), plan.skipped.map { it.file.path })
        assertEquals(1, plan.toDownload.size)
        assertTrue(plan.complete)
    }

    @Test fun anIncompleteIndexMakesThePlanIncompleteButNotUnusable() {
        val p = Pack("P", null, null, "1.20.1", ModLoader.FABRIC, null, listOf(file("mods/a.jar")), OverrideCounts(0, 0, 0), listOf("mods/x.jar: impronta non valida"))
        val plan = ModpackPlanner.plan(p, policy)
        assertTrue(plan.canInstall)
        assertFalse(plan.complete)
    }

    @Test fun withoutGameVersionLoaderOrContentThePackCannotBeInstalled() {
        assertFalse(ModpackPlanner.plan(pack(listOf(file("mods/a.jar")), minecraft = null), policy).canInstall)
        assertFalse(ModpackPlanner.plan(pack(listOf(file("mods/a.jar")), loader = null), policy).canInstall)
        val empty = ModpackPlanner.plan(pack(emptyList()), policy)
        assertFalse(empty.canInstall)
        assertTrue(empty.blockers.single().contains("vuoto"))
        assertTrue("con sole impostazioni non è vuoto", ModpackPlanner.plan(pack(emptyList(), overrides = 3), policy).canInstall)
    }

    @Test fun filesAlreadyInTheProfileAreRecognisedByHashNotByNameOnly() {
        val good = file("mods/good.jar")
        val wrong = file("mods/wrong.jar")
        val otherSize = file("mods/size.jar")
        File(dir, "mods").mkdirs()
        File(dir, "mods/good.jar").writeBytes(bytes)
        File(dir, "mods/wrong.jar").writeBytes(ByteArray(bytes.size) { 1 })      // stessa dimensione, contenuto diverso
        File(dir, "mods/size.jar").writeBytes(bytes + byteArrayOf(0))            // dimensione diversa
        val plan = ModpackPlanner.plan(pack(listOf(good, wrong, otherSize, file("mods/missing.jar"))), policy, profileDir = dir)
        assertEquals(listOf("mods/good.jar"), plan.present.map { it.file.path })
        assertEquals(listOf("mods/wrong.jar", "mods/size.jar", "mods/missing.jar"), plan.toDownload.map { it.file.path })
    }

    @Test fun sha1OnlyFilesAreCheckedWithSha1() {
        val f = PackFile("mods/a.jar", Fx.sha1Hex(bytes), null, -1L, listOf(cdn), Side.REQUIRED, Side.REQUIRED)
        File(dir, "mods").mkdirs()
        File(dir, "mods/a.jar").writeBytes(bytes)
        assertEquals(1, ModpackPlanner.plan(pack(listOf(f)), policy, profileDir = dir).present.size)
        File(dir, "mods/a.jar").writeBytes(bytes + byteArrayOf(1))
        assertEquals(1, ModpackPlanner.plan(pack(listOf(f)), policy, profileDir = dir).toDownload.size)
        assertNull(null)
    }

    @Test fun aFileTheUserDisabledCountsAsPresent() {
        File(dir, "mods").mkdirs()
        File(dir, "mods/a.jar.disabled").writeBytes(bytes)
        File(dir, "mods/b.jar.disabled").writeBytes(ByteArray(bytes.size) { 3 })
        val plan = ModpackPlanner.plan(pack(listOf(file("mods/a.jar"), file("mods/b.jar"))), policy, profileDir = dir)
        assertEquals(listOf("mods/a.jar"), plan.present.map { it.file.path })
        assertEquals(listOf("mods/b.jar"), plan.toDownload.map { it.file.path })
    }
}
