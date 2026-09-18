package com.nowly.app.statistics

import com.nowly.app.data.local.SampleEntity
import com.nowly.app.data.local.SampleStatus

data class Distribution(val label: String, val counts: Map<String, Int>) {
    val total: Int get() = counts.values.sum()
}

data class StatisticsData(val answered: Int, val skipped: Int, val unprocessed: Int,
    val dates: List<Distribution>, val hours: List<Distribution>, val options: Map<String, Int>) {
    companion object {
        fun from(records: List<SampleEntity>): StatisticsData {
            val answers = records.filter { it.status == SampleStatus.ANSWERED && it.optionId != null }
            fun counts(items: List<SampleEntity>) = items.groupingBy { it.optionId!! }.eachCount()
            val bands = listOf(Triple(0, 8, "00–08"), Triple(8, 12, "08–12"), Triple(12, 16, "12–16"), Triple(16, 20, "16–20"), Triple(20, 24, "20–24"))
            return StatisticsData(answers.size, records.count { it.status == SampleStatus.SKIPPED }, records.count { it.status == SampleStatus.UNPROCESSED },
                answers.groupBy { it.localDate }.toSortedMap().map { Distribution(it.key, counts(it.value)) },
                bands.map { (start, end, label) -> Distribution(label, counts(answers.filter { it.localHour in start until end })) }, counts(answers))
        }
    }
}
