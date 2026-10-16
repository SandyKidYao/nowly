package com.nowly.app.statistics

import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nowly.app.R
import com.nowly.app.data.local.SampleEntity
import com.nowly.app.data.local.SampleStatus
import com.nowly.app.projects.AnswerOption
import com.nowly.app.projects.RecordProject
import com.nowly.app.statistics.StatisticsExport.format
import com.nowly.app.ui.MvpViewModel
import com.nowly.app.ui.ScreenBar
import com.nowly.app.ui.minuteLabel
import com.nowly.app.ui.queryState
import com.nowly.app.ui.timeLabel
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Color follows the option's level on the current scale, so reordering options repaints them to match. */
private class ScalePaint(val scale: LevelScale) {
    private val colors = ScaleColors.colors(scale.size)
    fun of(option: AnswerOption): Color = scale.level(option.id)?.let { colors[it - 1] } ?: ScaleColors.retired
    val all: List<Color> get() = colors
}

@Composable
fun StatisticsScreen(id: String, project: RecordProject?, vm: MvpViewModel, onBack: () -> Unit) {
    var start by rememberSaveable(id) { mutableStateOf(LocalDate.now().minusDays(6).toString()) }
    var end by rememberSaveable(id) { mutableStateOf(LocalDate.now().toString()) }
    var filtering by remember { mutableStateOf(false) }
    val days = ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end)) + 1
    val flow = remember(id, start, end) { vm.range(id, start, end) }
    val previousFlow = remember(id, start, end) {
        vm.range(id, LocalDate.parse(start).minusDays(days).toString(), LocalDate.parse(start).minusDays(1).toString())
    }
    val query by queryState(flow)
    val previousQuery by queryState(previousFlow)
    val scale = remember(project?.options) { LevelScale(project?.options.orEmpty()) }
    val paint = remember(scale) { ScalePaint(scale) }
    val records = query?.getOrNull().orEmpty()
    val stats = remember(query, scale) { StatisticsData.from(records, scale, LocalDate.parse(start), LocalDate.parse(end)) }
    val previousMean = remember(previousQuery, scale) { previousQuery?.getOrNull()?.let(scale::mean) }
    val options = scale.levels + project?.options.orEmpty().filter { !it.active && (stats.options[it.id] ?: 0) > 0 }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            ScreenBar("统计", onBack)
            LazyColumn(Modifier.fillMaxSize().testTag("statistics-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(project?.name.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.heightIn(min = 48.dp).testTag("date-range")
                            .clickable(role = Role.Button, onClickLabel = "选择日期范围") { filtering = true },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("$start — $end", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            Icon(painterResource(R.drawable.ic_chevron_forward), null, Modifier.size(16.dp).rotate(90f), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (query == null) item { CircularProgressIndicator() }
                else if (query?.isFailure == true) item { Text("暂时无法读取统计，请返回后重试。") }
                else {
                    item { Overview(stats, scale, paint, previousMean, days) }
                    item {
                        StatSection(if (stats.weekly) "平均趋势 · 按周" else "平均趋势",
                            "折线为每${if (stats.weekly) "周" else "天"}平均等级，虚线为整段平均；下方色条为当${if (stats.weekly) "周" else "天"}各选项占比。") {
                            if (stats.answered == 0) EmptyChart() else {
                                TrendChart(stats, scale, paint)
                                Legend(options, paint)
                            }
                        }
                    }
                    item {
                        StatSection("时段分布", "按实际填写时段分组；右侧为该时段平均等级与回答次数。") {
                            if (stats.answered == 0) EmptyChart() else {
                                stats.hours.forEach { band ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {
                                        contentDescription = "${band.label}：${band.total} 次" + (band.mean?.let { "，平均 ${format(it)}" } ?: "")
                                    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(band.label, Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
                                        StackedBar(band.counts, options, paint, Modifier.weight(1f).height(16.dp))
                                        Text(band.mean?.let(::format) ?: "—", Modifier.width(28.dp), style = MaterialTheme.typography.labelLarge)
                                        Text("${band.total} 次", Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Legend(options, paint)
                            }
                        }
                    }
                    item {
                        StatSection("按选项统计", "只统计已回答记录，跳过和未处理不计入占比分母。") {
                            if (stats.answered == 0) EmptyChart() else options.forEach { option ->
                                val count = stats.options[option.id] ?: 0
                                val ratio = count.toFloat() / stats.answered
                                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(option.label + if (!option.active) "（已停用）" else "", Modifier.weight(1f))
                                        Text("$count 次 · ${(ratio * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                                    }
                                    // A hairline track keeps light levels such as the middle gray readable against the card.
                                    Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp), contentAlignment = Alignment.CenterStart) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 2.dp)
                                        Box(Modifier.fillMaxWidth(ratio).fillMaxHeight().background(paint.of(option), MaterialTheme.shapes.extraSmall))
                                    }
                                }
                            }
                        }
                    }
                    item {
                        val mosaic = remember(records, project?.times) { RecordMosaic.from(records, project?.times.orEmpty(), LocalDate.parse(start), LocalDate.parse(end)) }
                        MosaicSection(mosaic, project?.options.orEmpty(), options, paint, records.isEmpty())
                    }
                    if (project != null) item { ExportSection(project, records, start, end, previousMean, vm) }
                }
            }
        }
    }
    if (filtering) DateRangeDialog(start, end, { filtering = false }) { first, last -> start = first; end = last; filtering = false }
}

