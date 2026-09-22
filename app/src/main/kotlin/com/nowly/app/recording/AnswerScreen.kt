package com.nowly.app.recording

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nowly.app.data.local.SampleStatus
import com.nowly.app.projects.HomeUiState
import com.nowly.app.ui.MvpViewModel
import com.nowly.app.ui.ScreenBar
import com.nowly.app.ui.queryState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AnswerRoute(initialId: String, vm: MvpViewModel, onClose: () -> Unit) {
    var id by rememberSaveable(initialId) { mutableStateOf(initialId) }
    val state by vm.projects.collectAsStateWithLifecycle()
    val flow = remember(id) { vm.sample(id) }
    val result by queryState(flow)
    val sample = result?.getOrNull()
    val project = (state as? HomeUiState.Ready)?.projects?.firstOrNull { it.id == sample?.projectId }
    var saving by remember { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = saving) { }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            ScreenBar("记录此刻", { if (!saving) onClose() })
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (result == null || state == HomeUiState.Loading) {
                    CircularProgressIndicator()
                } else if (result?.isFailure == true || state == HomeUiState.ReadError) {
                    Text("暂时无法读取这次提醒，请返回后重试。")
                } else if (sample == null || project == null) {
                    Text("这条记录已不存在。")
                    TextButton(onClick = onClose) { Text("返回") }
                } else if (sample.status != SampleStatus.PENDING && !saving) {
                    Text("这次提醒已结束。", style = MaterialTheme.typography.headlineSmall)
                    if (project.pendingId != null && project.pendingId != id) {
                        Text("已有新的提醒，可以继续记录现在的状态。")
                        Button(onClick = { id = project.pendingId }) { Text("记录最新提醒") }
                    } else TextButton(onClick = onClose) { Text("返回") }
                } else {
                    Text(project.name, color = MaterialTheme.colorScheme.primary)
                    Text(project.question, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 27.sp, lineHeight = 41.sp), modifier = Modifier.padding(vertical = 16.dp))
                    project.options.filter { it.active }.forEach { option ->
                        OutlinedButton(onClick = {
                            // Capture the answer time at the tap, before any suspension or database work.
                            val tappedAt = vm.container.clock.millis()
                            val tappedZone = java.time.ZoneId.systemDefault()
                            if (!saving) {
                            saving = true; error = null
                            scope.launch {
                                try {
                                    if (vm.answer(id, option.id, tappedAt, tappedZone)) onClose()
                                    else error = "提醒或选项已更新，请查看当前状态后重试。"
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (_: Exception) { error = "尚未保存，请重试。" }
                                finally { saving = false }
                            }
                            }
                        }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("answer-${option.id}"),
                            shape = MaterialTheme.shapes.medium, colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)) {
                            Text(option.label, fontSize = 15.sp)
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (saving) Text("正在保存…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
