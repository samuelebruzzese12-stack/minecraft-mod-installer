package com.mmi.modinstaller.mods

import com.mmi.modinstaller.jar.ContentKind
import com.mmi.modinstaller.jar.DependencyKind
import com.mmi.modinstaller.jar.ModLoader
import com.mmi.modinstaller.mods.Fx.dep
import com.mmi.modinstaller.mods.Fx.fabricDeps
import com.mmi.modinstaller.mods.Fx.mod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCheckerTest {

    private fun check(items: List<ProfileItem>, profile: Profile = Fx.profile(), missing: Set<String> = emptySet()) =
        ProfileChecker.check(profile, items, missing)

    private fun codes(c: ProfileCheck) = c.issues.map { it.code }

    @Test fun emptyProfileIsClean() {
        val c = check(emptyList())
        assertTrue(c.isClean)
        assertEquals(0, c.checkedItems)
    }

    @Test fun consistentProfileIsClean() {
        val api = Fx.item("api", listOf(mod("fabric-api", "0.92.0", deps = fabricDeps())))
        val sodium = Fx.item("sodium", listOf(mod("sodium", "0.5.3", deps = fabricDeps(listOf(dep("fabric-api", ">=0.90"))))))
        val c = check(listOf(api, sodium))
        assertTrue(c.issues.toString(), c.isClean)
        assertEquals(2, c.checkedItems)
    }

    @Test fun missingDependencyIsReportedOnTheItemThatNeedsIt() {
        val sodium = Fx.item("sodium", listOf(mod("sodium", "0.5.3", deps = fabricDeps(listOf(dep("fabric-api"))))))
        val c = check(listOf(sodium))
        assertFalse(c.isClean)
        val i = c.needsAttention.single()
        assertEquals(PlanCode.DEP_MISSING, i.code)
        assertEquals("sodium", i.itemId)
    }

    @Test fun disabledAndWrongVersionDependenciesAreReported() {
        val api = Fx.item("api", listOf(mod("fabric-api", "0.80.0")), enabled = false)
        val lib = Fx.item("lib", listOf(mod("lib", "1.0.0")))
        val a = Fx.item("a", listOf(mod("a", deps = fabricDeps(listOf(dep("fabric-api"))))))
        val b = Fx.item("b", listOf(mod("b", deps = fabricDeps(listOf(dep("lib", ">=2.0.0"))))))
        val c = check(listOf(api, lib, a, b))
        assertTrue(codes(c).contains(PlanCode.DEP_DISABLED))
        assertTrue(codes(c).contains(PlanCode.DEP_VERSION))
    }

    @Test fun disabledItemsAreNotChecked() {
        val off = Fx.item("a", listOf(mod("a", deps = fabricDeps(listOf(dep("missing"))))), enabled = false)
        val c = check(listOf(off))
        assertTrue(c.isClean)
        assertEquals(0, c.checkedItems)
    }

    @Test fun twoCopiesOfTheSameModAreBlocking() {
        val one = Fx.item("one", listOf(mod("sodium", "0.5.2", deps = fabricDeps())), fileName = "sodium-0.5.2.jar")
        val two = Fx.item("two", listOf(mod("sodium", "0.5.3", deps = fabricDeps())), fileName = "sodium-0.5.3.jar")
        val c = check(listOf(one, two))
        val dup = c.blocking.single()
        assertEquals(PlanCode.DUPLICATE_MOD, dup.code)
        assertTrue(dup.detail!!.contains("sodium-0.5.2.jar") && dup.detail!!.contains("sodium-0.5.3.jar"))
        // disattivandone una il problema sparisce
        assertTrue(check(listOf(one.copy(enabled = false), two)).blocking.isEmpty())
    }

    @Test fun duplicatesOfNestedLibrariesAreFine() {
        val a = Fx.item("a", listOf(mod("a", deps = fabricDeps())), nested = listOf(mod("lib", "1.0.0")))
        val b = Fx.item("b", listOf(mod("b", deps = fabricDeps())), nested = listOf(mod("lib", "1.1.0")))
        assertTrue(check(listOf(a, b)).isClean)
    }

    @Test fun modForAnotherLoaderIsBlockingWhenTheProfileLoaderChanges() {
        val forgeMod = Fx.item("f", listOf(mod("f", loader = ModLoader.FORGE)))
        val c = check(listOf(forgeMod), Fx.profile(ModLoader.FABRIC))
        assertEquals(PlanCode.LOADER_MISMATCH, c.blocking.single().code)
        assertEquals("f", c.blocking.single().itemId)
    }

    @Test fun breaksBetweenTwoInstalledModsIsBlocking() {
        val a = Fx.item("a", listOf(mod("a", deps = fabricDeps(listOf(dep("b", kind = DependencyKind.BREAKS))))))
        val b = Fx.item("b", listOf(mod("b", deps = fabricDeps())))
        val c = check(listOf(a, b))
        assertEquals(PlanCode.BREAKS, c.blocking.single().code)
    }

    @Test fun missingFilesAreBlocking() {
        val a = Fx.item("a", listOf(mod("a", deps = fabricDeps())))
        val c = check(listOf(a), missing = setOf("a"))
        assertEquals(PlanCode.FILE_MISSING, c.blocking.single().code)
        assertEquals(0, c.checkedItems)
    }

    @Test fun shaderPackNeedsAShaderModAmongTheEnabledOnes() {
        val shader = Fx.item("s", category = ItemCategory.SHADER_PACK, kind = ContentKind.SHADER_PACK, fileName = "bsl.zip")
        assertTrue(codes(check(listOf(shader))).contains(PlanCode.SHADER_MOD))
        val iris = Fx.item("iris", listOf(mod("iris", "1.7.0", deps = fabricDeps())))
        assertFalse(codes(check(listOf(shader, iris))).contains(PlanCode.SHADER_MOD))
        assertTrue(codes(check(listOf(shader, iris.copy(enabled = false)))).contains(PlanCode.SHADER_MOD))
    }

    @Test fun resourcePackFormatMismatchIsOnlyAWarning() {
        val pack = Fx.item("r", category = ItemCategory.RESOURCE_PACK, kind = ContentKind.RESOURCE_PACK, fileName = "old.zip", packFormat = 3)
        val c = check(listOf(pack))
        assertTrue(c.isClean)
        assertEquals(PlanCode.PACK_FORMAT, c.warnings.single().code)
    }

    @Test fun optifineOnFabricIsBlocking() {
        val of = Fx.item("o", kind = ContentKind.OPTIFINE, fileName = "OptiFine.jar")
        assertEquals(PlanCode.OPTIFINE, check(listOf(of)).blocking.single().code)
        assertEquals(PlanCode.OPTIFINE, check(listOf(of), Fx.profile(ModLoader.FORGE)).needsAttention.single().code)
    }

    @Test fun javaRequirementOfInstalledModIsChecked() {
        val m = Fx.item("m", listOf(mod("m", deps = fabricDeps())), javaMajor = 21)
        assertEquals(1, check(listOf(m)).issues.count { it.code == PlanCode.JAVA_TOO_OLD })
        assertTrue(check(listOf(m), Fx.profile(java = 21)).isClean)
    }
}
