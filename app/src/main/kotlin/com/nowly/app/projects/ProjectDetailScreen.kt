package com.nowly.app.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nowly.app.data.local.SampleStatus
import com.nowly.app.ui.MvpViewModel
import com.nowly.app.ui.ScreenBar
import com.nowly.app.ui.queryState
import com.nowly.app.ui.timeLabel
import java.time.LocalDate

@Composable
fun ProjectDetailScreen(id: String, project: RecordProject?, vm: MvpViewModel, onBack: () -> Unit,
    onEdit: () -> Unit, onStatistics: () -> Unit, onAnswer: (String) -> Unit) {
    var limit by rememberSaveable(id) { mutableIntStateOf(30) }
    val flow = remember(id, limit) { vm.history(id, limit) }
    val query by queryState(flow, retainPrevious = true)
    val records = query?.getOrNull().orEmpty()
    val list = rememberLazyListState()
    LaunchedEffect(list, records.size, limit) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (records.size == limit && last >= records.size - 3) limit += 30
        }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            ScreenBar(project?.name ?: "记录项目", onBack) { TextButton(onClick = onEdit, enabled = project != null) { Text("设置") } }
            LazyColumn(Modifier.fillMaxSize().testTag("history-list"), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("采样记录", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = onStatistics) { Text("查看统计") }
                    }
                    Text("向下滑动查看更早记录", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                if (query == null) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
                else if (query?.isFailure == true) item { Text("暂时无法读取记录，请返回后重试。", Modifier.padding(vertical = 24.dp)) }
                else if (records.none { it.localDate == LocalDate.now().toString() }) item {
                    Text("今天", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
                    Text("今天还没有采样记录。", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                itemsIndexed(records, key = { _, it -> it.id }) { index, record ->
                    if (index == 0 || records[index - 1].localDate != record.localDate) {
                        Text(if (record.localDate == LocalDate.now().toString()) "今天 · ${record.localDate}" else record.localDate,
                            Modifier.padding(top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
                    }
                    val pending = record.status == SampleStatus.PENDING
                    val outcome = when (record.status) {
                        SampleStatus.ANSWERED -> "已回答"
                        SampleStatus.SKIPPED -> "已跳过"
                        SampleStatus.UNPROCESSED -> "未处理"
                        else -> "待处理"
                    }
                    Row(Modifier.fillMaxWidth().then(if (pending) Modifier.clickable { onAnswer(record.id) } else Modifier).padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(timeLabel(record.eventAt, record.answeredZoneId ?: record.zoneId), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text(if (record.status == SampleStatus.ANSWERED) project?.options?.firstOrNull { it.id == record.optionId }?.label ?: "已停用选项" else outcome,
                                style = MaterialTheme.typography.titleMedium)
                            Text(when (record.status) {
                                SampleStatus.ANSWERED -> "${timeLabel(record.plannedAt, record.zoneId)} 提醒 · ${timeLabel(record.answeredAt!!, record.answeredZoneId ?: record.zoneId)} 填写"
                                SampleStatus.SKIPPED -> "用户主动跳过"
                                SampleStatus.UNPROCESSED -> "已被后续提醒替换"
                                else -> "点击记录当下"
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (pending) "回答 ›" else outcome, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
                if (query?.isSuccess == true) item {
                    Text(if (records.size < limit) "没有更早的记录了" else "继续向下滑动加载历史",
                        Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
