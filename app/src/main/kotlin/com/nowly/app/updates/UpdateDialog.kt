package com.nowly.app.updates

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

/** The one place for the installed version, the automatic-check switch and every step of an update. */
@Composable
fun UpdateDialog(updater: AppUpdater, onDismiss: () -> Unit) {
    val state by updater.state.collectAsStateWithLifecycle()
    val autoCheck by updater.autoCheck.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val release = state.release
    // One tap on "download and install" carries through to the system installer once the file is verified.
    var installWhenReady by rememberSaveable { mutableStateOf(false) }
    fun install(ready: UpdateState.Ready) {
        if (!context.open(AndroidUpdates.installIntent(context, ready.file))) updater.installerUnavailable()
    }
    LaunchedEffect(state) {
        val ready = state as? UpdateState.Ready
        if (ready != null && installWhenReady) { installWhenReady = false; install(ready) }
        if (state is UpdateState.Failed) installWhenReady = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("update-dialog"),
        title = { Text(if (release == null) "版本与更新" else "新版本 ${release.versionName}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("当前版本 ${updater.versionName}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (val current = state) {
                    UpdateState.Idle -> Unit
                    UpdateState.Checking -> Text("正在检查更新…")
                    UpdateState.Current -> Text("已是最新版本。")
                    is UpdateState.Available -> {
                        Text("安装包 ${megabytes(current.release.size)}，下载后由系统确认安装，现有数据会保留。")
                        if (current.release.notes.isNotEmpty()) Text(current.release.notes, Modifier.testTag("update-notes"),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    is UpdateState.Downloading -> {
                        val fraction = (current.received.toFloat() / current.release.size).coerceIn(0f, 1f)
                        val percent = (fraction * 100).toInt()
                        LinearProgressIndicator(progress = { fraction },
                            Modifier.fillMaxWidth().semantics { contentDescription = "下载进度 $percent%" })
                        Text("正在下载 $percent% · ${megabytes(current.release.size)}", style = MaterialTheme.typography.bodySmall)
                    }
                    is UpdateState.Ready -> Text("下载完成并通过校验。安装时系统会请你确认，现有数据会保留。")
                    is UpdateState.Failed -> Text(current.message, color = MaterialTheme.colorScheme.error)
                }
                if (release == null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("自动检查更新")
                        Text("每天最多一次，只读取版本信息，不上传任何数据。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(autoCheck, updater::setAutoCheck,
                        Modifier.testTag("auto-check-switch").semantics { contentDescription = "自动检查更新" })
                }
            }
        },
        confirmButton = {
            when (val current = state) {
                is UpdateState.Available -> DialogButton("下载并安装", "update-download") { installWhenReady = true; updater.download() }
                is UpdateState.Downloading -> DialogButton("取消下载", "update-cancel") { installWhenReady = false; updater.cancelDownload() }
                is UpdateState.Ready -> DialogButton("安装", "update-install") { install(current) }
                is UpdateState.Failed -> if (current.release == null) DialogButton("重新检查", "update-check", onClick = updater::check)
                    else DialogButton("重新下载", "update-download") { installWhenReady = true; updater.download() }
                else -> DialogButton("检查更新", "update-check", enabled = state != UpdateState.Checking, onClick = updater::check)
            }
        },
        dismissButton = {
            when (val current = state) {
                is UpdateState.Available -> {
                    DialogButton("忽略此版本", "update-skip") { updater.skip(); onDismiss() }
                    DialogButton("以后再说", "update-close", onClick = onDismiss)
                }
                is UpdateState.Failed -> {
                    if (current.release != null) DialogButton("打开下载页", "update-page") {
                        context.open(Intent(Intent.ACTION_VIEW, UpdateManifest.DOWNLOAD_PAGE.toUri()))
                    }
                    DialogButton("关闭", "update-close", onClick = onDismiss)
                }
                else -> DialogButton("关闭", "update-close", onClick = onDismiss)
            }
        },
    )
}

@Composable
private fun DialogButton(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.small,
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) { Text(label) }
}

private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)

/** False when no app on the device can handle the request. */
private fun Context.open(intent: Intent): Boolean = runCatching { startActivity(intent) }.isSuccess
