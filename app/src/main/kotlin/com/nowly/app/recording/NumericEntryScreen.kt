package com.nowly.app.recording

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nowly.app.data.local.SampleEntity
import com.nowly.app.data.local.SampleStatus
import com.nowly.app.projects.*
import com.nowly.app.ui.MvpViewModel
import com.nowly.app.ui.ScreenBar
import com.nowly.app.ui.queryState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun NumericEntryRoute(projectId: String, editingId: String?, vm: MvpViewModel, onClose: () -> Unit) {
    val state by vm.projects.collectAsStateWithLifecycle()
    val project = (state as? HomeUiState.Ready)?.projects?.firstOrNull { it.id == projectId }
    val flow = remember(editingId) { vm.sample(editingId.orEmpty()) }
    val query by queryState(flow)
    val sample = query?.getOrNull()
    var loaded by remember(editingId) { mutableStateOf<SampleEntity?>(null) }
    LaunchedEffect(editingId, sample) { if (loaded == null && sample?.numericValue != null) loaded = sample }
    if (project?.kind == ProjectKind.NUMBER && (editingId == null || loaded != null)) {
        NumericEntryScreen(project, vm, onClose, editing = if (editingId == null) null else loaded)
    } else {
        Scaffold { insets -> Column(Modifier.fillMaxSize().padding(insets)) {
            ScreenBar("数字记录", onClose)
            if (state == HomeUiState.Loading || (editingId != null && query == null)) CircularProgressIndicator(Modifier.padding(24.dp))
            else Text("记录暂时无法读取或已更新，请返回后重新打开。", Modifier.padding(24.dp))
        } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NumericEntryScreen(project: RecordProject, vm: MvpViewModel, onClose: () -> Unit,
    pendingId: String? = null, editing: SampleEntity? = null, pendingStatus: String? = null) {
    val original = remember(project.id, editing?.id, pendingId) { editing }
    var value by rememberSaveable(project.id, editing?.id, pendingId) { mutableStateOf(editing?.numericValue.orEmpty()) }
    var selectedAt by rememberSaveable(project.id, editing?.id, pendingId) { mutableLongStateOf(editing?.eventAt ?: vm.container.clock.millis()) }
    var customTime by rememberSaveable(project.id, editing?.id, pendingId) { mutableStateOf(editing != null) }
    val zoneId = rememberSaveable(project.id, editing?.id, pendingId) { editing?.answeredZoneId ?: ZoneId.systemDefault().id }
    val entryId = rememberSaveable(project.id, editing?.id, pendingId) { UUID.randomUUID().toString() }
    // Keep the reminder identity through recreation and retries; never turn a submitted reminder answer into a second manual row.
    val boundPendingId by rememberSaveable(project.id, editing?.id, pendingId) { mutableStateOf(pendingId ?: project.pendingId) }
    val zone = ZoneId.of(zoneId)
    val local = Instant.ofEpochMilli(selectedAt).atZone(zone)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val latestFlow = remember(project.id, editing?.id) { vm.latestNumeric(project.id, editing?.id.orEmpty()) }
    val latest by queryState(latestFlow)
    BackHandler(enabled = saving) { }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.imePadding()) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            ScreenBar(if (editing == null) "记录数字" else "修改记录", { if (!saving) onClose() })
            if (pendingId != null && pendingStatus != null && pendingStatus != SampleStatus.PENDING && !saving) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("这次提醒已结束，请返回后重新打开记录。")
                    TextButton(onClick = onClose, shape = MaterialTheme.shapes.small) { Text("返回") }
                }
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(project.name, color = MaterialTheme.colorScheme.primary)
                Text(project.question, style = MaterialTheme.typography.headlineMedium)
                OutlinedTextField(value = value, onValueChange = { value = it; error = null }, label = { Text("数值") },
                    suffix = { if (project.unit.isNotEmpty()) Text(project.unit) }, singleLine = true, enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().testTag("numeric-value"))
                latest?.getOrNull()?.let { previous ->
                    Text("最近记录：${NumericValues.withUnit(previous.numericValue.orEmpty(), project.unit)} · ${previous.localDate}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("测量时间", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val pickerLocal = Instant.ofEpochMilli(if (customTime) selectedAt else vm.container.clock.millis()).atZone(zone)
                        DatePickerDialog(context, { _, year, month, day ->
                            selectedAt = LocalDate.of(year, month + 1, day).atTime(pickerLocal.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
                            customTime = true
                        }, pickerLocal.year, pickerLocal.monthValue - 1, pickerLocal.dayOfMonth).apply {
                            datePicker.maxDate = vm.container.clock.millis()
                        }.show()
                    }, enabled = !saving, shape = MaterialTheme.shapes.small, modifier = Modifier.testTag("numeric-date")) { Text(if (customTime) local.toLocalDate().toString() else "今天") }
                    OutlinedButton(onClick = {
                        val pickerLocal = Instant.ofEpochMilli(if (customTime) selectedAt else vm.container.clock.millis()).atZone(zone)
                        TimePickerDialog(context, { _, hour, minute ->
                            selectedAt = pickerLocal.toLocalDate().atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
                            customTime = true
                        }, pickerLocal.hour, pickerLocal.minute, true).show()
                    }, enabled = !saving, shape = MaterialTheme.shapes.small, modifier = Modifier.testTag("numeric-time")) {
                        Text(if (customTime) local.format(DateTimeFormatter.ofPattern("HH:mm")) else "现在")
                    }
                }
                if (editing == null && customTime) {
                    Text("补记独立保存，不会完成当前待处理提醒。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { selectedAt = vm.container.clock.millis(); customTime = false }, enabled = !saving,
                        shape = MaterialTheme.shapes.small) { Text("改为现在记录") }
                }
                if (editing == null && !customTime && boundPendingId == null && project.pendingId != null) Text(
                    "填写期间有新提醒到达，本次主动记录将独立保存。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("numeric-error")) }
                Button(onClick = {
                    if (NumericValues.normalize(value) == null) { error = NumericValues.ERROR; return@Button }
                    val measuredAt = if (customTime) selectedAt else vm.container.clock.millis()
                    if (measuredAt > vm.container.clock.millis()) { error = "测量时间不能晚于现在"; return@Button }
                    saving = true; error = null
                    scope.launch {
                        try {
                            val pending = if (editing == null && !customTime) boundPendingId else null
                            if (vm.saveNumeric(project.id, value, measuredAt, if (customTime) zone else ZoneId.systemDefault(), entryId, pending, original)) onClose()
                            else error = "记录或提醒已更新，请返回后重新打开。输入内容仍保留在此页。"
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { error = "尚未保存，请重试。输入内容仍保留在此页。" }
                        finally { saving = false }
                    }
                }, enabled = !saving, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("numeric-save")) {
                    Text(if (saving) "正在保存…" else if (editing == null) "保存记录" else "保存修改")
                }
                if (original != null) TextButton(onClick = { deleting = true }, enabled = !saving,
                    shape = MaterialTheme.shapes.small, modifier = Modifier.testTag("numeric-delete")) {
                    Text("删除这条记录", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (deleting && original != null) AlertDialog(onDismissRequest = { if (!saving) deleting = false },
        title = { Text("删除这条记录？") }, text = { Text("将删除 ${original.localDate} 的 ${NumericValues.withUnit(original.numericValue.orEmpty(), project.unit)}。此操作无法撤销。") },
        confirmButton = { TextButton(onClick = {
            saving = true
            scope.launch {
                try {
                    if (vm.deleteNumeric(original)) onClose() else error = "记录已更新，请返回后重新打开。"
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { error = "删除未完成，请重试。" }
                finally { saving = false; deleting = false }
            }
        }, enabled = !saving, shape = MaterialTheme.shapes.small, modifier = Modifier.testTag("confirm-numeric-delete")) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { deleting = false }, enabled = !saving, shape = MaterialTheme.shapes.small) { Text("取消") } })
}
