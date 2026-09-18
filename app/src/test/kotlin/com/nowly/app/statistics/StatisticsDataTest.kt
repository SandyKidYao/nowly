package com.nowly.app.statistics

import com.nowly.app.data.local.SampleEntity
import com.nowly.app.data.local.SampleStatus
import org.junit.Assert.*
import org.junit.Test

class StatisticsDataTest {
    @Test fun onlyAnswersCountAndGroupingUsesSavedAnswerDateAndHour() {
        val answer = SampleEntity("one", "p", 1, 2, SampleStatus.ANSWERED, "option-id", 3, "2026-09-19", 10, "Asia/Shanghai", 3)
        val stats = StatisticsData.from(listOf(answer, answer.copy(id = "two"),
            answer.copy(id = "skip", status = SampleStatus.SKIPPED, optionId = null, answeredAt = null),
            answer.copy(id = "old", status = SampleStatus.UNPROCESSED, optionId = null, answeredAt = null),
            answer.copy(id = "pending", status = SampleStatus.PENDING, optionId = null, answeredAt = null)))
        assertEquals(2, stats.answered)
        assertEquals(1, stats.skipped)
        assertEquals(1, stats.unprocessed)
        assertEquals(mapOf("option-id" to 2), stats.options)
        assertEquals("2026-09-19", stats.dates.single().label)
        assertEquals(2, stats.hours.first { it.label == "08–12" }.total)
        assertEquals(0, stats.hours.first { it.label == "00–08" }.total)
    }
    @Test fun noAnswersIsAnEmptyDistributionRatherThanZeroPressure() {
        val stats = StatisticsData.from(emptyList())
        assertTrue(stats.options.isEmpty()); assertTrue(stats.dates.isEmpty()); assertEquals(0, stats.answered)
    }
}
