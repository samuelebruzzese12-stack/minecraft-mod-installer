package com.mmi.modinstaller.mods

import com.mmi.modinstaller.core.Finding
import com.mmi.modinstaller.core.FindingCode
import com.mmi.modinstaller.core.Severity
import com.mmi.modinstaller.jar.ContentKind
import com.mmi.modinstaller.jar.DependencyKind
import com.mmi.modinstaller.jar.LoaderCertainty
import com.mmi.modinstaller.jar.ModEnvironment
import com.mmi.modinstaller.jar.ModLoader
import com.mmi.modinstaller.jar.NestedJar
import com.mmi.modinstaller.jar.RangeSyntax
import com.mmi.modinstaller.mods.Fx.dep
import com.mmi.modinstaller.mods.Fx.fabricDeps
import com.mmi.modinstaller.mods.Fx.mod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallPlannerTest {

    private fun fabricMod(id: String = "sodium", version: String = "0.5.3", extra: List<com.mmi.modinstaller.jar.ModDependency> = emptyList()) =
        Fx.report("$id-$version.jar", mods = listOf(mod(id, version, deps = fabricDeps(extra))))

    private fun codes(p: InstallPlan) = p.issues.map { it.code }

    // ------------------------------------------------------------------ caso normale

    @Test fun fabricModWithMatchingRequirementsIsReady() {
        val p = Fx.plan(fabricMod())
        assertEquals(PlanDecision.READY, p.decision)
        assertEquals(ItemCategory.MOD, p.category)
        assertEquals("sodium-0.5.3.jar", p.targetName)
        assertTrue(p.canInstall)
        assertFalse(p.needsConfirmation)
        assertTrue(p.missing.isEmpty())
        assertNull(p.replaces)
    }

    @Test fun reportErrorsBlockEvenIfKindLooksInstallable() {
        val r = Fx.report(
            "x.jar", mods = listOf(mod("x")),
            findings = listOf(Finding(Severity.ERROR, FindingCode.CORRUPT_ARCHIVE, "Archivio danneggiato"))
        )
        val p = Fx.plan(r)
        assertEquals(PlanDecision.BLOCKED, p.decision)
        assertEquals(PlanCode.REPORT_ERROR, p.blockingReasons.first().code)
        assertEquals("Archivio danneggiato", p.blockingReasons.first().message)
    }

    @Test fun unknownCorruptFileShowsTheRealErrorNotAGenericOne() {
        val r = Fx.report(
            "x.jar", kind = ContentKind.UNKNOWN,
            findings = listOf(Finding(Severity.ERROR, FindingCode.NOT_AN_ARCHIVE, "Non è un archivio"))
        )
        val p = Fx.plan(r)
        assertEquals(PlanDecision.BLOCKED, p.decision)
        assertEquals(1, p.issues.size)
        assertEquals(PlanCode.REPORT_ERROR, p.issues[0].code)
    }

    @Test fun notInstallableKindsAreBlockedWithAnExplanation() {
        for (kind in listOf(
            ContentKind.MODPACK_MODRINTH, ContentKind.MODPACK_CURSEFORGE, ContentKind.MOD_BUNDLE, ContentKind.BEDROCK_PACK,
            ContentKind.JAVA_WORLD, ContentKind.SERVER_PLUGIN, ContentKind.UNSUPPORTED_LOADER, ContentKind.UNKNOWN
        )) {
            val p = Fx.plan(Fx.report("a.zip", kind = kind))
            assertEquals(kind.name, PlanDecision.BLOCKED, p.decision)
            assertEquals(kind.name, PlanCode.NOT_INSTALLABLE, p.blockingReasons.first().code)
            assertNull(p.category)
        }
        val modpack = Fx.plan(Fx.report("a.mrpack", kind = ContentKind.MODPACK_MODRINTH))
        assertTrue(modpack.blockingReasons.first().message.contains("MODPACK MANAGER"))
    }

    // ------------------------------------------------------------------ caricatore

    @Test fun forgeModInFabricProfileIsBlocked() {
        val r = Fx.report("m.jar", mods = listOf(mod("m", loader = ModLoader.FORGE)))
        val p = Fx.plan(r)
        assertEquals(PlanDecision.BLOCKED, p.decision)
        assertEquals(PlanCode.LOADER_MISMATCH, p.blockingReasons.first().code)
        assertTrue(p.blockingReasons.first().message.contains("Forge"))
    }

    @Test fun neoforgeAndForgeAreNotInterchangeableWhenDeclared() {
        val r = Fx.report("m.jar", mods = listOf(mod("m", loader = ModLoader.NEOFORGE, deps = listOf(dep("minecraft", "[1.20.1]", syntax = RangeSyntax.MAVEN)))))
        assertEquals(PlanDecision.BLOCKED, Fx.plan(r, Fx.profile(ModLoader.FORGE)).decision)
        assertEquals(PlanDecision.READY, Fx.plan(r, Fx.profile(ModLoader.NEOFORGE)).decision)
    }

    @Test fun fabricModOnQuiltIsAllowedWithANote() {
        val p = Fx.plan(fabricMod(), Fx.profile(ModLoader.QUILT))
        assertEquals(PlanDecision.READY, p.decision)
        assertTrue(codes(p).contains(PlanCode.QUILT_FABRIC))
    }

    @Test fun quiltModInFabricProfileIsBlocked() {
        val r = Fx.report("m.jar", mods = listOf(mod("m", loader = ModLoader.QUILT)))
        assertEquals(PlanDecision.BLOCKED, Fx.plan(r, Fx.profile(ModLoader.FABRIC)).decision)
    }

    @Test fun modsTomlWithoutLoaderIsAskedConfirmationOnNeoForgeButNotOnForge() {
        val guessed = mod(
            "m", loader = ModLoader.FORGE, certainty = LoaderCertainty.GUESSED,
            deps = listOf(dep("minecraft", "[1.20.1]", syntax = RangeSyntax.MAVEN))
        )
        val r = Fx.report("m.jar", mods = listOf(guessed))
        val onForge = Fx.plan(r, Fx.profile(ModLoader.FORGE))
        assertEquals(PlanDecision.READY, onForge.decision)
        assertTrue(codes(onForge).contains(PlanCode.LOADER_GUESSED))
        val onNeo = Fx.plan(r, Fx.profile(ModLoader.NEOFORGE))
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, onNeo.decision)
        assertEquals(PlanCode.LOADER_GUESSED, onNeo.confirmReasons.first().code)
    }

    @Test fun legacyMcmodInfoIsNeverForNeoForge() {
        val legacy = mod("m", loader = ModLoader.FORGE, certainty = LoaderCertainty.GUESSED, descriptor = "mcmod.info", mcHint = "1.12.2")
        val r = Fx.report("m.jar", mods = listOf(legacy))
        assertEquals(PlanDecision.BLOCKED, Fx.plan(r, Fx.profile(ModLoader.NEOFORGE, "1.20.1")).decision)
        assertEquals(PlanDecision.READY, Fx.plan(r, Fx.profile(ModLoader.FORGE, "1.12.2")).decision)
    }

    @Test fun legacyMcVersionHintIsComparedOnMajorMinorOnly() {
        val legacy = mod("m", loader = ModLoader.FORGE, certainty = LoaderCertainty.GUESSED, descriptor = "mcmod.info", mcHint = "1.12")
        val r = Fx.report("m.jar", mods = listOf(legacy))
        assertEquals(PlanDecision.READY, Fx.plan(r, Fx.profile(ModLoader.FORGE, "1.12.2")).decision)
        val old = mod("o", loader = ModLoader.FORGE, certainty = LoaderCertainty.GUESSED, descriptor = "mcmod.info", mcHint = "1.7.10")
        val p = Fx.plan(Fx.report("o.jar", mods = listOf(old)), Fx.profile(ModLoader.FORGE, "1.12.2"))
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, p.decision)
        assertEquals(PlanCode.MC_MISMATCH, p.confirmReasons.first().code)
    }

    // ------------------------------------------------------------------ Minecraft, Java, versione del caricatore

    @Test fun differentMinecraftVersionNeedsConfirmation() {
        val r = Fx.report("m.jar", mods = listOf(mod("m", deps = listOf(dep("minecraft", "~1.19.4")))))
        val p = Fx.plan(r)
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, p.decision)
        assertEquals(PlanCode.MC_MISMATCH, p.confirmReasons.first().code)
        assertTrue(p.confirmReasons.first().detail!!.contains("1.19"))
    }

    @Test fun forgeMavenRangeForMinecraft() {
        fun forge(range: String) = Fx.report(
            "m.jar", mods = listOf(mod("m", loader = ModLoader.FORGE, deps = listOf(dep("minecraft", range, syntax = RangeSyntax.MAVEN))))
        )
        assertEquals(PlanDecision.READY, Fx.plan(forge("[1.20.1,1.21)"), Fx.profile(ModLoader.FORGE)).decision)
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, Fx.plan(forge("[1.19,1.20)"), Fx.profile(ModLoader.FORGE)).decision)
        assertEquals(PlanDecision.READY, Fx.plan(forge("[1.20.1]"), Fx.profile(ModLoader.FORGE)).decision)
    }

    @Test fun snapshotProfileOnlyGetsAnInfoNotAConfirmation() {
        val p = Fx.plan(fabricMod(), Fx.profile(minecraft = "24w14a"))
        assertEquals(PlanDecision.READY, p.decision)
        assertTrue(codes(p).contains(PlanCode.MC_UNKNOWN))
    }

    @Test fun missingMinecraftDeclarationIsOnlyAnInfo() {
        val p = Fx.plan(Fx.report("m.jar", mods = listOf(mod("m", deps = emptyList()))))
        assertEquals(PlanDecision.READY, p.decision)
        assertTrue(codes(p).contains(PlanCode.MC_UNKNOWN))
    }

    @Test fun unparsedMinecraftRequirementIsAnInfoWithTheReason() {
        val p = Fx.plan(Fx.report("m.jar", mods = listOf(mod("m", deps = listOf(dep("minecraft", "boh boh"))))))
        assertEquals(PlanDecision.READY, p.decision)
        val i = p.issues.first { it.code == PlanCode.MC_UNKNOWN }
        assertTrue(i.detail!!.contains("boh"))
    }

    @Test fun javaRequirementIsComparedWithTheProfileJava() {
        val r = Fx.report("m.jar", mods = listOf(mod("m", deps = listOf(dep("minecraft", "~1.20.1"), dep("java", ">=21")))))
        val p = Fx.plan(r) // 1.20.1 -> Java 17
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, p.decision)
        assertEquals(1, p.issues.count { it.code == PlanCode.JAVA_TOO_OLD })
        val withJava21 = Fx.plan(r, Fx.profile(java = 21))
        assertEquals(PlanDecision.READY, withJava21.decision)
    }

    @Test fun bytecodeJavaVersionIsCheckedOnlyOnceAlongWithTheDeclaredOne() {
        val r = Fx.report("m.jar", mods = listOf(mod("m", deps = listOf(dep("minecraft", "~1.20.1"), dep("java", ">=21")))), javaMajor = 21)
        val p = Fx.plan(r)
        assertEquals(1, p.issues.count { it.code == PlanCode.JAVA_TOO_OLD })
        val noDecl = Fx.plan(Fx.report("n.jar", mods = listOf(mod("n", deps = listOf(dep("minecraft", "~1.20.1")))), javaMajor = 21))
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, noDecl.decision)
        assertEquals(1, noDecl.issues.count { it.code == PlanCode.JAVA_TOO_OLD })
    }

    @Test fun loaderVersionRequirementUsesTheProfileLoaderVersionWhenKnown() {
        val r = Fx.report("m.jar", mods = listOf(mod("m", deps = listOf(dep("minecraft", "~1.20.1"), dep("fabricloader", ">=0.16.0")))))
        assertEquals(PlanDecision.READY, Fx.plan(r).decision) // versione del caricatore ignota: nessun giudizio
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, Fx.plan(r, Fx.profile(loaderVersion = "0.15.7")).decision)
        assertEquals(PlanDecision.READY, Fx.plan(r, Fx.profile(loaderVersion = "0.16.2")).decision)
    }

    @Test fun serverOnlyModNeedsConfirmation() {
        val r = Fx.report("m.jar", mods = listOf(mod("m", env = ModEnvironment.SERVER, deps = fabricDeps())))
        val p = Fx.plan(r)
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, p.decision)
        assertEquals(PlanCode.ENVIRONMENT, p.confirmReasons.first().code)
    }

    // ------------------------------------------------------------------ dipendenze

    @Test fun missingRequiredDependencyNeedsConfirmationAndIsListed() {
        val p = Fx.plan(fabricMod("iris", "1.7.0", listOf(dep("fabric-api", ">=0.90.0"))))
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, p.decision)
        assertEquals(PlanCode.DEP_MISSING, p.confirmReasons.first().code)
        assertEquals(1, p.missing.size)
        assertEquals("fabric-api", p.missing[0].id)
        assertEquals(">=0.90.0", p.missing[0].range)
        assertEquals("iris", p.missing[0].requiredBy)
    }

    @Test fun dependencyProvidedByAnInstalledFileIsSatisfied() {
        val api = Fx.item("api", listOf(mod("fabric-api", "0.92.0", provides = listOf("fabric"))))
        val p = Fx.plan(fabricMod("iris", "1.7.0", listOf(dep("fabric-api", ">=0.90.0"), dep("fabric", "*"))), installed = listOf(api))
        assertEquals(PlanDecision.READY, p.decision)
        assertTrue(p.missing.isEmpty())
    }

    @Test fun dependencyProvidedByANestedJarOfTheSameFileIsSatisfied() {
        val inner = NestedJar("META-INF/jars/lib.jar", 10, listOf(mod("lib", "2.1.0")))
        val r = Fx.report("m.jar", mods = listOf(mod("m", deps = fabricDeps(listOf(dep("lib", ">=2.0.0"))))), nested = listOf(inner))
        assertEquals(PlanDecision.READY, Fx.plan(r).decision)
    }

    @Test fun dependencyProvidedByANestedJarOfAnInstalledFileIsSatisfied() {
        val host = Fx.item("host", listOf(mod("host")), nested = listOf(mod("lib", "2.1.0")))
        val p = Fx.plan(fabricMod("m", "1.0.0", listOf(dep("lib", ">=2.0.0"))), installed = listOf(host))
        assertEquals(PlanDecision.READY, p.decision)
    }

    @Test fun dependencyWithTheWrongVersionNeedsConfirmationAndIsListedForUpdate() {
        val api = Fx.item("api", listOf(mod("fabric-api", "0.80.0")))
        val p = Fx.plan(fabricMod("iris", "1.7.0", listOf(dep("fabric-api", ">=0.90.0"))), installed = listOf(api))
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, p.decision)
        assertEquals(PlanCode.DEP_VERSION, p.confirmReasons.first().code)
        assertTrue(p.confirmReasons.first().detail!!.contains("0.80.0"))
        assertEquals("fabric-api", p.missing.single().id)
    }

    @Test fun dependencyThatIsInstalledButDisabledIsReportedAsDisabled() {
        val api = Fx.item("api", listOf(mod("fabric-api", "0.92.0")), enabled = false)
        val p = Fx.plan(fabricMod("iris", "1.7.0", listOf(dep("fabric-api"))), installed = listOf(api))
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, p.decision)
        assertEquals(PlanCode.DEP_DISABLED, p.confirmReasons.first().code)
        assertTrue(p.missing.isEmpty())
    }

    @Test fun dependencyWithUnknownVersionIsNotJudged() {
        val api = Fx.item("api", listOf(mod("fabric-api", null)))
        val p = Fx.plan(fabricMod("iris", "1.7.0", listOf(dep("fabric-api", ">=0.90.0"))), installed = listOf(api))
        assertEquals(PlanDecision.READY, p.decision)
    }

    @Test fun recommendedAndOptionalDependenciesDoNotAskForConfirmation() {
        val extra = listOf(dep("modmenu", kind = DependencyKind.RECOMMENDED), dep("cloth", kind = DependencyKind.OPTIONAL), dep("x", kind = DependencyKind.SUGGESTED))
        val p = Fx.plan(fabricMod("m", "1.0.0", extra))
        assertEquals(PlanDecision.READY, p.decision)
        assertTrue(codes(p).contains(PlanCode.DEP_RECOMMENDED))
        assertEquals(1, p.issues.count { it.code == PlanCode.DEP_RECOMMENDED })
        assertTrue(p.missing.isEmpty())
    }

    @Test fun serverSideForgeDependencyIsIgnoredOnTheClient() {
        val r = Fx.report(
            "m.jar",
            mods = listOf(
                mod(
                    "m", loader = ModLoader.FORGE,
                    deps = listOf(dep("minecraft", "[1.20.1]", syntax = RangeSyntax.MAVEN), dep("serverlib", syntax = RangeSyntax.MAVEN, side = "SERVER"))
                )
            )
        )
        assertEquals(PlanDecision.READY, Fx.plan(r, Fx.profile(ModLoader.FORGE)).decision)
    }

    @Test fun forgeLanguageProviderMustBeInstalled() {
        val kotlin = mod("m", loader = ModLoader.FORGE, language = "kotlinforforge", deps = listOf(dep("minecraft", "[1.20.1]", syntax = RangeSyntax.MAVEN)))
        val r = Fx.report("m.jar", mods = listOf(kotlin))
        val p = Fx.plan(r, Fx.profile(ModLoader.FORGE))
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, p.decision)
        assertEquals(PlanCode.LANGUAGE_PROVIDER, p.confirmReasons.first().code)
        val kff = Fx.item("kff", listOf(mod("kotlinforforge", "4.10.0", loader = ModLoader.FORGE)))
        assertEquals(PlanDecision.READY, Fx.plan(r, Fx.profile(ModLoader.FORGE), listOf(kff)).decision)
        val plain = mod("p", loader = ModLoader.FORGE, language = "javafml", deps = listOf(dep("minecraft", "[1.20.1]", syntax = RangeSyntax.MAVEN)))
        assertEquals(PlanDecision.READY, Fx.plan(Fx.report("p.jar", mods = listOf(plain)), Fx.profile(ModLoader.FORGE)).decision)
    }

    // ------------------------------------------------------------------ incompatibilità e conflitti

    @Test fun breaksWithAnInstalledModInRangeIsBlocked() {
        val old = Fx.item("old", listOf(mod("optifabric", "1.13.0")))
        val r = fabricMod("sodium", "0.5.3", listOf(dep("optifabric", kind = DependencyKind.BREAKS, reason = "Usa le stesse funzioni")))
        val p = Fx.plan(r, installed = listOf(old))
        assertEquals(PlanDecision.BLOCKED, p.decision)
        assertEquals(PlanCode.BREAKS, p.blockingReasons.first().code)
        assertEquals("Usa le stesse funzioni", p.blockingReasons.first().detail)
    }

    @Test fun breaksOutsideTheRangeIsFine() {
        val old = Fx.item("old", listOf(mod("optifabric", "2.0.0")))
        val r = fabricMod("sodium", "0.5.3", listOf(dep("optifabric", "<1.5.0", kind = DependencyKind.BREAKS)))
        assertEquals(PlanDecision.READY, Fx.plan(r, installed = listOf(old)).decision)
    }

    @Test fun conflictsNeedsConfirmationOnlyWhenThePartnerIsInstalled() {
        val r = fabricMod("a", "1.0.0", listOf(dep("b", kind = DependencyKind.CONFLICTS)))
        assertEquals(PlanDecision.READY, Fx.plan(r).decision)
        val p = Fx.plan(r, installed = listOf(Fx.item("b", listOf(mod("b", "1.0.0")))))
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, p.decision)
        assertEquals(PlanCode.CONFLICT, p.confirmReasons.first().code)
    }

    @Test fun anInstalledModThatBreaksTheNewOneBlocksIt() {
        val installed = Fx.item("a", listOf(mod("a", "1.0.0", deps = listOf(dep("sodium", "<0.6.0", kind = DependencyKind.BREAKS)))))
        val p = Fx.plan(fabricMod("sodium", "0.5.3"), installed = listOf(installed))
        assertEquals(PlanDecision.BLOCKED, p.decision)
        assertEquals(PlanCode.BREAKS_BY_INSTALLED, p.blockingReasons.first().code)
        val newer = Fx.plan(fabricMod("sodium", "0.6.1"), installed = listOf(installed))
        assertEquals(PlanDecision.READY, newer.decision)
    }

    @Test fun disabledModsDoNotConflict() {
        val off = Fx.item("a", listOf(mod("a", "1.0.0", deps = listOf(dep("sodium", kind = DependencyKind.BREAKS)))), enabled = false)
        assertEquals(PlanDecision.READY, Fx.plan(fabricMod("sodium", "0.5.3"), installed = listOf(off)).decision)
    }

    // ------------------------------------------------------------------ doppioni e aggiornamenti

    @Test fun sameFileTwiceIsAlreadyInstalled() {
        val r = fabricMod()
        val first = Fx.item("s", listOf(mod("sodium", "0.5.3")), sha = r.sha256)
        val p = Fx.plan(r, installed = listOf(first))
        assertEquals(PlanDecision.ALREADY_INSTALLED, p.decision)
        assertEquals(first.id, p.duplicateOf!!.id)
        assertFalse(p.canInstall)
        val off = Fx.plan(r, installed = listOf(first.copy(enabled = false)))
        assertEquals(PlanDecision.ALREADY_INSTALLED, off.decision)
        assertTrue(off.issues.first().message.contains("disattivato"))
    }

    @Test fun newerVersionIsAnUpdateAndReplacesTheOldFile() {
        val old = Fx.item("s", listOf(mod("sodium", "0.5.2")))
        val p = Fx.plan(fabricMod("sodium", "0.5.3"), installed = listOf(old))
        assertEquals(PlanDecision.READY, p.decision)
        assertEquals("s", p.replaces!!.id)
        assertTrue(codes(p).contains(PlanCode.UPDATE))
        assertEquals("sodium-0.5.3.jar", p.targetName)
    }

    @Test fun olderVersionIsADowngradeThatNeedsConfirmation() {
        val old = Fx.item("s", listOf(mod("sodium", "0.5.3")))
        val p = Fx.plan(fabricMod("sodium", "0.5.1"), installed = listOf(old))
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, p.decision)
        assertEquals(PlanCode.DOWNGRADE, p.confirmReasons.first().code)
        assertNotNull(p.replaces)
    }

    @Test fun sameVersionButDifferentFileNeedsConfirmation() {
        val old = Fx.item("s", listOf(mod("sodium", "0.5.3")))
        val p = Fx.plan(fabricMod("sodium", "0.5.3").let { Fx.report("sodium-copy.jar", mods = it.mods, sha256 = "other") }, installed = listOf(old))
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, p.decision)
        assertEquals(PlanCode.SAME_VERSION, p.confirmReasons.first().code)
    }

    @Test fun versionsThatCannotBeComparedNeedConfirmation() {
        val old = Fx.item("s", listOf(mod("sodium", null)))
        val p = Fx.plan(fabricMod("sodium", "0.5.3"), installed = listOf(old))
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, p.decision)
        assertEquals(PlanCode.SAME_VERSION, p.confirmReasons.first().code)
    }

    @Test fun updateThatBreaksAnInstalledDependentNeedsConfirmation() {
        val lib = Fx.item("lib", listOf(mod("lib", "2.5.0")))
        val user = Fx.item("user", listOf(mod("user", "1.0.0", deps = fabricDeps(listOf(dep("lib", ">=2.0.0 <3.0.0"))))))
        val p = Fx.plan(fabricMod("lib", "3.0.0"), installed = listOf(lib, user))
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, p.decision)
        val reason = p.confirmReasons.single { it.code == PlanCode.DEPENDENT_BROKEN }
        assertTrue(reason.message.contains("user"))
        val compatible = Fx.plan(fabricMod("lib", "2.9.0"), installed = listOf(lib, user))
        assertEquals(PlanDecision.READY, compatible.decision)
    }

    @Test fun updateThatNoLongerProvidesAnIdUsedByAnotherModNeedsConfirmation() {
        val api = Fx.item("api", listOf(mod("fabric-api", "0.90.0", provides = listOf("fabric"))))
        val user = Fx.item("user", listOf(mod("user", "1.0.0", deps = fabricDeps(listOf(dep("fabric"))))))
        val update = Fx.report("fabric-api-2.jar", mods = listOf(mod("fabric-api", "0.95.0", deps = fabricDeps())))
        val p = Fx.plan(update, installed = listOf(api, user))
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, p.decision)
        assertTrue(p.issues.any { it.code == PlanCode.DEPENDENT_BROKEN && it.message.contains("fabric") })
    }

    @Test fun anotherLoaderWithTheSameIdIsNotAnUpdate() {
        val forgeVersion = Fx.item("f", listOf(mod("sodium", "0.5.0", loader = ModLoader.FORGE)))
        val p = Fx.plan(fabricMod("sodium", "0.5.3"), installed = listOf(forgeVersion))
        assertNull(p.replaces)
        assertEquals("sodium-0.5.3.jar", p.targetName)
    }

    // ------------------------------------------------------------------ file particolari

    @Test fun optifineIsBlockedOnFabricAndNeedsConfirmationOnForge() {
        val r = Fx.report("OptiFine_1.20.1_HD_U_I6.jar", kind = ContentKind.OPTIFINE)
        assertEquals(PlanDecision.BLOCKED, Fx.plan(r).decision)
        assertEquals(PlanDecision.BLOCKED, Fx.plan(r, Fx.profile(ModLoader.QUILT)).decision)
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, Fx.plan(r, Fx.profile(ModLoader.FORGE)).decision)
    }

    @Test fun libraryWithoutDescriptionNeedsConfirmation() {
        val p = Fx.plan(Fx.report("lib.jar", kind = ContentKind.LIBRARY))
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, p.decision)
        assertEquals(PlanCode.LIBRARY, p.confirmReasons.first().code)
    }

    @Test fun nativeLibrariesOnlyWarn() {
        val r = Fx.report("m.jar", mods = listOf(mod("m", deps = fabricDeps())), nativeLibraries = listOf("natives/linux/libx.so"))
        val p = Fx.plan(r)
        assertEquals(PlanDecision.READY, p.decision)
        assertTrue(p.issues.any { it.code == PlanCode.NATIVE_LIBS && it.level == IssueLevel.WARNING })
    }

    // ------------------------------------------------------------------ pacchetti

    @Test fun resourcePackWithTheRightFormatIsReadyAndGoesToTheRightFolder() {
        val r = Fx.report("Faithful.zip", kind = ContentKind.RESOURCE_PACK, pack = Fx.packInfo(15))
        val p = Fx.plan(r)
        assertEquals(PlanDecision.READY, p.decision)
        assertEquals(ItemCategory.RESOURCE_PACK, p.category)
        assertEquals("Faithful.zip", p.targetName)
        assertFalse(codes(p).contains(PlanCode.PACK_FORMAT))
    }

    @Test fun resourcePackForAnotherMinecraftOnlyWarns() {
        val r = Fx.report("Old.zip", kind = ContentKind.RESOURCE_PACK, pack = Fx.packInfo(3))
        val p = Fx.plan(r)
        assertEquals(PlanDecision.READY, p.decision)
        assertTrue(p.issues.any { it.code == PlanCode.PACK_FORMAT && it.level == IssueLevel.WARNING })
    }

    @Test fun resourcePackWithUnknownFormatGetsNoJudgement() {
        val r = Fx.report("New.zip", kind = ContentKind.RESOURCE_PACK, pack = Fx.packInfo(99))
        assertFalse(codes(Fx.plan(r)).contains(PlanCode.PACK_FORMAT))
    }

    @Test fun wrappedPackIsRepackaged() {
        val r = Fx.report("Wrapped.zip", kind = ContentKind.RESOURCE_PACK, pack = Fx.packInfo(15, wrapper = "MyPack"))
        val p = Fx.plan(r)
        assertEquals(PlanDecision.READY, p.decision)
        assertEquals("MyPack", p.repackageWrapper)
        assertTrue(codes(p).contains(PlanCode.REPACKAGED))
    }

    @Test fun shaderPackWarnsWithoutAShaderMod() {
        val r = Fx.report("BSL.zip", kind = ContentKind.SHADER_PACK, pack = Fx.packInfo(null, shaders = true, assets = false))
        val p = Fx.plan(r)
        assertEquals(PlanDecision.READY, p.decision)
        assertEquals(ItemCategory.SHADER_PACK, p.category)
        assertTrue(codes(p).contains(PlanCode.SHADER_MOD))
        val iris = Fx.item("iris", listOf(mod("iris", "1.7.0")))
        assertFalse(codes(Fx.plan(r, installed = listOf(iris))).contains(PlanCode.SHADER_MOD))
        val irisOff = iris.copy(enabled = false)
        assertTrue(codes(Fx.plan(r, installed = listOf(irisOff))).contains(PlanCode.SHADER_MOD))
    }

    @Test fun dataPackStaysInTheProfile() {
        val r = Fx.report("Caves.zip", kind = ContentKind.DATA_PACK, pack = Fx.packInfo(null, data = true, assets = false))
        val p = Fx.plan(r)
        assertEquals(PlanDecision.READY, p.decision)
        assertEquals(ItemCategory.DATA_PACK, p.category)
        assertTrue(codes(p).contains(PlanCode.DATAPACK_WORLD))
    }

    @Test fun packWithTheSameNameAsAnInstalledOneNeedsConfirmation() {
        val old = Fx.item("pk", category = ItemCategory.RESOURCE_PACK, kind = ContentKind.RESOURCE_PACK, fileName = "Faithful.zip", origin = "faithful.ZIP")
        val r = Fx.report("Faithful.zip", kind = ContentKind.RESOURCE_PACK, pack = Fx.packInfo(15), sha256 = "other")
        val p = Fx.plan(r, installed = listOf(old))
        assertEquals(PlanDecision.NEEDS_CONFIRMATION, p.decision)
        assertEquals(PlanCode.REPLACES_NAME, p.confirmReasons.first().code)
        assertEquals("pk", p.replaces!!.id)
    }

    @Test fun identicalPackInAnotherCategoryIsNotADuplicate() {
        val asData = Fx.item("pk", category = ItemCategory.DATA_PACK, kind = ContentKind.DATA_PACK, sha = "same")
        val r = Fx.report("x.zip", kind = ContentKind.RESOURCE_PACK, pack = Fx.packInfo(15), sha256 = "same")
        assertEquals(PlanDecision.READY, Fx.plan(r, installed = listOf(asData)).decision)
    }

    // ------------------------------------------------------------------ nomi dei file

    @Test fun targetNamesAreSafeAndUnique() {
        assertEquals("sodium-fabric-0.5.3+mc1.20.1.jar", FileNames.forTarget("sodium-fabric-0.5.3+mc1.20.1.jar", ItemCategory.MOD))
        assertEquals("a_b_c.jar", FileNames.forTarget("a/b\\c.JAR".substringAfterLast('/').let { "a:b*c.JAR" }, ItemCategory.MOD))
        assertEquals("evil.jar", FileNames.forTarget("../../evil.jar", ItemCategory.MOD))
        assertEquals("file.jar", FileNames.forTarget("....jar", ItemCategory.MOD))
        assertEquals("pack.zip", FileNames.forTarget("pack.jar", ItemCategory.RESOURCE_PACK))
        assertEquals("Mod senza estensione.jar", FileNames.forTarget("Mod senza estensione", ItemCategory.MOD))
        assertEquals("x-2.jar", FileNames.unique("x.jar", setOf("x.jar")))
        assertEquals("X-3.jar", FileNames.unique("X.jar", setOf("x.jar", "x-2.jar")))
        assertEquals("y.jar", FileNames.unique("y.jar", setOf("x.jar")))
        assertTrue(FileNames.forTarget("x".repeat(300) + ".jar", ItemCategory.MOD).length <= 104)
    }

    @Test fun anInstalledFileWithTheSameNameGetsADifferentTargetName() {
        val other = Fx.item("a", listOf(mod("other")), fileName = "sodium-0.5.3.jar")
        val p = Fx.plan(fabricMod(), installed = listOf(other))
        assertEquals("sodium-0.5.3-2.jar", p.targetName)
        assertTrue(codes(p).contains(PlanCode.RENAMED))
        assertEquals(PlanDecision.READY, p.decision)
    }
}
