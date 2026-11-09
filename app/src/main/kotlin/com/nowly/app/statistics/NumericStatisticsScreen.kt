package com.nowly.app.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nowly.app.projects.NumericValues
import com.nowly.app.projects.RecordProject
import com.nowly.app.ui.MvpViewModel
import com.nowly.app.ui.ScreenBar
import com.nowly.app.ui.queryState
import com.nowly.app.ui.timeLabel
import java.math.BigDecimal
import java.math.MathContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun NumericStatisticsScreen(project: RecordProject, vm: MvpViewModel, onBack: () -> Unit,
    onRecord: () -> Unit, onEdit: (String) -> Unit, onExport: (() -> Unit)?, transferEnabled: Boolean) {
    var start by rememberSaveable(project.id) { mutableStateOf(LocalDate.now().minusDays(29).toString()) }
    var end by rememberSaveable(project.id) { mutableStateOf(LocalDate.now().toString()) }
    var filtering by remember { mutableStateOf(false) }
    val flow = remember(project.id, start, end) { vm.range(project.id, start, end) }
    val query by queryState(flow)
    val records = query?.getOrNull().orEmpty()
    val summary = remember(records) { NumericSummary.from(records) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            ScreenBar("统计", onBack) { TextButton(onClick = onRecord, shape = MaterialTheme.shapes.small) { Text("记录数字") } }
            LazyColumn(Modifier.fillMaxSize().testTag("statistics-list"), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(project.name, style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { filtering = true }, shape = MaterialTheme.shapes.small, modifier = Modifier.testTag("date-range")) {
                            Text("$start — $end")
                        }
                    }
                }
                when {
                    query == null -> item { CircularProgressIndicator() }
                    query?.isFailure == true -> item { Text("暂时无法读取统计，请返回后重试。") }
                    else -> {
                        item {
                            NumericSection {
                                Text("范围内最近一次", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(summary.latest?.let { NumericValues.withUnit(NumericValues.format(it.value), project.unit) } ?: "—",
                                    style = MaterialTheme.typography.headlineLarge, modifier = Modifier.testTag("numeric-latest"))
                                summary.latest?.let { Text("${it.sample.localDate} ${timeLabel(it.sample.eventAt, it.sample.answeredZoneId ?: it.sample.zoneId)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                val change = summary.change
                                Text(if (change == null) "再记录一次，就能比较变化。" else
                                    "较上次 ${NumericValues.withUnit(NumericValues.signed(change), project.unit)}",
                                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("numeric-change"))
                                if (summary.previous != null) {
                                    val days = Duration.ofMillis(summary.latest!!.sample.eventAt - summary.previous!!.sample.eventAt).toDays()
                                    Text(if (days == 0L) "两次测量间隔不足 1 天" else "两次测量间隔 $days 天", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        item {
                            NumericSection {
                                Text("变化趋势", style = MaterialTheme.typography.titleLarge)
                                Text("连线连接实际测量点，未测日期不补值。", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (summary.points.isEmpty()) Text("还没有测量记录。")
                                else NumericTrend(summary, LocalDate.parse(start), LocalDate.parse(end), project.unit)
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("测量记录", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                Text("${summary.points.size} 次", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (summary.points.isEmpty()) Text("点击「记录数字」开始。", Modifier.padding(top = 12.dp))
                        }
                        items(summary.points.asReversed(), key = { it.sample.id }) { point ->
                            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                                Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "修改这次测量") { onEdit(point.sample.id) }
                                    .padding(16.dp).heightIn(min = 48.dp).testTag("numeric-row-${point.sample.id}"),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(NumericValues.withUnit(NumericValues.format(point.value), project.unit), style = MaterialTheme.typography.titleMedium)
                                        Text("${point.sample.localDate} ${timeLabel(point.sample.eventAt, point.sample.answeredZoneId ?: point.sample.zoneId)}",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text("修改", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                        item { ExportSection(project, records, start, end, null, vm, onExport, transferEnabled) }
                    }
                }
            }
        }
    }
    if (filtering) DateRangeDialog(start, end, { filtering = false }) { first, last -> start = first; end = last; filtering = false }
}

@Composable
private fun NumericSection(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun NumericTrend(summary: NumericSummary, start: LocalDate, end: LocalDate, unit: String) {
    val points = summary.points
    val minimum = points.minOf { it.value }
    val maximum = points.maxOf { it.value }
    val range = maximum.subtract(minimum)
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Text("${NumericValues.format(minimum)} — ${NumericValues.withUnit(NumericValues.format(maximum), unit)}",
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(Modifier.fillMaxWidth().height(180.dp).testTag("numeric-trend").semantics {
        contentDescription = "变化趋势，${points.size} 次测量，范围 ${NumericValues.format(minimum)} 至 ${NumericValues.withUnit(NumericValues.format(maximum), unit)}"
    }) {
        val inset = 8.dp.toPx()
        val width = size.width - inset * 2
        val height = size.height - inset * 2
        val duration = (end.toEpochDay() - start.toEpochDay() + 1).toDouble()
        val offsets = points.map { point ->
            val local = Instant.ofEpochMilli(point.sample.eventAt).atZone(ZoneId.of(point.sample.answeredZoneId ?: point.sample.zoneId))
            val day = LocalDate.parse(point.sample.localDate).toEpochDay() - start.toEpochDay() + local.toLocalTime().toSecondOfDay() / 86400.0
            val y = if (range.signum() == 0) .5f else point.value.subtract(minimum).divide(range, MathContext.DECIMAL64).toFloat()
            Offset(inset + (day / duration).toFloat() * width, inset + (1 - y) * height)
        }.sortedBy { it.x }
        drawLine(outline, Offset(inset, size.height - inset), Offset(size.width - inset, size.height - inset), 1.dp.toPx())
        val path = Path()
        offsets.forEachIndexed { index, at -> if (index == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y) }
        drawPath(path, primary, style = Stroke(2.dp.toPx()))
        offsets.forEach { drawCircle(primary, 3.5.dp.toPx(), it) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(start.toString(), style = MaterialTheme.typography.labelMedium)
        Text(end.toString(), style = MaterialTheme.typography.labelMedium)
    }
}
