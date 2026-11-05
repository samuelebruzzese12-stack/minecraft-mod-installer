package com.mmi.modinstaller.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** L'avviso sulla versione di Minecraft nella schermata JAVA TO BEDROCK. */
class ConvertVersionTest {
    @Test fun olderMinecraftIsWarned() {
        assertEquals(
            "Il tuo Minecraft è la 1.21.50: per questo add-on serve la 1.21.80 o successiva. Aggiorna Minecraft prima di usarlo.",
            versionWarning("1.21.50.7")
        )
        assertEquals(true, versionWarning("1.20.81")?.startsWith("Il tuo Minecraft è la 1.20.81"))
    }

    @Test fun addonNeedsItsOwnVersion() {
        // Con i blocchi con più stati l'add-on chiede Minecraft 1.26.40: un 1.26.30 va avvisato, un 1.26.62 no.
        assertEquals(
            "Il tuo Minecraft è la 1.26.30: per questo add-on serve la 1.26.40 o successiva. Aggiorna Minecraft prima di usarlo.",
            versionWarning("1.26.30.1", listOf(1, 26, 40))
        )
        assertNull(versionWarning("1.26.62.1", listOf(1, 26, 40)))
        assertNull(versionWarning("1.21.95", listOf(1, 21, 90)))
    }

    @Test fun recentOrUnknownIsFine() {
        assertNull(versionWarning("1.21.80.3"))
        assertNull(versionWarning("1.26.60.5"))
        assertNull(versionWarning(null))
        assertNull(versionWarning("beta"))
    }
}
