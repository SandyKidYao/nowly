package com.nowly.app.statistics

import com.nowly.app.data.local.SampleEntity
import com.nowly.app.data.local.SampleStatus
import java.math.BigDecimal

data class NumericPoint(val sample: SampleEntity, val value: BigDecimal)
data class NumericSummary(val points: List<NumericPoint>) {
    val latest get() = points.lastOrNull()
    val previous get() = points.getOrNull(points.size - 2)
    val change: BigDecimal? get() = latest?.value?.let { last -> previous?.value?.let(last::subtract) }
    companion object {
        fun from(records: List<SampleEntity>) = NumericSummary(records.filter { it.status == SampleStatus.ANSWERED }
            .mapNotNull { sample -> sample.numericValue?.toBigDecimalOrNull()?.let { NumericPoint(sample, it) } }
            .sortedWith(compareBy<NumericPoint> { it.sample.eventAt }.thenBy { it.sample.id }))
    }
}
