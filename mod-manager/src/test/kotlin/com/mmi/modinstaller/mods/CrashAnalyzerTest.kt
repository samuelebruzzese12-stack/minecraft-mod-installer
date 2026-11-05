package com.mmi.modinstaller.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testi costruiti con i messaggi che Fabric Loader, Forge e la JVM scrivono davvero (le frasi sono quelle dei loro sorgenti). */
class CrashAnalyzerTest {

    private val fabricMissing = """
        [12:01:02] [main/INFO]: Loading Minecraft 1.20.1 with Fabric Loader 0.15.11
        [12:01:03] [main/ERROR]: Incompatible mods found!
        net.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible with the game or each other!
        A potential solution has been determined, this may resolve your problem:
        	 - Install fabric-api, any version.
        	 - Replace mod 'Sodium' (sodium) 0.4.10 with any version between 0.5.0 (inclusive) and 0.6.0 (exclusive) of mod 'Sodium' (sodium).
        More details:
        	 - Mod 'Iris' (iris) 1.6.4 requires any version of fabric-api, which is missing!
        	 - Mod 'Xaero's Minimap' (xaerominimap) 23.9.7 requires version 1.20.1 of 'Minecraft' (minecraft), but only the wrong version is present: 1.20.4!
        	 - Mod 'Iris' (iris) 1.6.4 is incompatible with any version before 0.5.0 of mod 'Sodium' (sodium), yet a conflicting version is present: 0.4.10!
        	at net.fabricmc.loader.impl.FormattedException.ofLocalized(FormattedException.java:51)
    """.trimIndent()

    @Test fun fabricDependencyMessages() {
        val a = CrashAnalyzer.analyze(fabricMissing)
        assertFalse(a.isCrashReport)
        assertEquals("Fabric", a.loader)
        val missing = a.findings.single { it.cause == CrashCause.MISSING_DEPENDENCY }
        assertEquals("«Iris» (iris) ha bisogno di «fabric-api» (any version), che non c'è.", missing.text)
        assertEquals(listOf("iris", "fabric-api"), missing.mods)
        val wrong = a.findings.single { it.cause == CrashCause.WRONG_VERSION }
        assertEquals("«Xaero's Minimap» (xaerominimap) vuole «Minecraft» version 1.20.1, ma c'è la versione 1.20.4.", wrong.text)
        assertTrue(wrong.advice.contains("versione di Minecraft"))
        val inc = a.findings.single { it.cause == CrashCause.INCOMPATIBLE }
        assertEquals(listOf("iris", "sodium"), inc.mods)
        assertTrue(a.recognized)
        assertEquals("net.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible with the game or each other!", a.exception)
    }

    @Test fun fabricWrongVersionsPlural() {
        val a = CrashAnalyzer.analyze("\t - Mod 'Lithium' (lithium) 0.11.2 requires version 0.15.0 or later of mod 'Fabric Loader' (fabricloader), but only the wrong versions are present: 0.14.21/0.14.22!")
        val f = a.findings.single()
        assertEquals(CrashCause.WRONG_VERSION, f.cause)
        assertTrue(f.text, f.text.contains("0.14.21/0.14.22"))
        assertEquals(listOf("lithium", "fabricloader"), f.mods)
    }

    @Test fun fabricDuplicate() {
        val a = CrashAnalyzer.analyze("net.fabricmc.loader.impl.discovery.ModResolutionException: duplicate mod sodium")
        val f = a.findings.single()
        assertEquals(CrashCause.DUPLICATE, f.cause)
        assertEquals(listOf("sodium"), f.mods)
    }

    private val crashReport = """
        ---- Minecraft Crash Report ----
        // Uh... Did I do that?

        Time: 2026-11-05 12:00:00
        Description: Initializing game

        java.lang.OutOfMemoryError: Java heap space
        	at java.base/java.util.Arrays.copyOf(Arrays.java:3537)

        A detailed walkthrough of the error, its code path and all known details is as follows:
        ---------------------------------------------------------------------------------------

        -- System Details --
        Details:
        	Minecraft Version: 1.20.1
        	Java Version: 17.0.8, Eclipse Adoptium
        	Suspected Mods: Iris (iris), Sodium (sodium)
        	Fabric Mods:
        		iris: Iris 1.6.4
    """.trimIndent()

    @Test fun crashReportHeaderAndMemory() {
        val a = CrashAnalyzer.analyze(crashReport)
        assertTrue(a.isCrashReport)
        assertEquals("1.20.1", a.minecraft)
        assertEquals("17.0.8", a.java)
        assertEquals("Fabric", a.loader)
        assertEquals("Initializing game", a.description)
        assertEquals("java.lang.OutOfMemoryError: Java heap space", a.exception)
        assertEquals(listOf("Iris (iris)", "Sodium (sodium)"), a.suspectedMods)
        assertEquals(CrashCause.OUT_OF_MEMORY, a.findings.single().cause)
    }

    @Test fun javaTooOld() {
        val a = CrashAnalyzer.analyze(
            "java.lang.UnsupportedClassVersionError: net/minecraft/client/main/Main has been compiled by a more recent version of the Java Runtime " +
                "(class file version 65.0), this version of the Java Runtime only recognizes class file versions up to 61.0"
        )
        val f = a.findings.single()
        assertEquals(CrashCause.WRONG_JAVA, f.cause)
        assertEquals("Una mod (o il gioco) è fatta per Java 21, ma il programma di avvio usa Java 17.", f.text)
    }

