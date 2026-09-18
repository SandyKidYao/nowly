package com.nowly.app.platform.reminders

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

object ReminderPlanner {
    fun next(now: Instant, zone: ZoneId, minutes: List<Int>): Instant? {
        val today = now.atZone(zone).toLocalDate()
        return (0L..2L).flatMap { day -> minutes.map { minute ->
            today.plusDays(day).atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant()
        } }.filter { it > now }.minOrNull()
    }

    fun isDue(at: Instant, zone: ZoneId, minutes: List<Int>): Boolean {
        val date = at.atZone(zone).toLocalDate()
        return minutes.any { date.atTime(it / 60, it % 60).atZone(zone).toInstant() == at }
    }
}
