package io.embodit.lightplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fast pinching stays quiet (taste profile D15): each kind plays at most once per its gap. */
class SoundGateTest {
    @Test
    fun repeatedRejectsPlayOncePerGap() {
        val g = SoundGate()
        var played = 0
        // ten refused pinches in two seconds
        for (t in 0L until 2000L step 200L) if (g.allow("reject", t)) played++
        assertEquals(3, played)
    }

    @Test
    fun kindsDoNotBlockEachOther() {
        val g = SoundGate()
        assertTrue(g.allow("select", 0))
        assertTrue(g.allow("reject", 10))
        assertTrue(g.allow("place", 20))
        assertFalse(g.allow("select", 100))
        assertTrue(g.allow("select", 400))
    }
}
