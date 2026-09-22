package com.nowly.app.projects

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.stateDescription
import com.nowly.app.R
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nowly.app.data.repository.OfflineProjectRepository
import com.nowly.app.ui.ScreenBar
import com.nowly.app.ui.minuteLabel
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private class EditorState(val id: String, project: RecordProject?) {
    var name by mutableStateOf(project?.name.orEmpty())
    var question by mutableStateOf(project?.question.orEmpty())
    var remindersEnabled by mutableStateOf(project?.remindersEnabled ?: true)
    var options by mutableStateOf(project?.options?.filter { it.active } ?: List(2) { AnswerOption(UUID.randomUUID().toString(), "", it) })
    var times by mutableStateOf(project?.times ?: OfflineProjectRepository.defaultTimes)
    fun draft() = ProjectDraft(id, name, question, true, options, times, remindersEnabled)
    fun move(fromId: String, toId: String): Boolean {
        val from = options.indexOfFirst { it.id == fromId }
        val to = options.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0 || from == to) return false
        options = options.toMutableList().apply { add(to, removeAt(from)) }
        return true
    }
    fun moveBy(id: String, offset: Int): Boolean {
        val from = options.indexOfFirst { it.id == id }
        if (from < 0) return false
        val target = options.getOrNull(from + offset) ?: return false
        return move(id, target.id)
    }
    companion object {
        val Saver = mapSaver(
            save = { state -> mapOf("id" to state.id, "name" to state.name, "question" to state.question,
                "remindersEnabled" to state.remindersEnabled,
                "ids" to ArrayList(state.options.map { it.id }), "labels" to ArrayList(state.options.map { it.label }), "times" to ArrayList(state.times)) },
            restore = { saved -> EditorState(saved["id"] as String, null).apply {
                name = saved["name"] as String; question = saved["question"] as String
                remindersEnabled = saved["remindersEnabled"] as? Boolean ?: true
                val ids = saved["ids"] as List<*>; val labels = saved["labels"] as List<*>
                options = ids.mapIndexed { index, id -> AnswerOption(id as String, labels[index] as String, index) }
                times = (saved["times"] as List<*>).map { it as Int }
            } },
        )
    }
}

