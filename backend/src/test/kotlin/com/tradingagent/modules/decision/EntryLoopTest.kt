package com.tradingagent.modules.decision

import com.tradingagent.modules.strategy.ClosedBarPolicy
import com.tradingagent.modules.strategy.MandateControl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class EntryLoopTest {
    @Test
    fun `the next cycle waits until the following five minute close`() {
        val exactly = Instant.parse("2026-11-27T21:50:00Z")
        assertEquals(305, EntryPace.wait(exactly, 300, 5).seconds)
        val justAfter = Instant.parse("2026-11-27T21:50:01Z")
        assertEquals(304, EntryPace.wait(justAfter, 300, 5).seconds)
        val nearClose = Instant.parse("2026-11-27T21:54:59Z")
        assertEquals(6, EntryPace.wait(nearClose, 300, 5).seconds)
        val started = Instant.parse("2026-11-28T06:00:00Z")
        assertEquals(20, EntryPace.BREAKOUT_INTERVAL.seconds)
        assertEquals(15, EntryPace.remaining(started, started.plusSeconds(5), EntryPace.BREAKOUT_INTERVAL).seconds)
        assertEquals(0, EntryPace.remaining(started, started.plusSeconds(25), EntryPace.BREAKOUT_INTERVAL).seconds)
    }

    @Test
    fun `only a spent book or a blocked entry stops the loop`() {
        assertTrue(EntryPace.finished(MandateControl.BUDGET_LIMIT))
        assertTrue(EntryPace.finished(MandateControl.EXPOSURE_LIMIT))
        assertTrue(EntryPace.finished(MandateControl.LOSS_LIMIT))
        assertTrue(EntryPace.finished(MandateControl.EXISTING_POSITION))
        assertTrue(EntryPace.finished(ClosedBarPolicy.KILL_SWITCH))
        assertFalse(EntryPace.finished(MandateControl.SCORE))
        assertFalse(EntryPace.finished(MandateControl.WARMUP))
        assertFalse(EntryPace.finished(MandateControl.OK))
    }
}
