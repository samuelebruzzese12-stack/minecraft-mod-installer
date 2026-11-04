package com.mmi.modinstaller.jar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {

    private fun v(s: String): ModVersion = ModVersion.parse(s)!!

    @Test
    fun ordering() {
        assertTrue(v("1.20.1") < v("1.20.2"))
        assertTrue(v("1.9") < v("1.10"))
        assertEquals(0, v("1.20").compareTo(v("1.20.0")))
        assertTrue(v("1.0.0-beta.1") < v("1.0.0-beta.2"))
        assertTrue(v("1.0.0-alpha") < v("1.0.0-beta"))
        assertTrue(v("1.0.0-rc.1") < v("1.0.0"))
        assertTrue(v("1.0.0-pre1") < v("1.0.0"))
        assertTrue(v("1.0.0-SNAPSHOT") < v("1.0.0"))
        assertTrue(v("1.0.0") < v("1.0.0-forge"))
        assertTrue(v("1.2.3") < v("1.2.3-1"))
        assertEquals(0, v("0.92.2+1.20.1").compareTo(v("0.92.2")))
        assertEquals(0, v("v1.2.3").compareTo(v("1.2.3")))
        assertTrue(v("0.15.11") > v("0.14.21"))
        assertTrue(v("1.0-rc1") < v("1.0-rc2"))
        assertTrue(v("1.0-rc") < v("1.0-rc.1"))
    }

    @Test
    fun notComparable() {
        assertFalse(v("24w14a").comparable)
        assertFalse(v("latest").comparable)
        assertFalse(v("\${version}").comparable)
        assertNull(ModVersion.parse(""))
        assertNull(ModVersion.parse(null))
        assertTrue(v("1.20.1").comparable)
        // numeri enormi non fanno traboccare
        assertFalse(v("99999999999999999999999.1").comparable)
    }

    @Test
    fun mavenRanges() {
        val r = VersionRanges.parseMaven("[1.20,1.21)")
        assertEquals(Compat.YES, r.test("1.20"))
        assertEquals(Compat.YES, r.test("1.20.1"))
        assertEquals(Compat.YES, r.test("1.20.6"))
        assertEquals(Compat.NO, r.test("1.21"))
        assertEquals(Compat.NO, r.test("1.19.4"))
        assertEquals(Compat.UNKNOWN, r.test("24w14a"))
        assertEquals(Compat.UNKNOWN, r.test(null as String?))

        val exact = VersionRanges.parseMaven("[1.20.1]")
        assertEquals(Compat.YES, exact.test("1.20.1"))
        assertEquals(Compat.NO, exact.test("1.20.2"))

        val open = VersionRanges.parseMaven("[47,)")
        assertEquals(Compat.YES, open.test("47.1.0"))
        assertEquals(Compat.YES, open.test("48"))
        assertEquals(Compat.NO, open.test("46.0.14"))

        val upTo = VersionRanges.parseMaven("(,1.0]")
        assertEquals(Compat.YES, upTo.test("0.5"))
        assertEquals(Compat.YES, upTo.test("1.0"))
        assertEquals(Compat.NO, upTo.test("1.0.1"))

        val exclusive = VersionRanges.parseMaven("(1.0,2.0)")
        assertEquals(Compat.NO, exclusive.test("1.0"))
        assertEquals(Compat.YES, exclusive.test("1.5"))
        assertEquals(Compat.NO, exclusive.test("2.0"))

        val union = VersionRanges.parseMaven("(,1.0],[1.2,)")
        assertEquals(Compat.YES, union.test("0.9"))
        assertEquals(Compat.NO, union.test("1.1"))
        assertEquals(Compat.YES, union.test("1.2"))
    }

    @Test
    fun mavenTolerance() {
        assertEquals(Compat.YES, VersionRanges.parseMaven("*").test("5.0"))
        assertEquals(Compat.YES, VersionRanges.parseMaven("").test("5.0"))
        assertEquals(Compat.YES, VersionRanges.parseMaven(null).test("5.0"))
        // una versione semplice in Maven è solo consigliata: non limita nulla
        assertEquals(Compat.YES, VersionRanges.parseMaven("1.20.1").test("1.19"))
        for (bad in listOf("[1.0", "[1.0,2.0", "[2.0,1.0]", "(1.0)", "[a,b,c]", "[1.0,2.0)x", "[\${mc},)")) {
            val c = VersionRanges.parseMaven(bad)
            assertTrue("$bad", c is UnparsedConstraint)
            assertEquals(Compat.UNKNOWN, c.test("1.5"))
        }
    }

    @Test
    fun semverPredicates() {
        assertEquals(Compat.YES, VersionRanges.parseSemver(">=0.14.21").test("0.15.0"))
        assertEquals(Compat.NO, VersionRanges.parseSemver(">=0.14.21").test("0.14.9"))
        assertEquals(Compat.NO, VersionRanges.parseSemver(">1.0").test("1.0"))
        assertEquals(Compat.YES, VersionRanges.parseSemver("<=1.0").test("1.0"))
        assertEquals(Compat.NO, VersionRanges.parseSemver("<1.0").test("1.0"))
        assertEquals(Compat.YES, VersionRanges.parseSemver("=1.20.1").test("1.20.1"))
        assertEquals(Compat.YES, VersionRanges.parseSemver("1.20.1").test("1.20.1"))
        assertEquals(Compat.NO, VersionRanges.parseSemver("1.20.1").test("1.20.2"))
        assertEquals(Compat.YES, VersionRanges.parseSemver("*").test("anything"))
        assertEquals(Compat.YES, VersionRanges.parseSemver(">= 1.2").test("1.3"))
    }

    @Test
    fun semverTildeCaretAndWildcards() {
        val tilde = VersionRanges.parseSemver("~1.20.1")
        assertEquals(Compat.YES, tilde.test("1.20.1"))
        assertEquals(Compat.YES, tilde.test("1.20.4"))
        assertEquals(Compat.NO, tilde.test("1.20.0"))
        assertEquals(Compat.NO, tilde.test("1.21"))
        val caret = VersionRanges.parseSemver("^1.2.3")
        assertEquals(Compat.YES, caret.test("1.9.0"))
        assertEquals(Compat.NO, caret.test("2.0.0"))
        assertEquals(Compat.NO, caret.test("1.2.2"))
        val x = VersionRanges.parseSemver("1.20.x")
        assertEquals(Compat.YES, x.test("1.20.5"))
        assertEquals(Compat.NO, x.test("1.21"))
        assertEquals(Compat.NO, x.test("1.19.9"))
        val star = VersionRanges.parseSemver("1.*")
        assertEquals(Compat.YES, star.test("1.99"))
        assertEquals(Compat.NO, star.test("2.0"))
        assertEquals(Compat.YES, VersionRanges.parseSemver(">=1.20.x").test("1.20.0"))
        assertEquals(Compat.NO, VersionRanges.parseSemver("<1.20.x").test("1.20.0"))
        assertEquals(Compat.NO, VersionRanges.parseSemver(">1.20.x").test("1.20.9"))
        assertEquals(Compat.YES, VersionRanges.parseSemver(">1.20.x").test("1.21"))
    }

    @Test
    fun semverCombinations() {
        val both = VersionRanges.parseSemver(">=1.20 <1.21")
        assertEquals(Compat.YES, both.test("1.20.4"))
        assertEquals(Compat.NO, both.test("1.21"))
        assertEquals(Compat.NO, both.test("1.19"))
        val or = VersionRanges.parseSemverAnyOf(listOf("1.20.1", "1.20.4"))
        assertEquals(Compat.YES, or.test("1.20.4"))
        assertEquals(Compat.NO, or.test("1.20.2"))
        val pipes = VersionRanges.parseSemver("1.19.x || 1.20.x")
        assertEquals(Compat.YES, pipes.test("1.19.4"))
        assertEquals(Compat.YES, pipes.test("1.20.1"))
        assertEquals(Compat.NO, pipes.test("1.21"))
        assertEquals(Compat.UNKNOWN, both.test("24w14a"))
        assertEquals(Compat.UNKNOWN, VersionRanges.parseSemver("@@").test("1.0"))
    }

    @Test
    fun descriptionsAreReadable() {
        assertEquals("da 1.20 (incluso) a 1.21 (escluso)", VersionRanges.parseMaven("[1.20,1.21)").describe())
        assertEquals("47 o successiva", VersionRanges.parseMaven("[47,)").describe())
        assertEquals("esattamente 1.20.1", VersionRanges.parseMaven("[1.20.1]").describe())
        assertEquals("qualsiasi versione", VersionRanges.parseSemver("*").describe())
        assertNotNull(VersionRanges.parseSemver("~1.20.1").describe())
    }

    @Test
    fun dependencyUsesItsSyntax() {
        val maven = ModDependency("minecraft", DependencyKind.REQUIRED, "[1.20,1.21)", RangeSyntax.MAVEN)
        val semver = ModDependency("minecraft", DependencyKind.REQUIRED, "~1.20.1", RangeSyntax.SEMVER)
        assertEquals(Compat.YES, maven.test("1.20.4"))
        assertEquals(Compat.YES, semver.test("1.20.4"))
        assertTrue(maven.isPlatform)
        assertFalse(ModDependency("sodium", DependencyKind.REQUIRED).isPlatform)
        assertEquals(Compat.YES, ModDependency("x", DependencyKind.REQUIRED).test("1.0"))
    }
}
