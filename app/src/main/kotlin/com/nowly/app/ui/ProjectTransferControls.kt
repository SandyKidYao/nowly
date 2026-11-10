package com.nowly.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.nowly.app.projects.ProjectKind

data class ProjectTransferActions(val enabled: Boolean, val importProject: () -> Unit, val exportProject: (String) -> Unit)

/** Launchers live above navigation so file results survive leaving a project screen. */
@Composable
fun projectTransferControls(vm: MvpViewModel): ProjectTransferActions {
    val resolver = LocalContext.current.contentResolver
    val preview by vm.importPreview.collectAsStateWithLifecycle()
    val export by vm.projectExport.collectAsStateWithLifecycle()
    val busy by vm.transferBusy.collectAsStateWithLifecycle()
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readProject { resolver.openInputStream(uri) }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) vm.cancelProjectExport()
        else vm.writeProject { resolver.openOutputStream(uri, "wt") }
    }
    LaunchedEffect(export) {
        export?.let { if (vm.claimExportPicker()) save.launch(it.fileName) }
    }
    preview?.let { candidate ->
        val replacing = candidate.existingName != null
        AlertDialog(
            onDismissRequest = vm::dismissImport,
            title = { Text(if (replacing) "替换已有项目？" else "导入项目？") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(candidate.archive.project.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (candidate.archive.project.kind == ProjectKind.NUMBER)
                        "数字项目${candidate.archive.project.unit.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()} · ${candidate.archive.samples.size} 条历史记录"
                        else "${candidate.archive.options.count { it.active }} 个启用选项 · ${candidate.archive.samples.size} 条历史记录")
                    val date = Instant.ofEpochMilli(candidate.archive.exportedAt).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    Text("导出于 $date", style = MaterialTheme.typography.bodySmall)
                    if (replacing) Text("将整体替换「${candidate.existingName}」及其 ${candidate.existingSamples} 条历史记录，无法撤销。其他项目不受影响。")
                    else Text("将新增一个项目，保留完整设置和全部历史记录。")
                    Text("待处理提醒将静默恢复，过去的提醒不会补发。迁移后请关闭旧 App 的提醒，避免重复通知。")
                    if (busy) Text("正在导入，请稍候…")
                }
            },
            confirmButton = {
                TextButton(onClick = vm::confirmImport, enabled = !busy, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.testTag("confirm-project-import")) {
                    Text(if (replacing) "替换项目" else "导入项目")
                }
            },
            dismissButton = { TextButton(onClick = vm::dismissImport, enabled = !busy, shape = MaterialTheme.shapes.small) { Text("取消") } },
        )
    }
    return ProjectTransferActions(!busy && preview == null && export == null,
        importProject = { open.launch(arrayOf("*/*")) }, exportProject = vm::prepareProjectExport)
}
