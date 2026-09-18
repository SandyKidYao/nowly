package com.nowly.app.statistics

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nowly.app.projects.AnswerOption
import com.nowly.app.projects.RecordProject
import com.nowly.app.ui.MvpViewModel
import com.nowly.app.ui.ScreenBar
import com.nowly.app.ui.queryState
import java.time.LocalDate
import kotlin.math.roundToInt

private val palette = listOf(Color(0xFFDCE3D8), Color(0xFFBCCAB8), Color(0xFF99AD95), Color(0xFF728D70), Color(0xFF496548))
private fun optionColor(option: AnswerOption) = palette[Math.floorMod(option.colorIndex, palette.size)]

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatisticsScreen(id: String, project: RecordProject?, vm: MvpViewModel, onBack: () -> Unit) {
    var start by rememberSaveable(id) { mutableStateOf(LocalDate.now().minusDays(6).toString()) }
    var end by rememberSaveable(id) { mutableStateOf(LocalDate.now().toString()) }
    var filtering by remember { mutableStateOf(false) }
    val flow = remember(id, start, end) { vm.range(id, start, end) }
    val query by queryState(flow)
    val stats = remember(query) { StatisticsData.from(query?.getOrNull().orEmpty()) }
    val options = project?.options.orEmpty().filter { it.active || (stats.options[it.id] ?: 0) > 0 }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            ScreenBar("项目统计", onBack) { TextButton(shape = MaterialTheme.shapes.small, onClick = { filtering = true }) { Text("筛选") } }
            LazyColumn(Modifier.fillMaxSize().testTag("statistics-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item {
                    Text(project?.name.orEmpty(), style = MaterialTheme.typography.titleLarge)
                    Text("$start — $end", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (query == null) item { CircularProgressIndicator() }
                else if (query?.isFailure == true) item { Text("暂时无法读取统计，请返回后重试。") }
                else {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Metric(stats.answered, "已回答"); Metric(stats.skipped, "已跳过"); Metric(stats.unprocessed, "未处理")
                        }
                    }
                    item {
                        StatSection("日期趋势", "按实际填写日期展示选项占比；柱上为回答次数。") {
                            if (stats.answered == 0) EmptyChart() else {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                                    items(stats.dates, key = { it.label }) { day ->
                                        Column(Modifier.width(44.dp).semantics { contentDescription = "${day.label}：${day.total} 次回答" }, horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(day.total.toString(), style = MaterialTheme.typography.labelMedium)
                                            Column(Modifier.width(28.dp).height(128.dp).padding(vertical = 8.dp)) {
                                                options.filter { (day.counts[it.id] ?: 0) > 0 }.asReversed().forEach { option ->
                                                    Box(Modifier.fillMaxWidth().weight(day.counts.getValue(option.id).toFloat()).background(optionColor(option)))
                                                }
                                            }
                                            Text(day.label.substring(5), style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                                Text("仅展示有回答的日期", style = MaterialTheme.typography.labelMedium)
                                Legend(options)
                            }
                        }
                    }
                    item {
                        StatSection("时段分布", "按实际填写时段分组，不按原定提醒时刻。") {
                            if (stats.answered == 0) EmptyChart() else {
                                stats.hours.forEach { band ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(band.label, Modifier.width(52.dp), style = MaterialTheme.typography.labelMedium)
                                        Row(Modifier.weight(1f).height(18.dp).background(MaterialTheme.colorScheme.primaryContainer)) {
                                            options.filter { (band.counts[it.id] ?: 0) > 0 }.forEach { option ->
                                                Box(Modifier.fillMaxHeight().weight(band.counts.getValue(option.id).toFloat()).background(optionColor(option)))
                                            }
                                        }
                                        Text("${band.total} 次", Modifier.width(46.dp), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                Legend(options)
                            }
                        }
                    }
                    item {
                        StatSection("按选项统计", "只统计已回答记录，跳过和未处理不计入占比分母。") {
                            if (stats.answered == 0) EmptyChart() else options.forEach { option ->
                                val count = stats.options[option.id] ?: 0
                                val ratio = count.toFloat() / stats.answered
                                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(option.label + if (!option.active) "（已停用）" else "", Modifier.weight(1f))
                                        Text("$count 次 · ${(ratio * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp).background(MaterialTheme.colorScheme.primaryContainer)) {
                                        Box(Modifier.fillMaxWidth(ratio).fillMaxHeight().background(optionColor(option)))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (filtering) DateRangeDialog(start, end, { filtering = false }) { first, last -> start = first; end = last; filtering = false }
}

@Composable
private fun Metric(value: Int, label: String) {
    Column { Text(value.toString(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary); Text(label, style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun StatSection(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable private fun EmptyChart() { Text("这个日期范围内还没有回答记录", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(options: List<AnswerOption>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(8.dp).background(optionColor(option)))
            Text(option.label, style = MaterialTheme.typography.labelMedium)
        } }
    }
}

@Composable
private fun DateRangeDialog(start: String, end: String, onDismiss: () -> Unit, onApply: (String, String) -> Unit) {
    var first by rememberSaveable { mutableStateOf(start) }
    var last by rememberSaveable { mutableStateOf(end) }
    val context = LocalContext.current
    fun choose(value: String, update: (String) -> Unit) {
        val date = LocalDate.parse(value)
        DatePickerDialog(context, { _, year, month, day -> update(LocalDate.of(year, month + 1, day).toString()) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择日期范围") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 30).forEach { days -> TextButton(shape = MaterialTheme.shapes.small, onClick = { last = LocalDate.now().toString(); first = LocalDate.now().minusDays(days - 1L).toString() }) { Text("最近 $days 天") } }
            }
            Text("自定义日期", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { choose(first) { first = it } }, modifier = Modifier.fillMaxWidth()) { Text("开始：$first") }
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { choose(last) { last = it } }, modifier = Modifier.fillMaxWidth()) { Text("结束：$last") }
            if (first > last) Text("开始日期不能晚于结束日期", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { onApply(first, last) }, enabled = first <= last) { Text("应用筛选") } },
        dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = onDismiss) { Text("取消") } })
}
