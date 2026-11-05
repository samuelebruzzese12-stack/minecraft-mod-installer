package com.mmi.modinstaller.worlds

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldVersionsTest {

    @Test fun theSameVersionGivesNoWarning() {
        assertNull(WorldVersions.warning("1.20.1", "1.20.1"))
        // La quarta cifra non conta (non esiste nelle versioni Java).
        assertNull(WorldVersions.warning("1.20.1", "1.20.1.0"))
    }

    @Test fun aWorldNewerThanTheProfileMayBreak() {
        val w = WorldVersions.warning("1.21", "1.20.1")!!
        assertTrue(w, w.contains("più recente del profilo") && w.contains("rovinarlo"))
        // Il nuovo modo di numerare (26.x) viene dopo 1.x.
        assertTrue(WorldVersions.warning("26.2", "1.21.1")!!.contains("più recente del profilo"))
    }

    @Test fun anOlderWorldIsUpgradedByTheGame() {
        val w = WorldVersions.warning("1.19.2", "1.20.1")!!
        assertTrue(w, w.contains("aggiornerà il mondo"))
    }

    @Test fun unreadableVersionsGiveNoWarning() {
        assertNull(WorldVersions.warning(null, "1.20.1"))
        assertNull(WorldVersions.warning("1.20.1", null))
        assertNull(WorldVersions.warning("24w14a", "1.20.4"))
    }
}
