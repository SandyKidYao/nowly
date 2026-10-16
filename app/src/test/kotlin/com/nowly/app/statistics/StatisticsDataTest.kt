package com.nowly.app.statistics

import com.nowly.app.data.local.SampleEntity
import com.nowly.app.data.local.SampleStatus
import com.nowly.app.projects.AnswerOption
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class StatisticsDataTest {
    private val day = LocalDate.parse("2026-09-19")
    private val scale = LevelScale(listOf(AnswerOption("low", "低", 0), AnswerOption("mid", "中", 1), AnswerOption("option-id", "高", 2),
        AnswerOption("old", "旧", 5, active = false)))

    @Test fun onlyAnswersCountAndGroupingUsesSavedAnswerDateAndHour() {
        val answer = SampleEntity("one", "p", 1, 2, SampleStatus.ANSWERED, "option-id", 3, "2026-09-19", 10, "Asia/Shanghai", 3)
        val stats = StatisticsData.from(listOf(answer, answer.copy(id = "two"),
            answer.copy(id = "skip", status = SampleStatus.SKIPPED, optionId = null, answeredAt = null),
            answer.copy(id = "old", status = SampleStatus.UNPROCESSED, optionId = null, answeredAt = null),
            answer.copy(id = "pending", status = SampleStatus.PENDING, optionId = null, answeredAt = null)), scale, day, day)
        assertEquals(2, stats.answered)
        assertEquals(1, stats.skipped)
        assertEquals(1, stats.unprocessed)
        assertEquals(mapOf("option-id" to 2), stats.options)
        assertEquals(day, stats.trend.single().start)
        assertEquals(2, stats.hours.first { it.label == "08–12" }.total)
        assertEquals(0, stats.hours.first { it.label == "00–08" }.total)
        assertEquals(50, stats.responseRate)
    }

    @Test fun noAnswersIsAnEmptyDistributionRatherThanZeroPressure() {
        val stats = StatisticsData.from(emptyList(), scale, day, day)
        assertTrue(stats.options.isEmpty()); assertNull(stats.mean); assertNull(stats.trend.single().mean); assertEquals(0, stats.answered)
        assertNull(stats.responseRate)
    }

    @Test fun averageUsesPositionOnActiveScaleAndIgnoresRetiredOptions() {
        val answer = SampleEntity("a", "p", 1, 2, SampleStatus.ANSWERED, "low", 3, "2026-09-19", 10, "Asia/Shanghai", 3)
        val records = listOf(answer, answer.copy(id = "b", optionId = "option-id"), answer.copy(id = "c", optionId = "option-id", localHour = 21),
            answer.copy(id = "d", optionId = "old"))
        val stats = StatisticsData.from(records, scale, day, day)
        assertEquals((1 + 3 + 3) / 3.0, stats.mean!!, 1e-9)
        assertEquals(2.0, stats.hours.first { it.label == "08–12" }.mean!!, 1e-9)
        assertEquals(3.0, stats.hours.first { it.label == "20–24" }.mean!!, 1e-9)
        assertEquals("中", scale.nearest(stats.mean!!).label)
        assertNull(scale.level("old"))
    }

    @Test fun trendKeepsEmptyDaysAndSwitchesToWeeksForLongRanges() {
        val answer = SampleEntity("a", "p", 1, 2, SampleStatus.ANSWERED, "mid", 3, "2026-09-19", 10, "Asia/Shanghai", 3)
        val week = StatisticsData.from(listOf(answer), scale, day.minusDays(6), day)
        assertFalse(week.weekly)
        assertEquals(7, week.trend.size)
        assertEquals(listOf(null, null, null, null, null, null, 2.0), week.trend.map { it.mean })
        val long = StatisticsData.from(listOf(answer), scale, day.minusDays(89), day)
        assertTrue(long.weekly)
        assertEquals(day.minusDays(89), long.trend.first().start)
        assertEquals(day, long.trend.last().end)
        assertTrue(long.trend.all { it.start.dayOfWeek == java.time.DayOfWeek.MONDAY || it.start == day.minusDays(89) })
        assertEquals(1, long.trend.sumOf { it.total })
    }

    @Test fun mosaicPlacesEveryPromptUnderItsReminderTimeWithNewestDateFirst() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        fun at(date: LocalDate, hour: Int, minute: Int = 0) = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        val answered = SampleEntity("a", "p", at(day, 10), at(day, 10, 20), SampleStatus.ANSWERED, "mid", at(day, 10, 20), day.toString(), 10, zone.id, at(day, 10, 20), zone.id)
        val skipped = SampleEntity("s", "p", at(day, 15, 30), null, SampleStatus.SKIPPED, null, null, day.toString(), 15, zone.id, at(day, 15, 30))
        val older = answered.copy(id = "o", plannedAt = at(day.minusDays(2), 12), localDate = day.minusDays(2).toString())
        val mosaic = RecordMosaic.from(listOf(skipped, answered, older), listOf(600, 1200), day.minusDays(2), day)
        assertEquals(listOf(600, 720, 930, 1200), mosaic.columns)
        assertEquals(listOf(day, day.minusDays(1), day.minusDays(2)), mosaic.days.map { it.date })
        assertEquals(listOf("a", null, "s", null), mosaic.days[0].cells.map { it?.id })
        assertTrue(mosaic.days[1].cells.all { it == null })
        assertEquals(listOf(null, "o", null, null), mosaic.days[2].cells.map { it?.id })
    }

    @Test fun mosaicMergesColumnsByHourWhenScheduleChangedOften() {
        val times = (0 until RecordMosaic.MAX_COLUMNS + 1).map { 480 + it * 50 }
        val mosaic = RecordMosaic.from(emptyList(), times, day, day)
        assertEquals(times.map { it / 60 * 60 }.distinct(), mosaic.columns)
        assertTrue(mosaic.columns.size <= RecordMosaic.MAX_COLUMNS)
    }

    @Test fun scaleColorsHaveOneColorPerLevelWithExactPolesAndMiddle() {
        (1..20).forEach { assertEquals(it, ScaleColors.colors(it).size) }
        val five = ScaleColors.colors(5)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF9A5B26), five.first())
        assertEquals(androidx.compose.ui.graphics.Color(0xFFCDC8BC), five[2])
        assertEquals(androidx.compose.ui.graphics.Color(0xFF2E5E78), five.last())
        assertEquals(five.toSet().size, five.size)
    }
}
