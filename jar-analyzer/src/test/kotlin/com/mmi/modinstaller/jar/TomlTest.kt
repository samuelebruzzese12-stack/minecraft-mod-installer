package com.mmi.modinstaller.jar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TomlTest {

    @Test
    fun forgeTemplate() {
        val doc = Toml.parse(
            """
            # esempio di mods.toml
            modLoader="javafml" #nome del linguaggio
            loaderVersion="[47,)"
            license="MIT"

            [[mods]]
            modId="examplemod"
            version="${'$'}{file.jarVersion}"
            displayName="Example Mod"
            description='''
            Prima riga
            Seconda riga con "virgolette"
            '''

            [[dependencies.examplemod]]
                modId="forge"
                mandatory=true
                versionRange="[47.1.0,)"
                ordering="NONE"
                side="BOTH"
            [[dependencies.examplemod]]
                modId="minecraft"
                mandatory=true
                versionRange="[1.20.1,1.21)"
                side="BOTH"
            """.trimIndent()
        )
        assertEquals("javafml", doc["modLoader"])
        val mods = doc["mods"] as List<*>
        assertEquals(1, mods.size)
        val mod = mods[0] as Map<*, *>
        assertEquals("examplemod", mod["modId"])
        assertEquals("\${file.jarVersion}", mod["version"])
        assertEquals("Prima riga\nSeconda riga con \"virgolette\"\n", mod["description"])
        val deps = (doc["dependencies"] as Map<*, *>)["examplemod"] as List<*>
        assertEquals(2, deps.size)
        assertEquals("forge", (deps[0] as Map<*, *>)["modId"])
        assertEquals(true, (deps[0] as Map<*, *>)["mandatory"])
        assertEquals("[1.20.1,1.21)", (deps[1] as Map<*, *>)["versionRange"])
    }

    @Test
    fun valuesAndNesting() {
        val doc = Toml.parse(
            """
            a = 12
            b = -3
            c = 1_000
            d = 0x1F
            e = 3.5
            f = true
            g = [1, 2,
                 3, # tre
            ]
            h = { x = 1, y = "due" }
            "quoted key" = 'letterale \n non interpretato'
            i.j.k = "annidato"
            when = 1979-05-27
            [t.u]
            v = "w"
            """.trimIndent()
        )
        assertEquals(12L, doc["a"])
        assertEquals(-3L, doc["b"])
        assertEquals(1000L, doc["c"])
        assertEquals(31L, doc["d"])
        assertEquals(3.5, doc["e"])
        assertEquals(true, doc["f"])
        assertEquals(listOf(1L, 2L, 3L), doc["g"])
        assertEquals(mapOf("x" to 1L, "y" to "due"), doc["h"])
        assertEquals("letterale \\n non interpretato", doc["quoted key"])
        assertEquals("annidato", (((doc["i"] as Map<*, *>)["j"]) as Map<*, *>)["k"])
        assertEquals("1979-05-27", doc["when"])
        assertEquals("w", ((doc["t"] as Map<*, *>)["u"] as Map<*, *>)["v"])
    }

    @Test
    fun escapesAndCrLf() {
        val doc = Toml.parse("a = \"tab\\tqui \\u00e8 \\\"ok\\\"\"\r\nb = 'x'\r\n")
        assertEquals("tab\tqui è \"ok\"", doc["a"])
        assertEquals("x", doc["b"])
    }

    @Test
    fun bomIsIgnored() {
        assertEquals("v", Toml.parse("﻿k = \"v\"")["k"])
    }

    @Test
    fun errorsAreReportedNotThrownAsRuntime() {
        val bad = listOf(
            "a = \"non chiusa",
            "a = 1\na = 2",
            "[x\nb=1",
            "a = [1, 2",
            "= 3",
            "a = 1 b = 2",
            "a = { x = 1"
        )
        for (text in bad) {
            try {
                Toml.parse(text)
                fail("doveva fallire: $text")
            } catch (e: TomlException) {
                assertTrue(e.message!!.contains("riga"))
            }
        }
    }

    @Test
    fun hugeNestingDoesNotCrash() {
        val deep = "a = " + "[".repeat(3000) + "]".repeat(3000)
        try {
            Toml.parse(deep)
        } catch (e: TomlException) {
            // accettabile
        } catch (e: StackOverflowError) {
            fail("la profondità dell'annidamento deve essere limitata")
        }
    }
}
