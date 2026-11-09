package com.nowly.app.platform.reminders

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

object ReminderPlanner {
    fun next(now: Instant, zone: ZoneId, minutes: List<Int>, days: Int = 127): Instant? {
        val today = now.atZone(zone).toLocalDate()
        return (0L..7L).filter { offset -> days and (1 shl (today.plusDays(offset).dayOfWeek.value - 1)) != 0 }
            .flatMap { day -> minutes.map { minute ->
            today.plusDays(day).atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant()
        } }.filter { it > now }.minOrNull()
    }

    fun isDue(at: Instant, zone: ZoneId, minutes: List<Int>, days: Int = 127): Boolean {
        val date = at.atZone(zone).toLocalDate()
        if (days and (1 shl (date.dayOfWeek.value - 1)) == 0) return false
        return minutes.any { date.atTime(it / 60, it % 60).atZone(zone).toInstant() == at }
    }
}
