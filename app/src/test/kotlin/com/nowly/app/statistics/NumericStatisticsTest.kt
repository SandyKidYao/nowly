package com.nowly.app.statistics

import com.nowly.app.data.local.*
import com.nowly.app.projects.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class NumericStatisticsTest {
    private val at = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
    private val project = RecordProject("weight", "体重", "这次是多少？", true, at, times = listOf(480),
        kind = ProjectKind.NUMBER, unit = "kg", reminderFrequency = ReminderFrequency.WEEKLY, reminderDays = 64)
    private fun sample(offset: Int, value: String?) = SampleEntity("weight@${at + offset * 86_400_000L}", "weight", at + offset * 86_400_000L,
        null, if (value == null) SampleStatus.SKIPPED else SampleStatus.ANSWERED, null,
        if (value == null) null else at + offset * 86_400_000L,
        "2026-11-${(1 + offset).toString().padStart(2, '0')}", 8, "Asia/Shanghai", at + offset * 86_400_000L,
        if (value == null) null else "Asia/Shanghai", value)
    @Test fun missingMeasurementsAreNotZerosAndLatestChangeUsesActualOrderedMeasurements() {
        val summary = NumericSummary.from(listOf(sample(7, "65.3"), sample(2, null), sample(0, "65.2")))
        assertEquals(2, summary.points.size)
        assertEquals("65.3", summary.latest!!.value.toPlainString())
        assertEquals("0.1", summary.change!!.toPlainString())
        assertNull(NumericSummary.from(listOf(sample(2, null))).latest)
        assertNull(NumericSummary.from(listOf(sample(0, "0"))).change)
    }
    @Test fun numericCsvAndPromptPreserveValuesUnitsMissingStatesAndWeeklySchedule() {
        val records = listOf(sample(0, "65.2"), sample(7, "65.3"), sample(4, null))
        val csv = StatisticsExport.csv(project, records)
        assertTrue(csv.startsWith("日期,时间,星期,状态,数值,单位"))
        assertTrue(csv.contains("已记录,65.2,kg"))
        assertTrue(csv.contains("已跳过,,kg"))
        val prompt = StatisticsExport.prompt(project, records, "2026-11-01", "2026-11-08", null, "Asia/Shanghai")
        assertTrue(prompt.contains("较上一次：+0.1 kg"))
        assertTrue(prompt.contains("每周 周日 08:00"))
        assertFalse(prompt.contains("平均等级"))
    }
}