@Composable
private fun Overview(stats: StatisticsData, scale: LevelScale, paint: ScalePaint, previousMean: Double?, days: Long) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f).testTag("average-level")) {
                    Text("平均等级", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stats.mean?.let(::format) ?: "—", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                        Text("/ ${scale.size}", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    stats.mean?.let { Text("接近「${scale.nearest(it).label}」", style = MaterialTheme.typography.bodySmall) }
                }
                Column(Modifier.testTag("average-change"), horizontalAlignment = Alignment.End) {
                    Text("较前 $days 天", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val change = if (stats.mean != null && previousMean != null) stats.mean - previousMean else null
                    Text(when {
                        change == null -> "无可比数据"
                        abs(change) < 0.05 -> "持平"
                        change > 0 -> "↑ ${format(change)}"
                        else -> "↓ ${format(-change)}"
                    }, style = if (change == null) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleLarge)
                    previousMean?.let { Text("前期 ${format(it)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (scale.size > 0) ScaleMarker(stats.mean, scale, paint)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric(stats.answered.toString(), "已回答"); Metric(stats.skipped.toString(), "已跳过"); Metric(stats.unprocessed.toString(), "未处理")
                Metric(stats.responseRate?.let { "$it%" } ?: "—", "回答率")
            }
        }
    }
}

/** The scale as colored steps with a tick where the average sits, labelled at both ends. */
@Composable
private fun ScaleMarker(mean: Double?, scale: LevelScale, paint: ScalePaint) {
    val ink = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            val gap = 2.dp.toPx(); val bar = 8.dp.toPx(); val top = (size.height - bar) / 2
            val step = (size.width - gap * (scale.size - 1)) / scale.size
            paint.all.forEachIndexed { index, color ->
                drawRoundRect(color, Offset(index * (step + gap), top), Size(step, bar), CornerRadius(2.dp.toPx()))
            }
            mean?.let {
                // Level k occupies the k-th step; its centre is where an average of exactly k lands.
                val x = ((it - 0.5) / scale.size).toFloat() * size.width
                drawLine(surface, Offset(x, 0f), Offset(x, size.height), 6.dp.toPx(), StrokeCap.Round)
                drawLine(ink, Offset(x, 0f), Offset(x, size.height), 2.5.dp.toPx(), StrokeCap.Round)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(scale.levels.first().label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(scale.levels.last().label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TrendChart(stats: StatisticsData, scale: LevelScale, paint: ScalePaint) {
    val points = stats.trend
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val line = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surface
    val track = MaterialTheme.colorScheme.primaryContainer
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = muted)
    val gridLevels = listOf(1, (scale.size + 1) / 2, scale.size).distinct()
    val gridLabels = gridLevels.associateWith { level -> scale.levels[level - 1].label.let { if (it.length > 6) it.take(6) + "…" else it } }
    // Level names sit in a left gutter so they never collide with the line; the strips and dates below share it.
    val gutter = with(LocalDensity.current) { gridLabels.values.maxOf { measurer.measure(it, labelStyle).size.width }.toDp() } + 8.dp
    val chosen = selected?.let(points::getOrNull)
    val unit = if (stats.weekly) "周" else "天"
    val best = points.filter { it.mean != null }.maxByOrNull { it.mean!! }
    val worst = points.filter { it.mean != null }.minByOrNull { it.mean!! }
    fun label(point: TrendPoint) = if (stats.weekly) "${point.start.toString().substring(5)}–${point.end.toString().substring(5)}"
        else "${point.start.toString().substring(5)} ${StatisticsExport.weekday(point.start.toString())}"
    Text(chosen?.let { point ->
        label(point) + " · " + (point.mean?.let { "平均 ${format(it)}「${scale.nearest(it).label}」· ${point.total} 次" } ?: if (point.total > 0) "${point.total} 次（均为停用选项）" else "没有回答")
    } ?: "点按图表查看每${unit}详情", Modifier.testTag("trend-detail"), style = MaterialTheme.typography.bodySmall,
        color = if (chosen == null) muted else MaterialTheme.colorScheme.onSurface)
    Column(Modifier.fillMaxWidth().testTag("trend-chart")
        .semantics { contentDescription = "平均趋势：" + points.filter { it.mean != null }.joinToString("；") { "${label(it)} 平均 ${format(it.mean!!)}" } }
        .pointerInput(points, gutter) {
            detectTapGestures { offset ->
                val left = gutter.toPx()
                selected = ((offset.x - left) / (size.width - left) * points.size).toInt().coerceIn(points.indices).takeIf { it != selected }
            }
        }) {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val left = gutter.toPx()
            val slot = (size.width - left) / points.size
            val top = 10.dp.toPx(); val bottom = size.height - 10.dp.toPx()
            fun y(level: Double) = if (scale.size <= 1) (top + bottom) / 2 else bottom - ((level - 1) / (scale.size - 1)).toFloat() * (bottom - top)
            fun x(index: Int) = left + (index + 0.5f) * slot
            gridLabels.forEach { (level, text) ->
                val gy = y(level.toDouble())
                drawLine(grid, Offset(left, gy), Offset(size.width, gy), 1.dp.toPx())
                val measured = measurer.measure(text, labelStyle)
                drawText(measured, topLeft = Offset(left - 8.dp.toPx() - measured.size.width, gy - measured.size.height / 2f))
            }
            stats.mean?.let { mean ->
                drawLine(muted.copy(alpha = 0.6f), Offset(left, y(mean)), Offset(size.width, y(mean)), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            }
            chosen?.let { drawLine(grid, Offset(x(selected!!), 0f), Offset(x(selected!!), size.height), 2.dp.toPx()) }
            val present = points.indices.filter { points[it].mean != null }
            present.zipWithNext().forEach { (a, b) ->
                // Empty days between two answered days are bridged with a dashed, lighter segment.
                drawLine(if (b - a == 1) line else line.copy(alpha = 0.45f), Offset(x(a), y(points[a].mean!!)), Offset(x(b), y(points[b].mean!!)),
                    2.dp.toPx(), StrokeCap.Round, if (b - a == 1) null else PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())))
            }
            present.forEach { index ->
                val radius = if (index == selected) 6.dp.toPx() else 4.dp.toPx()
                val center = Offset(x(index), y(points[index].mean!!))
                drawCircle(surface, radius + 2.dp.toPx(), center)
                drawCircle(line, radius, center)
            }
        }
        Canvas(Modifier.fillMaxWidth().padding(start = gutter, top = 8.dp).height(36.dp)) {
            val slot = size.width / points.size
            val width = (slot * 0.6f).coerceIn(2.dp.toPx(), 14.dp.toPx())
            val gap = if (width >= 6.dp.toPx()) 1.5.dp.toPx() else 0f
            points.forEachIndexed { index, point ->
                val left = (index + 0.5f) * slot - width / 2
                if (point.total == 0) { drawRect(track, Offset(left, size.height - 2.dp.toPx()), Size(width, 2.dp.toPx())); return@forEachIndexed }
                val segments = (scale.levels + point.counts.keys.filter { scale.level(it) == null }.map { AnswerOption(it, "", Int.MAX_VALUE, false) })
                    .mapNotNull { option -> point.counts[option.id]?.let { option to it } }
                val usable = size.height - gap * (segments.size - 1)
                var bottom = size.height
                segments.forEach { (option, count) ->
                    val height = usable * count / point.total
                    drawRect(paint.of(option), Offset(left, bottom - height), Size(width, height))
                    bottom -= height + gap
                }
                if (index == selected) drawRect(line, Offset(left, size.height + 1.dp.toPx()), Size(width, 2.dp.toPx()))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = gutter)) {
        Text(points.first().start.toString().substring(5), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = muted)
        Text(points.last().end.toString().substring(5), style = MaterialTheme.typography.labelMedium, color = muted)
    }
    if (best != null && worst != null && best != worst) {
        Text("最高 ${label(best)}（${format(best.mean!!)}） · 最低 ${label(worst)}（${format(worst.mean!!)}）",
            style = MaterialTheme.typography.labelMedium, color = muted)
    }
}

/** 100% stacked bar, lowest level on the left, with a thin surface gap between segments. */
@Composable
private fun StackedBar(counts: Map<String, Int>, options: List<AnswerOption>, paint: ScalePaint, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.primaryContainer
    Canvas(modifier) {
        val segments = options.mapNotNull { option -> counts[option.id]?.takeIf { it > 0 }?.let { option to it } }
        val total = segments.sumOf { it.second }
        if (total == 0) { drawRoundRect(track, cornerRadius = CornerRadius(3.dp.toPx())); return@Canvas }
        val gap = 2.dp.toPx()
        val usable = size.width - gap * (segments.size - 1)
        var left = 0f
        segments.forEach { (option, count) ->
            val width = usable * count / total
            drawRoundRect(paint.of(option), Offset(left, 0f), Size(width, size.height), CornerRadius(3.dp.toPx()))
            left += width + gap
        }
    }
}

@Composable
private fun ExportSection(project: RecordProject, records: List<SampleEntity>, start: String, end: String, previousMean: Double?, vm: MvpViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf(false) }
    val csv = remember(records, project) { StatisticsExport.csv(project, records) }
    val prompt = remember(records, project, previousMean) {
        StatisticsExport.prompt(project, records, start, end, previousMean, ZoneId.systemDefault().id)
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    // The byte-order mark lets spreadsheet apps detect UTF-8 and show Chinese correctly.
                    context.contentResolver.openOutputStream(uri)!!.use { it.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + csv.toByteArray()) }
                }
            }.isSuccess
            vm.errors.emit(if (saved) "CSV 已保存" else "保存失败，请重试")
        }
    }
    fun copy(label: String, text: String, done: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        vm.errors.tryEmit(done)
    }
    val enabled = records.isNotEmpty()
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("导出数据", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Text("${records.size} 条", Modifier.testTag("export-count"), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Copy always sits in the right-hand column; the left column holds the row's own action.
            ExportRow("CSV 表格") {
                ExportButton("保存", enabled, Modifier.testTag("export-csv")) { save.launch(StatisticsExport.fileName(project, start, end)) }
                ExportButton("复制", enabled, Modifier.testTag("copy-csv"), filled = true) { copy("Nowly CSV", csv, "已复制 CSV") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            ExportRow("AI 提示词") {
                ExportButton("预览", enabled, Modifier.testTag("preview-prompt")) { preview = true }
                ExportButton("复制", enabled, Modifier.testTag("copy-prompt"), filled = true) { copy("Nowly 分析提示词", prompt, "已复制提示词") }
            }
        }
    }
    if (preview) AlertDialog(onDismissRequest = { preview = false }, title = { Text("AI 分析提示词") }, text = {
        SelectionContainer(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text(prompt, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("prompt-preview"))
        }
    }, confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { copy("Nowly 分析提示词", prompt, "已复制提示词"); preview = false }) { Text("复制") } },
        dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { preview = false }) { Text("关闭") } })
}

