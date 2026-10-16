package com.nowly.app.statistics

import com.nowly.app.data.local.SampleEntity
import com.nowly.app.data.local.SampleStatus
import com.nowly.app.projects.RecordProject
import com.nowly.app.ui.minuteLabel
import com.nowly.app.ui.timeLabel
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/** Plain-text exports of one record's samples in the statistics date range, oldest first. */
object StatisticsExport {
    /** Above this the prompt keeps the summaries and drops the raw rows, so it still pastes into a chat. */
    const val PROMPT_ROW_LIMIT = 1500

    private val statusLabels = mapOf(SampleStatus.ANSWERED to "已回答", SampleStatus.SKIPPED to "已跳过",
        SampleStatus.UNPROCESSED to "未处理", SampleStatus.PENDING to "待处理")
    private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun weekday(date: String): String = weekdays[LocalDate.parse(date).dayOfWeek.value - 1]
    fun format(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    fun fileName(project: RecordProject, start: String, end: String): String =
        "nowly-${project.name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")}-$start-$end.csv"

    fun csv(project: RecordProject, records: List<SampleEntity>): String {
        val scale = LevelScale(project.options)
        val rows = records.sortedWith(compareBy<SampleEntity> { it.eventAt }.thenBy { it.id }).map { record ->
            val option = record.optionId?.let { id -> project.options.firstOrNull { it.id == id } }
            listOf(record.localDate, eventTime(record), weekday(record.localDate), statusLabels[record.status] ?: record.status,
                if (record.status == SampleStatus.ANSWERED) option?.label ?: "已停用选项" else "",
                scale.level(record.optionId)?.toString().orEmpty(), timeLabel(record.plannedAt, record.zoneId), record.answeredZoneId ?: record.zoneId)
        }
        return (listOf(listOf("日期", "时间", "星期", "状态", "选项", "等级", "提醒时间", "时区")) + rows)
            .joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",") { cell(it) } }
    }

    fun prompt(project: RecordProject, records: List<SampleEntity>, start: String, end: String, previousMean: Double?, zone: String): String {
        val scale = LevelScale(project.options)
        val stats = StatisticsData.from(records, scale, LocalDate.parse(start), LocalDate.parse(end))
        val days = ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end)) + 1
        val pending = records.count { it.status == SampleStatus.PENDING }
        val retired = project.options.filter { !it.active && (stats.options[it.id] ?: 0) > 0 }
        val sorted = records.sortedWith(compareBy<SampleEntity> { it.eventAt }.thenBy { it.id })
        return buildString {
            appendLine("你是一位擅长个人数据分析的助手。下面是我用「此刻 Nowly」做经验采样记录的数据：App 每天在固定时间提醒我，我用一个单选回答当下的状态，不方便时可以跳过。请根据数据帮我分析。")
            appendLine()
            appendLine("## 记录说明")
            appendLine("- 记录名称：${project.name}")
            appendLine("- 问题：${project.question}")
            appendLine("- 选项是有序等级，从低到高：" + scale.levels.mapIndexed { index, option -> "${index + 1} = ${option.label}" }.joinToString("，"))
            if (retired.isNotEmpty()) appendLine("- 已停用的旧选项（没有等级，不计入平均）：" + retired.joinToString("、") { it.label })
            if (project.times.isNotEmpty()) appendLine("- 每日提醒时间：" + project.times.joinToString("、") { minuteLabel(it) })
            appendLine("- 日期范围：$start 至 $end（$days 天），时区 $zone")
            appendLine("- 「时间」是实际填写时间，日期与时段都按它归组；「已跳过」是我主动跳过，「未处理」是在下一次提醒到来前没有回应，「待处理」是提醒仍未回应。")
            appendLine()
            appendLine("## 概况")
            appendLine("- 回答 ${stats.answered} 次 · 跳过 ${stats.skipped} 次 · 未处理 ${stats.unprocessed} 次 · 待处理 $pending 次" +
                (stats.responseRate?.let { "；回答率 $it%（不含待处理）" } ?: ""))
            stats.mean?.let { mean ->
                append("- 平均等级 ${format(mean)} / ${scale.size}，接近「${scale.nearest(mean).label}」")
                appendLine(previousMean?.let { "；前 $days 天平均 ${format(it)}" } ?: "；前 $days 天没有可比较的回答")
            }
            if (stats.answered > 0) appendLine("- 各选项次数：" + (scale.levels + retired).joinToString("，") { option ->
                val count = stats.options[option.id] ?: 0
                "${option.label} $count 次（${(count * 100f / stats.answered).roundToInt()}%）"
            })
            appendLine("- 各时段（按填写时间）：" + stats.hours.joinToString("，") { band ->
                "${band.label} ${band.total} 次" + (band.mean?.let { "，平均 ${format(it)}" } ?: "")
            })
            appendLine()
            appendLine(if (stats.weekly) "## 每周平均（周一开始）" else "## 每日平均")
            appendLine(if (stats.weekly) "开始日期,结束日期,回答次数,平均等级" else "日期,星期,回答次数,平均等级")
            stats.trend.forEach { point ->
                val first = if (stats.weekly) "${point.start},${point.end}" else "${point.start},${weekday(point.start.toString())}"
                appendLine("$first,${point.total},${point.mean?.let(::format).orEmpty()}")
            }
            appendLine()
            appendLine("## 原始记录")
            if (sorted.size > PROMPT_ROW_LIMIT) appendLine("（共 ${sorted.size} 条，超过 $PROMPT_ROW_LIMIT 条，这里省略逐条记录；需要时可以从 App 导出 CSV 表格。）")
            else {
                appendLine("日期,星期,时间,状态,等级,选项")
                sorted.forEach { record ->
                    val option = record.optionId?.let { id -> project.options.firstOrNull { it.id == id } }
                    appendLine(listOf(record.localDate, weekday(record.localDate), eventTime(record), statusLabels[record.status] ?: record.status,
                        scale.level(record.optionId)?.toString().orEmpty(),
                        if (record.status == SampleStatus.ANSWERED) option?.label ?: "已停用选项" else "").joinToString(",") { cell(it) })
                }
            }
            appendLine()
            appendLine("## 请分析")
            appendLine("1. 整体水平与变化趋势：这段时间总体偏高还是偏低？在变好、变差还是平稳？有没有明显的转折点？")
            appendLine("2. 时间规律：一天中哪些时段、一周中哪几天偏高或偏低？")
            appendLine("3. 波动与异常：哪些日期明显偏离平常？波动大小如何？")
            appendLine("4. 跳过与未处理：它们集中在哪些时段或日期？可能让结论产生什么偏差？")
            appendLine("5. 根据以上发现，给我 2–3 条可以留意或尝试的具体建议，以及接下来值得继续观察的问题。")
            appendLine()
            append("要求：每个结论都引用具体数字；样本较少时说明不确定性；不要做医学或心理诊断；用中文回答。")
        }
    }

    private fun eventTime(record: SampleEntity) = timeLabel(record.answeredAt ?: record.eventAt, record.answeredZoneId ?: record.zoneId)

    private fun cell(value: String) = if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${value.replace("\"", "\"\"")}\"" else value
}
