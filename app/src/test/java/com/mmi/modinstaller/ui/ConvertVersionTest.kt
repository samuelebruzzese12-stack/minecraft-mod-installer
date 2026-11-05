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

    @Test fun recentOrUnknownIsFine() {
        assertNull(versionWarning("1.21.80.3"))
        assertNull(versionWarning("1.26.60.5"))
        assertNull(versionWarning(null))
        assertNull(versionWarning("beta"))
    }
}
