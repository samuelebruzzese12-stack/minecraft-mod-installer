package com.mmi.modinstaller.mods

import com.mmi.modinstaller.core.ArchiveUnsafeException
import com.mmi.modinstaller.core.LimitExceededException
import com.mmi.modinstaller.core.SafetyLimits
import com.mmi.modinstaller.jar.ContentKind
import com.mmi.modinstaller.jar.DependencyKind
import com.mmi.modinstaller.jar.LoaderCertainty
import com.mmi.modinstaller.jar.ModLoader
import com.mmi.modinstaller.jar.PackInfo
import com.mmi.modinstaller.jar.RangeSyntax
import com.mmi.modinstaller.mods.Fx.dep
import com.mmi.modinstaller.mods.Fx.mod
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class SmallThingsTest {

    private val dirs = ArrayList<File>()

    private fun tmp(): File = Fx.tempDir().also { dirs.add(it) }

    @After fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    // ------------------------------------------------------------------ Java richiesto da Minecraft

    @Test fun javaVersionRequiredByMinecraft() {
        val expected = mapOf(
            "1.7.10" to 8, "1.12.2" to 8, "1.16.5" to 8, "1.17" to 16, "1.17.1" to 16, "1.18" to 17, "1.18.2" to 17, "1.19.4" to 17,
            "1.20" to 17, "1.20.1" to 17, "1.20.4" to 17, "1.20.5" to 21, "1.20.6" to 21, "1.21" to 21, "1.21.1" to 21
        )
        for ((mc, java) in expected) assertEquals(mc, java, ProfileJava.forMinecraft(mc))
        for (unknown in listOf("1.22", "1.30.1", "24w14a", "", "boh", "2.0")) assertNull(unknown, ProfileJava.forMinecraft(unknown))
        assertNull(ProfileJava.forMinecraft(null))
    }

    @Test fun profileJavaCanBeOverridden() {
        assertEquals(17, Fx.profile(minecraft = "1.20.1").effectiveJava)
        assertEquals(21, Fx.profile(minecraft = "1.20.1", java = 21).effectiveJava)
        assertNull(Fx.profile(minecraft = "24w14a").effectiveJava)
        assertTrue(Fx.profile().title.contains("Fabric") && Fx.profile().title.contains("1.20.1"))
    }

    // ------------------------------------------------------------------ proposta di profilo

    @Test fun profileSuggestionFromFabricMod() {
        val r = Fx.report("m.jar", mods = listOf(mod("m", deps = listOf(dep("minecraft", "~1.20.1")))))
        val s = ProfileSuggester.fromReport(r)
        assertEquals("1.20.1", s.minecraft)
        assertEquals(ModLoader.FABRIC, s.loader)
    }

    @Test fun profileSuggestionFromForgeRangeAndLoader() {
        val r = Fx.report(
            "m.jar", mods = listOf(mod("m", loader = ModLoader.FORGE, deps = listOf(dep("minecraft", "[1.19.2,1.20)", syntax = RangeSyntax.MAVEN))))
        )
        val s = ProfileSuggester.fromReport(r)
        assertEquals("1.19.2", s.minecraft)
        assertEquals(ModLoader.FORGE, s.loader)
    }

    @Test fun profileSuggestionPrefersACertainLoader() {
        val guessed = mod("g", loader = ModLoader.FORGE, certainty = LoaderCertainty.GUESSED)
        val sure = mod("s", loader = ModLoader.FABRIC, deps = listOf(dep("minecraft", "~1.20.4")))
        val s = ProfileSuggester.fromReport(Fx.report("m.jar", mods = listOf(guessed, sure)))
        assertEquals(ModLoader.FABRIC, s.loader)
        assertEquals("1.20.4", s.minecraft)
    }

    @Test fun profileSuggestionFromLegacyHintAndPacks() {
        val legacy = mod("m", loader = ModLoader.FORGE, certainty = LoaderCertainty.GUESSED, descriptor = "mcmod.info", mcHint = "1.12.2")
        assertEquals("1.12.2", ProfileSuggester.fromReport(Fx.report("m.jar", mods = listOf(legacy))).minecraft)
        val pack = PackInfo(null, 15, null, null, true, false, false, "1.20 - 1.20.1", null)
        val s = ProfileSuggester.fromReport(Fx.report("p.zip", kind = ContentKind.RESOURCE_PACK, pack = pack))
        assertEquals("1.20", s.minecraft)
        assertNull(s.loader)
        val none = ProfileSuggester.fromReport(Fx.report("x.jar"))
        assertNull(none.minecraft)
        assertNull(none.loader)
        val placeholder = mod("p", deps = listOf(dep("minecraft", "\${mcversion}")))
        assertNull(ProfileSuggester.fromReport(Fx.report("p.jar", mods = listOf(placeholder))).minecraft)
    }

    // ------------------------------------------------------------------ dipendenze

    @Test fun compareVersionsOnlyWhenBothAreComparable() {
        assertTrue(DependencyEvaluator.compareVersions("1.2.0", "1.1.9")!! > 0)
        assertTrue(DependencyEvaluator.compareVersions("1.0.0-beta.1", "1.0.0")!! < 0)
        assertEquals(0, DependencyEvaluator.compareVersions("1.0.0+build5", "1.0.0")!!)
        assertNull(DependencyEvaluator.compareVersions(null, "1.0"))
        assertNull(DependencyEvaluator.compareVersions("24w14a", "1.0"))
        assertNull(DependencyEvaluator.compareVersions("1.0", "boh"))
    }

    @Test fun evaluatorPrefersTheSatisfyingProviderOverAWrongOne() {
        val old = Fx.item("old", listOf(mod("lib", "1.0.0")))
        val good = Fx.item("good", listOf(mod("lib", "2.0.0")))
        val index = InstalledIndex(listOf(old, good))
        val r = DependencyEvaluator.evaluate(dep("lib", ">=2.0.0"), index, emptyMap())
        assertEquals(DepState.SATISFIED, r.state)
        assertEquals("good", r.provider!!.id)
        assertEquals(DepState.WRONG_VERSION, DependencyEvaluator.evaluate(dep("lib", ">=3.0.0"), index, emptyMap()).state)
        assertEquals(DepState.MISSING, DependencyEvaluator.evaluate(dep("nope"), index, emptyMap()).state)
        // il file che sta per essere sostituito non conta
        assertEquals(DepState.WRONG_VERSION, DependencyEvaluator.evaluate(dep("lib", ">=2.0.0"), InstalledIndex(listOf(old, good)), emptyMap(), skipItemId = "good").state)
        // quello che il file offre da solo basta
        assertEquals(DepState.SATISFIED, DependencyEvaluator.evaluate(dep("nope"), index, mapOf("nope" to "1.0")).state)
        assertEquals(DepState.WRONG_VERSION, DependencyEvaluator.evaluate(dep("nope", ">=2"), InstalledIndex(emptyList()), mapOf("nope" to "1.0")).state)
    }

    @Test fun platformDependenciesAreAnsweredByTheProfile() {
        val p = Fx.profile(ModLoader.FORGE, "1.20.1", "47.2.0", 17)
        fun c(id: String, range: String, syntax: RangeSyntax = RangeSyntax.MAVEN) = DependencyEvaluator.platformCompat(dep(id, range, syntax = syntax), p)
        assertEquals(com.mmi.modinstaller.jar.Compat.YES, c("minecraft", "[1.20.1,1.21)"))
        assertEquals(com.mmi.modinstaller.jar.Compat.NO, c("minecraft", "[1.19,1.20)"))
        assertEquals(com.mmi.modinstaller.jar.Compat.YES, c("forge", "[47,)"))
        assertEquals(com.mmi.modinstaller.jar.Compat.NO, c("forge", "[48,)"))
        assertEquals(com.mmi.modinstaller.jar.Compat.YES, c("java", ">=17", RangeSyntax.SEMVER))
        assertEquals(com.mmi.modinstaller.jar.Compat.UNKNOWN, c("neoforge", "[20,)"))
        assertTrue(DependencyEvaluator.isLoaderId("fabricloader", ModLoader.FABRIC))
        assertFalse(DependencyEvaluator.isLoaderId("fabricloader", ModLoader.QUILT))
    }

    // ------------------------------------------------------------------ rimpacchettamento

    private fun zipWith(file: File, vararg entries: Pair<String, String>): File = Fx.zip(file, linkedMapOf(*entries))

    @Test fun repackagerStripsTheWrapperAndSkipsJunk() {
        val d = tmp()
        val s = zipWith(
            File(d, "in.zip"),
            "W/pack.mcmeta" to "{}", "W/assets/a.txt" to "A", "W/sub/b.txt" to "B", "__MACOSX/W/._x" to "j", "W/.DS_Store" to "j", "W/Thumbs.db" to "j"
        )
        val out = File(d, "out.zip")
        val n = PackRepackager.repackage(s, "W", out)
        assertEquals(3, n)
        ZipFile(out).use { z ->
            assertEquals(listOf("assets/a.txt", "pack.mcmeta", "sub/b.txt"), z.entries().toList().map { it.name }.sorted())
            assertEquals("A", z.getInputStream(z.getEntry("assets/a.txt")).readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test fun repackagerOutputIsReproducible() {
        val d = tmp()
        val s = zipWith(File(d, "in.zip"), "W/pack.mcmeta" to "{}", "W/assets/a.txt" to "A")
        PackRepackager.repackage(s, "W", File(d, "o1.zip"))
        Thread.sleep(1100)
        PackRepackager.repackage(s, "W", File(d, "o2.zip"))
        assertEquals(Fx.sha256(File(d, "o1.zip").readBytes()), Fx.sha256(File(d, "o2.zip").readBytes()))
    }

    @Test fun repackagerRefusesUnsafePathsAndRemovesTheTarget() {
        val d = tmp()
        val s = File(d, "evil.zip")
        ZipOutputStream(FileOutputStream(s)).use { z ->
            z.putNextEntry(ZipEntry("W/pack.mcmeta")); z.write("{}".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("W/../../evil.txt")); z.write("x".toByteArray()); z.closeEntry()
        }
        val out = File(d, "out.zip")
        try {
            PackRepackager.repackage(s, "W", out)
            fail("doveva rifiutare i percorsi pericolosi")
        } catch (e: ArchiveUnsafeException) {
            // atteso
        }
        assertFalse(out.exists())
    }

    @Test fun repackagerEnforcesSizeLimits() {
        val d = tmp()
        val s = zipWith(File(d, "in.zip"), "W/pack.mcmeta" to "{}", "W/big.bin" to "x".repeat(5000))
        val out = File(d, "out.zip")
        try {
            PackRepackager.repackage(s, "W", out, SafetyLimits(maxEntryBytes = 1000))
            fail("doveva fermarsi")
        } catch (e: LimitExceededException) {
            // atteso
        }
        assertFalse(out.exists())
        try {
            PackRepackager.repackage(s, "W", out, SafetyLimits(maxTotalUncompressedBytes = 1000))
            fail("doveva fermarsi")
        } catch (e: com.mmi.modinstaller.core.ArchiveException) {
            // atteso (limite sul totale)
        }
        assertFalse(out.exists())
    }

    @Test fun repackagerCanBeCancelled() {
        val d = tmp()
        val s = zipWith(File(d, "in.zip"), "W/a" to "1", "W/b" to "2")
        val out = File(d, "out.zip")
        try {
            PackRepackager.repackage(s, "W", out, SafetyLimits(), cancel = { throw java.util.concurrent.CancellationException() })
            fail("doveva interrompersi")
        } catch (e: java.util.concurrent.CancellationException) {
            // atteso
        }
        assertFalse(out.exists())
    }

    @Test fun repackagerKeepsWindowsStyleSeparatorsOut() {
        val d = tmp()
        val s = File(d, "in.zip")
        ZipOutputStream(FileOutputStream(s)).use { z ->
            z.putNextEntry(ZipEntry("W\\pack.mcmeta")); z.write("{}".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("W\\assets\\a.txt")); z.write("A".toByteArray()); z.closeEntry()
        }
        val out = File(d, "out.zip")
        PackRepackager.repackage(s, "W", out)
        ZipFile(out).use { z -> assertEquals(listOf("assets/a.txt", "pack.mcmeta"), z.entries().toList().map { it.name }.sorted()) }
    }

    // ------------------------------------------------------------------ copie e spostamenti

    @Test fun moveRefusesToOverwriteAndKeepsTheSource() {
        val d = tmp()
        val a = Fx.file(d, "a.txt", "A".toByteArray())
        val b = Fx.file(d, "b.txt", "B".toByteArray())
        try {
            Digests.move(a, b)
            fail("doveva rifiutare")
        } catch (e: java.io.IOException) {
            // atteso
        }
        assertEquals("A", a.readText())
        assertEquals("B", b.readText())
        val c = File(d, "sub/c.txt")
        Digests.move(a, c)
        assertFalse(a.exists())
        assertEquals("A", c.readText())
    }

    @Test fun digestsMatchKnownValues() {
        val d = tmp()
        val f = Fx.file(d, "abc.txt", "abc".toByteArray())
        val h = Digests.of(f)
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", h.sha1)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h.sha256)
        assertEquals(3L, h.size)
        val copy = File(d, "copy.txt")
        val c = f.inputStream().use { Digests.copy(it, copy, 3, {}, null) }
        assertEquals(h.sha256, c.sha256)
        assertNotNull(copy.readText())
    }

    @Test fun deleteRecursivelyDoesNotFollowSymlinks() {
        val d = tmp()
        val target = File(d, "target").also { it.mkdirs() }
        File(target, "keep.txt").writeText("keep")
        val victim = File(d, "victim").also { it.mkdirs() }
        File(victim, "x.txt").writeText("x")
        try {
            java.nio.file.Files.createSymbolicLink(File(victim, "link").toPath(), target.toPath())
        } catch (e: Exception) {
            return // il sistema non permette i link simbolici: niente da provare
        }
        Digests.deleteRecursively(victim)
        assertFalse(victim.exists())
        assertEquals("keep", File(target, "keep.txt").readText())
        assertTrue(DependencyKind.values().isNotEmpty())
    }
}
