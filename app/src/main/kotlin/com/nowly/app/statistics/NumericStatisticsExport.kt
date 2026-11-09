package com.nowly.app.statistics

import com.nowly.app.data.local.SampleEntity
import com.nowly.app.data.local.SampleOrigin
import com.nowly.app.data.local.SampleStatus
import com.nowly.app.projects.NumericValues
import com.nowly.app.projects.RecordProject
import com.nowly.app.projects.ReminderFrequency
import com.nowly.app.ui.minuteLabel
import com.nowly.app.ui.timeLabel

object NumericStatisticsExport {
    private val statuses = mapOf(SampleStatus.ANSWERED to "已记录", SampleStatus.PENDING to "待处理",
        SampleStatus.SKIPPED to "已跳过", SampleStatus.UNPROCESSED to "未处理")
    fun csv(project: RecordProject, records: List<SampleEntity>): String {
        val rows = records.sortedWith(compareBy<SampleEntity> { it.eventAt }.thenBy { it.id }).map { sample ->
            listOf(sample.localDate, timeLabel(sample.eventAt, sample.answeredZoneId ?: sample.zoneId),
                StatisticsExport.weekday(sample.localDate), statuses[sample.status].orEmpty(), sample.numericValue.orEmpty(), project.unit,
                if (sample.origin == SampleOrigin.MANUAL) "" else timeLabel(sample.plannedAt, sample.zoneId),
                sample.answeredZoneId ?: sample.zoneId, if (sample.origin == SampleOrigin.MANUAL) "主动记录" else "提醒")
        }
        return (listOf(listOf("日期", "时间", "星期", "状态", "数值", "单位", "提醒时间", "时区", "来源")) + rows)
            .joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",") { cell(it) } }
    }
    fun prompt(project: RecordProject, records: List<SampleEntity>, start: String, end: String): String {
        val summary = NumericSummary.from(records)
        val frequency = if (project.reminderFrequency == ReminderFrequency.WEEKLY) "每周 " +
            listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").filterIndexed { index, _ -> project.reminderDays and (1 shl index) != 0 }.joinToString("、") else "每天"
        return buildString {
            appendLine("下面是我用「此刻 Nowly」记录的数值数据，请描述数据变化。")
            appendLine("- 名称：${project.name}")
            appendLine("- 问题：${project.question}")
            appendLine("- 单位：${project.unit.ifEmpty { "未设置" }}")
            appendLine("- 日期范围：$start 至 $end")
            appendLine("- 提醒安排：$frequency ${project.times.joinToString("、", transform = ::minuteLabel)}；目前${if (project.remindersEnabled) "开启" else "关闭"}")
            appendLine("- 数值对应实际测量时间；主动记录与提醒回答均可计入。缺测不等于 0，未回答的提醒没有数值。")
            appendLine("- 测量 ${summary.points.size} 次")
            summary.latest?.let { appendLine("- 范围内最近一次：${NumericValues.withUnit(NumericValues.format(it.value), project.unit)}，${it.sample.localDate}") }
            summary.change?.let { appendLine("- 较上一次：${NumericValues.withUnit(NumericValues.signed(it), project.unit)}") }
            appendLine()
            if (records.size <= StatisticsExport.PROMPT_ROW_LIMIT) appendLine(csv(project, records))
            else appendLine("记录超过 ${StatisticsExport.PROMPT_ROW_LIMIT} 条，已省略逐条数据；可另行导出 CSV。")
            append("请引用具体数值说明整体变化、波动与测量间隔，并说明数据缺口。不要推断缺测数值，也不要把上升或下降自动判断为好坏；不提供健康诊断或目标建议。")
        }
    }
    private fun cell(value: String): String = if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' })
        "\"${value.replace("\"", "\"\"")}\"" else value
}
