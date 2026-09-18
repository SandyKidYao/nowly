package com.nowly.app.platform

import com.nowly.app.platform.reminders.ReminderPlanner
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReminderPlannerTest {
    @Test fun choosesTheNextTimeAcrossAllProjectsWithoutReplayingPastSlots() {
        val zone = ZoneId.of("Asia/Shanghai")
        assertEquals(Instant.parse("2026-09-18T04:00:00Z"), ReminderPlanner.next(Instant.parse("2026-09-18T02:00:00Z"), zone, listOf(600, 720, 720)))
        assertEquals(Instant.parse("2026-09-19T02:00:00Z"), ReminderPlanner.next(Instant.parse("2026-09-18T13:00:00Z"), zone, listOf(600, 1200)))
    }
    @Test fun emptyScheduleHasNoWakeup() { assertNull(ReminderPlanner.next(Instant.EPOCH, ZoneId.of("UTC"), emptyList())) }
    @Test fun springGapShiftsForwardAndIsRecognizedByDelivery() {
        val zone = ZoneId.of("America/New_York")
        val due = ReminderPlanner.next(Instant.parse("2026-03-08T06:00:00Z"), zone, listOf(150))!!
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), due)
        assertTrue(ReminderPlanner.isDue(due, zone, listOf(150)))
    }
    @Test fun autumnOverlapDoesNotSampleTwiceAtTheSameLocalTime() {
        val zone = ZoneId.of("America/New_York")
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), ReminderPlanner.next(Instant.parse("2026-11-01T05:31:00Z"), zone, listOf(90)))
    }
}