@Composable
private fun ExportRow(title: String, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        actions()
    }
}

@Composable
private fun ExportButton(label: String, enabled: Boolean, modifier: Modifier, filled: Boolean = false, onClick: () -> Unit) {
    val padding = PaddingValues(horizontal = 8.dp)
    if (filled) Button(onClick = onClick, modifier = modifier.width(80.dp), enabled = enabled, shape = MaterialTheme.shapes.small, contentPadding = padding) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier.width(80.dp), enabled = enabled, shape = MaterialTheme.shapes.small, contentPadding = padding) { Text(label) }
}

@Composable
private fun Metric(value: String, label: String) {
    Column { Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary); Text(label, style = MaterialTheme.typography.labelMedium) }
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

private val mosaicLabelWidth = 76.dp

/**
 * Every prompt in the range as a date × reminder-time grid. From the user's side skipped and unprocessed both mean
 * "not answered", and pending and no prompt both mean "nothing yet", so each pair shares one mark.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MosaicSection(mosaic: RecordMosaic, allOptions: List<AnswerOption>, legendOptions: List<AnswerOption>, paint: ScalePaint, empty: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("记录", style = MaterialTheme.typography.titleLarge)
            if (empty || mosaic.columns.isEmpty()) { EmptyChart(); return@Column }
            Legend(legendOptions, paint) {
                val muted = MaterialTheme.colorScheme.onSurfaceVariant; val faint = MaterialTheme.colorScheme.outline
                listOf(SampleStatus.SKIPPED to "未回答", null to "暂无记录").forEach { (status, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Canvas(Modifier.size(10.dp)) { drawMosaicMark(status, null, size.minDimension, muted, faint) }
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                val slot = ((maxWidth - mosaicLabelWidth) / mosaic.columns.size).coerceAtMost(32.dp)
                val rowHeight = slot.coerceAtLeast(20.dp)
                Column {
                    MosaicHeader(mosaic.columns, slot)
                    mosaic.days.forEach { day ->
                        MosaicRow(day, mosaic.columns, slot, rowHeight, allOptions, paint)
                    }
                }
            }
        }
    }
}

@Composable
private fun MosaicHeader(columns: List<Int>, slot: androidx.compose.ui.unit.Dp) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().height(18.dp)) {
        Spacer(Modifier.width(mosaicLabelWidth))
        Canvas(Modifier.width(slot * columns.size).fillMaxHeight()) {
            var lastEnd = Float.NEGATIVE_INFINITY
            columns.forEachIndexed { index, minute ->
                // Labels that would collide with the previous one are dropped; every column keeps its cell.
                val label = if (minute % 60 == 0) "${minute / 60}" else minuteLabel(minute)
                val measured = measurer.measure(label, style)
                val left = (index + 0.5f) * slot.toPx() - measured.size.width / 2f
                if (left > lastEnd + 2.dp.toPx()) {
                    drawText(measured, topLeft = Offset(left, (size.height - measured.size.height) / 2f))
                    lastEnd = left + measured.size.width
                }
            }
        }
    }
}

@Composable
private fun MosaicRow(day: MosaicDay, columns: List<Int>, slot: androidx.compose.ui.unit.Dp, rowHeight: androidx.compose.ui.unit.Dp,
    options: List<AnswerOption>, paint: ScalePaint) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val faint = MaterialTheme.colorScheme.outline
    val date = day.date.toString()
    fun optionOf(sample: SampleEntity) = options.firstOrNull { it.id == sample.optionId }
    fun describe(sample: SampleEntity) = when (sample.status) {
        SampleStatus.ANSWERED -> optionOf(sample)?.label ?: "已停用选项"
        SampleStatus.SKIPPED, SampleStatus.UNPROCESSED -> "未回答"
        else -> null
    }
    Row(Modifier.fillMaxWidth().height(rowHeight).testTag("mosaic-row-$date").semantics(mergeDescendants = true) {
        contentDescription = "${date.substring(5)} ${StatisticsExport.weekday(date)}：" +
            day.cells.mapIndexedNotNull { index, sample -> sample?.let(::describe)?.let { "${minuteLabel(columns[index])} $it" } }.joinToString("，").ifEmpty { "暂无记录" }
    }, verticalAlignment = Alignment.CenterVertically) {
        Text("${date.substring(5)} ${StatisticsExport.weekday(date)}", Modifier.width(mosaicLabelWidth),
            style = MaterialTheme.typography.labelMedium, color = if (day.cells.all { it == null }) muted.copy(alpha = 0.6f) else muted)
        Canvas(Modifier.width(slot * columns.size).fillMaxHeight()) {
            val cell = (slot.toPx() - 4.dp.toPx()).coerceAtMost(22.dp.toPx())
            day.cells.forEachIndexed { index, sample ->
                translate((index + 0.5f) * slot.toPx() - cell / 2, size.height / 2 - cell / 2) {
                    drawMosaicMark(sample?.status, sample?.let { s -> optionOf(s)?.let(paint::of) ?: ScaleColors.retired }, cell, muted, faint)
                }
            }
        }
    }
}

/** One mosaic cell in a size × size box: filled for an answer, a frame when not answered, a dot when there is nothing yet. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMosaicMark(status: String?, fill: Color?, size: Float, muted: Color, faint: Color) {
    val stroke = 1.5.dp.toPx()
    val radius = CornerRadius((size * 0.2f).coerceAtMost(4.dp.toPx()))
    when (status) {
        SampleStatus.ANSWERED -> drawRoundRect(fill ?: ScaleColors.retired, Offset.Zero, Size(size, size), radius)
        SampleStatus.SKIPPED, SampleStatus.UNPROCESSED ->
            drawRoundRect(muted, Offset(stroke / 2, stroke / 2), Size(size - stroke, size - stroke), radius, style = Stroke(stroke))
        else -> drawCircle(faint, (size * 0.12f).coerceAtLeast(1.5.dp.toPx()), Offset(size / 2, size / 2))
    }
}

/** Color chip with a faint ink outline so the lightest levels still read on the card surface. */
@Composable
private fun Swatch(color: Color) {
    Box(Modifier.size(10.dp).background(color, MaterialTheme.shapes.extraSmall)
        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), MaterialTheme.shapes.extraSmall))
}

@Composable private fun EmptyChart() { Text("这个日期范围内还没有回答记录", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(options: List<AnswerOption>, paint: ScalePaint, extra: @Composable () -> Unit = {}) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Swatch(paint.of(option))
            Text(option.label, style = MaterialTheme.typography.labelMedium)
        } }
        extra()
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