@Composable
fun ProjectEditorScreen(id: String, create: Boolean, project: RecordProject?, notificationsAllowed: Boolean,
    preciseAllowed: Boolean, onBack: () -> Unit, onNotificationPermission: () -> Unit, onExactPermission: () -> Unit,
    onSave: suspend (ProjectDraft) -> Unit, onDelete: suspend () -> Unit) {
    if (!create && project == null) { CircularProgressIndicator(); return }
    val state = rememberSaveable(id, saver = EditorState.Saver) { EditorState(id, project) }
    val scroll = rememberLazyListState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val reorderable = rememberReorderableLazyListState(scroll, scrollThreshold = 56.dp) { from, to ->
        if (!saving && state.move(from.key as? String ?: "", to.key as? String ?: "")) {
            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }
    val editingEnabled = !saving && !reorderable.isAnyItemDragging
    BackHandler(enabled = !editingEnabled) { }
    fun moveAccessibly(optionId: String, delta: Int): Boolean {
        if (!editingEnabled || !state.moveBy(optionId, delta)) return false
        // There is one non-reorderable header before the option items.
        val targetIndex = state.options.indexOfFirst { it.id == optionId } + 1
        val layout = scroll.layoutInfo
        val target = layout.visibleItemsInfo.firstOrNull { it.index == targetIndex }
        if (target == null || target.offset < layout.viewportStartOffset ||
            target.offset + target.size > layout.viewportEndOffset) {
            scope.launch { scroll.animateScrollToItem(targetIndex) }
        }
        return true
    }
    fun updateTime(index: Int, minute: Int) {
        if (state.times.withIndex().any { it.index != index && it.value == minute }) { error = "提醒时间不能重复"; return }
        state.times = state.times.toMutableList().also { it[index] = minute }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.imePadding()) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            ScreenBar(if (create) "创建记录" else "编辑", { if (editingEnabled) onBack() })
            LazyColumn(
                modifier = Modifier.weight(1f).testTag("editor-list"), state = scroll,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "project-fields") {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("信息", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(shape = MaterialTheme.shapes.small, value = state.name, onValueChange = { state.name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = editingEnabled)
                        OutlinedTextField(shape = MaterialTheme.shapes.small, value = state.question, onValueChange = { state.question = it }, label = { Text("采样问题") }, modifier = Modifier.fillMaxWidth(), enabled = editingEnabled, minLines = 2)
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("回答选项", style = MaterialTheme.typography.titleMedium)
                    }
                }
                itemsIndexed(state.options, key = { _, option -> option.id }) { index, option ->
                    ReorderableItem(reorderable, option.id, enabled = !saving) { dragging ->
                        val elevation by animateDpAsState(if (dragging) 4.dp else 0.dp, label = "option-drag-elevation")
                        Surface(
                            modifier = Modifier.fillMaxWidth().testTag("option-row-${option.id}"),
                            shape = MaterialTheme.shapes.small,
                            color = if (dragging) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
                            shadowElevation = elevation,
                        ) {
                            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(48.dp)
                                        .draggableHandle(enabled = !saving,
                                            onDragStarted = {
                                                focus.clearFocus()
                                                keyboard?.hide()
                                                haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                            },
                                            onDragStopped = { haptic.performHapticFeedback(HapticFeedbackType.GestureEnd) })
                                        .focusable(enabled = !saving)
                                        .testTag("option-handle-${option.id}")
                                        .semantics {
                                            contentDescription = "调整选项 ${index + 1} 顺序"
                                            stateDescription = "第 ${index + 1} 项，共 ${state.options.size} 项"
                                            if (saving) disabled()
                                            customActions = if (!editingEnabled) emptyList() else buildList {
                                                if (index > 0) add(CustomAccessibilityAction("上移") { moveAccessibly(option.id, -1) })
                                                if (index < state.options.lastIndex) add(CustomAccessibilityAction("下移") { moveAccessibly(option.id, 1) })
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(painterResource(R.drawable.ic_drag_handle), contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                                }
                                OutlinedTextField(
                                    shape = MaterialTheme.shapes.small, value = option.label,
                                    onValueChange = { value -> if (editingEnabled) state.options = state.options.map { if (it.id == option.id) it.copy(label = value) else it } },
                                    label = { Text("选项 ${index + 1}") }, singleLine = true,
                                    enabled = !saving, readOnly = reorderable.isAnyItemDragging,
                                    modifier = Modifier.weight(1f).testTag("option-input-${option.id}"),
                                )
                                TextButton(shape = MaterialTheme.shapes.small,
                                    onClick = { state.options = state.options.filterNot { it.id == option.id } },
                                    enabled = editingEnabled && state.options.size > 2,
                                    modifier = Modifier.size(48.dp).semantics { contentDescription = "移除选项 ${index + 1}" },
                                ) { Text("×") }
                            }
                        }
                    }
                }
                item(key = "sampling-settings") {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        TextButton(shape = MaterialTheme.shapes.small, onClick = { state.options = state.options + AnswerOption(UUID.randomUUID().toString(), "", state.options.size) }, enabled = editingEnabled && state.options.size < 20) { Text("＋ 添加选项") }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("采样提醒", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Switch(state.remindersEnabled, { state.remindersEnabled = it }, enabled = editingEnabled,
                                modifier = Modifier.testTag("reminder-switch").semantics { contentDescription = "提醒开关" })
                        }
                        ReminderTimeline(state.times, enabled = editingEnabled, onChange = ::updateTime, onEnd = { state.times = state.times.sorted() })
                        state.times.forEachIndexed { index, minute ->
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = {
                                    TimePickerDialog(context, { _, hour, min -> updateTime(index, hour * 60 + min); state.times = state.times.sorted() }, minute / 60, minute % 60, true).show()
                                }, enabled = editingEnabled, modifier = Modifier.weight(1f).semantics { contentDescription = "提醒时间 ${index + 1}" }) { Text(minuteLabel(minute)) }
                                TextButton(shape = MaterialTheme.shapes.small, onClick = { state.times = state.times.filterIndexed { i, _ -> i != index } }, enabled = editingEnabled && state.times.size > 1,
                                    modifier = Modifier.size(48.dp).semantics { contentDescription = "移除提醒时间 ${index + 1}" }) { Text("×") }
                            }
                        }
                        TextButton(shape = MaterialTheme.shapes.small, onClick = {
                            val candidate = (1..1440).map { ((state.times.maxOrNull() ?: 480) + it * 15) % 1440 }.firstOrNull { it !in state.times }
                            if (candidate != null) state.times = (state.times + candidate).sorted()
                        }, enabled = editingEnabled && state.times.size < 24) { Text("＋ 添加提醒时间") }
                        if (!notificationsAllowed) {
                            Text("系统通知尚未开启，暂时无法发送提醒。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(shape = MaterialTheme.shapes.small, onClick = onNotificationPermission, enabled = editingEnabled) { Text("开启系统通知") }
                        } else if (!preciseAllowed) {
                            Text("当前提醒可能延迟。允许准时提醒后，系统会尽量按设定时刻触发。", style = MaterialTheme.typography.bodySmall)
                            TextButton(shape = MaterialTheme.shapes.small, onClick = onExactPermission, enabled = editingEnabled) { Text("允许准时提醒") }
                        }
                        if (!create) {
                            HorizontalDivider(Modifier.padding(top = 16.dp))
                            Button(shape = MaterialTheme.shapes.small, onClick = { confirmDelete = true }, enabled = editingEnabled,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError)) { Text("删除") }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.padding(20.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
                    Button(shape = MaterialTheme.shapes.small, onClick = {
                        val draft = state.draft()
                        error = DraftValidation.error(draft)
                        if (error == null && editingEnabled) {
                        saving = true
                        scope.launch {
                            try { onSave(draft) }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { error = "未能保存，请重试。已填写的内容仍保留在此页。" }
                            finally { saving = false }
                        }
                        }
                    }, enabled = editingEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (saving) "正在保存…" else "保存") }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { if (!saving) confirmDelete = false },
        title = { Text("删除「${project?.name.orEmpty()}」？") },
        text = { Text("这会删除「${project?.name.orEmpty()}」及其全部记录，并停止后续提醒。此操作无法撤销。") },
        confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { scope.launch {
            saving = true
            try { onDelete(); confirmDelete = false }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "删除未完成，请重试"; confirmDelete = false }
            finally { saving = false }
        } }, enabled = !saving) { Text("确认删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { confirmDelete = false }, enabled = !saving) { Text("取消") } })
}

@Composable
private fun ReminderTimeline(times: List<Int>, enabled: Boolean, onChange: (Int, Int) -> Unit, onEnd: () -> Unit) {
    val currentTimes by rememberUpdatedState(times)
    val update by rememberUpdatedState(onChange)
    val ended by rememberUpdatedState(onEnd)
    val isEnabled by rememberUpdatedState(enabled)
    var width by remember { mutableFloatStateOf(1f) }
    val primary = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(64.dp).onSizeChanged { width = it.width.toFloat() }.pointerInput(Unit) {
        var selected = -1
        val inset = 12.dp.toPx()
        fun x(minute: Int) = inset + (width - 2 * inset) * minute / 1440f
        detectDragGestures(onDragStart = { position ->
            selected = if (isEnabled) currentTimes.indices.minByOrNull { abs(x(currentTimes[it]) - position.x) } ?: -1 else -1
        }, onDragEnd = { selected = -1; ended() }, onDragCancel = { selected = -1 }, onDrag = { change, _ ->
            if (selected >= 0 && isEnabled) {
                change.consume()
                val minute = (((change.position.x - inset) / (width - 2 * inset) * 1440 / 15).roundToInt() * 15).coerceIn(0, 1439)
                if (currentTimes.withIndex().none { it.index != selected && it.value == minute }) update(selected, minute)
            }
        })
    }.semantics { contentDescription = "每日提醒时间轴；可拖动圆点，也可使用下方时间按钮精确修改" }) {
        val inset = 12.dp.toPx(); val y = size.height / 2
        drawLine(line, Offset(inset, y), Offset(size.width - inset, y), 3.dp.toPx())
        times.forEach { minute -> drawCircle(primary, 7.dp.toPx(), Offset(inset + (size.width - 2 * inset) * minute / 1440f, y)) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf("00", "06", "12", "18", "24").forEach { Text(it, style = MaterialTheme.typography.labelMedium) } }
}