    @Test fun forgeMissingAndWrong() {
        val text = """
            Missing or unsupported mandatory dependencies:
            	Mod ID: 'geckolib', Requested by: 'mymod', Expected range: '[4.0,)', Actual version: '[MISSING]'
            	Mod ID: 'minecraft', Requested by: 'othermod', Expected range: '[1.19.2,1.19.3)', Actual version: '1.20.1'
            -- MOD mymod --
            	Mod File: /mods/mymod.jar
            	Failure message: Mod mymod requires curios 5.0 or above
            		Currently, curios is not installed
            	Mod Version: 1.0
            	Failure message: Mod othermod only supports forge 47.1 or above
            		Currently, forge is 47.0.3
            FML: 47.0
        """.trimIndent()
        val a = CrashAnalyzer.analyze(text)
        assertEquals("Forge", a.loader)
        val missing = a.findings.filter { it.cause == CrashCause.MISSING_DEPENDENCY }
        assertEquals(setOf(listOf("mymod", "geckolib"), listOf("mymod", "curios")), missing.map { it.mods }.toSet())
        val wrong = a.findings.filter { it.cause == CrashCause.WRONG_VERSION }
        assertEquals(2, wrong.size)
        assertTrue(wrong.any { it.text.contains("ma c'è la versione 1.20.1") && it.advice.contains("versione giusta di minecraft") })
        assertTrue(wrong.any { it.text == "«othermod» vuole «forge» 47.1 or above, ma c'è: 47.0.3." })
    }

    @Test fun mixinFailureNamesTheMod() {
        val a = CrashAnalyzer.analyze(
            "org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError: An unexpected critical error was encountered\n" +
                "Caused by: org.spongepowered.asm.mixin.throwables.MixinApplyError: Mixin [coolmod.mixins.json:MixinWorld] from mod coolmod failed injection check"
        )
        val f = a.findings.single()
        assertEquals(CrashCause.MIXIN, f.cause)
        assertEquals(listOf("coolmod"), f.mods)
    }

    @Test fun graphicsDriver() {
        val a = CrashAnalyzer.analyze("[main/ERROR]: GLFW error 65542: WGL: The driver does not appear to support OpenGL")
        assertEquals(CrashCause.GRAPHICS, a.findings.single().cause)
    }

    @Test fun unknownKeepsTheException() {
        val a = CrashAnalyzer.analyze("---- Minecraft Crash Report ----\nDescription: Ticking entity\n\njava.lang.NullPointerException: Cannot invoke \"x\" because \"y\" is null\n")
        assertFalse(a.recognized)
        val f = a.findings.single()
        assertEquals(CrashCause.UNKNOWN, f.cause)
        assertTrue(f.text, f.text.contains("java.lang.NullPointerException"))
        assertNull(a.loader)
    }

    @Test fun plainTextIsNotRecognized() {
        val a = CrashAnalyzer.analyze("ciao, questo non è un log")
        assertFalse(a.recognized)
        assertNull(a.exception)
        assertTrue(a.suspectedMods.isEmpty())
    }

    @Test fun hugeTextKeepsHeadAndTail() {
        val big = "Description: X\n" + "a".repeat(CrashAnalyzer.MAX_CHARS * 2) + "\njava.lang.OutOfMemoryError: Java heap space\n"
        val a = CrashAnalyzer.analyze(big)
        assertEquals("X", a.description)
        assertEquals(CrashCause.OUT_OF_MEMORY, a.findings.single().cause)
    }

    @Test fun matcherFindsModsInProfiles() {
        val a = CrashAnalyzer.analyze(fabricMissing + "\n\tSuspected Mods: Cool Mod (coolmod), Unknown")
        assertEquals(listOf("iris", "fabric-api", "xaerominimap", "sodium", "coolmod"), CrashMatcher.ids(a))
        val p1 = Fx.profile(id = "p1")
        val p2 = Fx.profile(id = "p2")
        val iris = Fx.item("i1", mods = listOf(Fx.mod("iris")))
        val sodium = Fx.item("s1", mods = listOf(Fx.mod("sodium")), enabled = false)
        val other = Fx.item("o1", mods = listOf(Fx.mod("lithium")))
        val bundle = Fx.item("b1", mods = listOf(Fx.mod("bundle", provides = listOf("CoolMod"))))
        val found = CrashMatcher.find(a, listOf(p1 to listOf(iris, other), p2 to listOf(sodium, bundle)))
        assertEquals(listOf("p1:iris:i1", "p2:sodium:s1", "p2:coolmod:b1"), found.map { it.profile.id + ":" + it.modId + ":" + it.item.id })
    }

    @Test fun readerKeepsHeadAndTailAndUnzips() {
        val body = "Description: Testa\n" + "x".repeat(3_000_000) + "\njava.lang.OutOfMemoryError: Java heap space\n"
        val plain = CrashText.read(body.toByteArray().inputStream())!!
        assertFalse(plain.compressed)
        assertEquals(body.length.toLong(), plain.bytesRead)
        assertTrue(plain.text.length <= CrashAnalyzer.MAX_CHARS + 1)
        assertTrue(plain.text.startsWith("Description: Testa"))
        assertTrue(plain.text.endsWith("java.lang.OutOfMemoryError: Java heap space\n"))

        val gz = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(gz).use { it.write("Mod 'A' (a) 1.0 requires any version of b, which is missing!".toByteArray()) }
        val unzipped = CrashText.read(gz.toByteArray().inputStream())!!
        assertTrue(unzipped.compressed)
        assertEquals(CrashCause.MISSING_DEPENDENCY, CrashAnalyzer.analyze(unzipped.text).findings.single().cause)

        val small = CrashText.read("breve".toByteArray().inputStream())!!
        assertEquals("breve", small.text)
    }

    @Test fun readerRejectsBinary() {
        assertNull(CrashText.read(ByteArray(4096) { if (it % 3 == 0) 0 else 65 }.inputStream()))
        assertEquals("", CrashText.read(ByteArray(0).inputStream())!!.text)
    }
}
