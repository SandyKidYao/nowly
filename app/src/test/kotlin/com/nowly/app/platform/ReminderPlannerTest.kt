package com.nowly.app.platform

import com.nowly.app.platform.reminders.ReminderPlanner
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReminderPlannerTest {
    @Test fun weeklyScheduleSkipsOtherDaysAndMovesToNextWeekAfterTheExactSlot() {
        val zone = ZoneId.of("Asia/Shanghai")
        val sunday = 64
        val due = ReminderPlanner.next(Instant.parse("2026-11-02T00:00:00Z"), zone, listOf(480), sunday)!!
        assertEquals(Instant.parse("2026-11-08T00:00:00Z"), due)
        assertTrue(ReminderPlanner.isDue(due, zone, listOf(480), sunday))
        assertFalse(ReminderPlanner.isDue(Instant.parse("2026-11-03T00:00:00Z"), zone, listOf(480), sunday))
        assertEquals(Instant.parse("2026-11-15T00:00:00Z"), ReminderPlanner.next(due, zone, listOf(480), sunday))
    }
    @Test fun weeklySchedulesUseLocalCalendarAcrossDaylightSavingChanges() {
        val zone = ZoneId.of("America/New_York")
        val first = ReminderPlanner.next(Instant.parse("2026-03-01T12:00:00Z"), zone, listOf(480), 64)!!
        val next = ReminderPlanner.next(first, zone, listOf(480), 64)!!
        assertEquals(Instant.parse("2026-03-01T13:00:00Z"), first)
        assertEquals(Instant.parse("2026-03-08T12:00:00Z"), next)
        assertEquals(167L, java.time.Duration.between(first, next).toHours())
    }
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
