package com.nowly.app.statistics

import com.nowly.app.data.local.SampleEntity
import com.nowly.app.data.local.SampleStatus
import com.nowly.app.projects.AnswerOption
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** Every record is an ordered scale: active options by position are levels 1..N; retired options have no level. */
class LevelScale(options: List<AnswerOption>) {
    val levels: List<AnswerOption> = options.filter { it.active }.sortedBy { it.position }
    private val byId = levels.withIndex().associate { (index, option) -> option.id to index + 1 }
    val size: Int get() = levels.size
    fun level(optionId: String?): Int? = byId[optionId]
    fun mean(records: List<SampleEntity>): Double? =
        records.mapNotNull { if (it.status == SampleStatus.ANSWERED) level(it.optionId) else null }.takeIf { it.isNotEmpty() }?.average()
    fun nearest(mean: Double): AnswerOption = levels[(mean.roundToInt() - 1).coerceIn(levels.indices)]
}

data class Distribution(val label: String, val counts: Map<String, Int>, val mean: Double? = null) {
    val total: Int get() = counts.values.sum()
}

/** One day, or one week when the range is long; empty buckets stay so the time axis is continuous. */
data class TrendPoint(val start: LocalDate, val end: LocalDate, val counts: Map<String, Int>, val mean: Double?) {
    val total: Int get() = counts.values.sum()
}

data class StatisticsData(val answered: Int, val skipped: Int, val unprocessed: Int, val mean: Double?,
    val trend: List<TrendPoint>, val weekly: Boolean, val hours: List<Distribution>, val options: Map<String, Int>) {
    /** Pending prompts are still open, so they are not part of the rate. */
    val responseRate: Int? get() = (answered + skipped + unprocessed).takeIf { it > 0 }?.let { (answered * 100f / it).roundToInt() }

    companion object {
        /** Ranges longer than about two months are drawn by week to keep each point readable. */
        const val DAILY_LIMIT = 62

        fun from(records: List<SampleEntity>, scale: LevelScale, start: LocalDate, end: LocalDate): StatisticsData {
            val answers = records.filter { it.status == SampleStatus.ANSWERED && it.optionId != null && it.answeredAt != null }
            fun counts(items: List<SampleEntity>) = items.groupingBy { it.optionId!! }.eachCount()
            val bands = listOf(Triple(0, 8, "00–08"), Triple(8, 12, "08–12"), Triple(12, 16, "12–16"), Triple(16, 20, "16–20"), Triple(20, 24, "20–24"))
            val weekly = ChronoUnit.DAYS.between(start, end) + 1 > DAILY_LIMIT
            val byDate = answers.groupBy { LocalDate.parse(it.localDate) }
            val trend = if (end < start) emptyList() else generateSequence(if (weekly) start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else start) {
                it.plusDays(if (weekly) 7 else 1)
            }.takeWhile { it <= end }.map { first ->
                val from = maxOf(first, start); val to = if (weekly) minOf(first.plusDays(6), end) else first
                val items = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.flatMap { byDate[it].orEmpty() }.toList()
                TrendPoint(from, to, counts(items), scale.mean(items))
            }.toList()
            return StatisticsData(answers.size, records.count { it.status == SampleStatus.SKIPPED }, records.count { it.status == SampleStatus.UNPROCESSED },
                scale.mean(answers), trend, weekly,
                bands.map { (from, to, label) -> answers.filter { it.localHour in from until to }.let { Distribution(label, counts(it), scale.mean(it)) } },
                counts(answers))
        }
    }
}

/** One row of the record mosaic: a date and, per reminder time column, the prompt it held (null when there was none). */
data class MosaicDay(val date: LocalDate, val cells: List<SampleEntity?>)

/**
 * Every prompt in the range on a grid: one row per date (newest first), one column per reminder time of day.
 * Rows use the record's saved date like the rest of the page; columns use the planned reminder time, so
 * answered, skipped and unprocessed prompts line up under the reminder that raised them.
 */
data class RecordMosaic(val columns: List<Int>, val days: List<MosaicDay>) {
    companion object {
        /** Beyond this many reminder times (schedules changed often) columns merge by hour to stay tappable. */
        const val MAX_COLUMNS = 12

        fun plannedMinute(sample: SampleEntity): Int =
            Instant.ofEpochMilli(sample.plannedAt).atZone(ZoneId.of(sample.zoneId)).toLocalTime().let { it.hour * 60 + it.minute }

        fun from(records: List<SampleEntity>, reminderTimes: List<Int>, start: LocalDate, end: LocalDate): RecordMosaic {
            val exact = (records.map(::plannedMinute) + reminderTimes).distinct().sorted()
            fun column(minute: Int) = if (exact.size > MAX_COLUMNS) minute / 60 * 60 else minute
            val columns = exact.map(::column).distinct()
            // Should two prompts share a cell (an answer saved after midnight, or merged hours), the later one is shown.
            val byCell = records.sortedBy { it.eventAt }.associateBy { LocalDate.parse(it.localDate) to column(plannedMinute(it)) }
            val days = if (end < start) emptyList() else generateSequence(end) { it.minusDays(1) }.takeWhile { it >= start }
                .map { date -> MosaicDay(date, columns.map { byCell[date to it] }) }.toList()
            return RecordMosaic(columns, days)
        }
    }
}
