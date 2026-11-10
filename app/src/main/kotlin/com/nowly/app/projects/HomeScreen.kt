package com.nowly.app.projects

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.nowly.app.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(state: HomeUiState, onCreate: () -> Unit,
    onStatistics: (String) -> Unit, onAnswer: (String) -> Unit,
    onImport: (() -> Unit)? = null, transferEnabled: Boolean = true, onNumeric: ((String) -> Unit)? = null,
    updateNotice: String? = null, versionLabel: String? = null, onUpdates: (() -> Unit)? = null) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).testTag("projects-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.brand_name), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        Text("此刻", style = MaterialTheme.typography.headlineLarge)
                    }
                    if (onImport != null) TextButton(onClick = onImport, enabled = transferEnabled,
                        shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp).testTag("import-project")) { Text("导入项目") }
                    TextButton(shape = MaterialTheme.shapes.small, onClick = onCreate, modifier = Modifier.size(48.dp).semantics { contentDescription = "创建项目" }) {
                        Text("＋", style = MaterialTheme.typography.headlineSmall)
                    }
                }
                Text(LocalDate.now().format(DateTimeFormatter.ofPattern("M 月 d 日，EEEE", Locale.SIMPLIFIED_CHINESE)),
                    Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (updateNotice != null && onUpdates != null) item(key = "update-notice") {
                Surface(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
                    .clickable(role = Role.Button, onClickLabel = "查看更新", onClick = onUpdates).testTag("update-notice"),
                    shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(updateNotice, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text("查看", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            when (state) {
                HomeUiState.Loading -> item { val description = stringResource(R.string.home_loading); CircularProgressIndicator(Modifier.semantics { contentDescription = description }) }
                HomeUiState.ReadError -> item { Text(stringResource(R.string.home_load_error)) }
                is HomeUiState.Ready -> {
                    if (state.projects.isEmpty()) item {
                        Column(Modifier.padding(vertical = 64.dp)) {
                            Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.home_empty_description), Modifier.padding(top = 12.dp))
                        }
                    }
                    items(state.projects, key = { it.id }) { project ->
                        // The card is one target for statistics; the record buttons inside keep their own action.
                        Card(Modifier.fillMaxWidth().testTag("project-card-${project.id}").clip(MaterialTheme.shapes.large)
                            .clickable(role = Role.Button, onClickLabel = "查看统计") { onStatistics(project.id) },
                            shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(project.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleLarge,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (!project.remindersEnabled) {
                                        Surface(Modifier.testTag("project-paused-${project.id}"),
                                            shape = MaterialTheme.shapes.extraSmall,
                                            color = MaterialTheme.colorScheme.secondaryContainer) {
                                            Text("已暂停", Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                Text(project.question, style = MaterialTheme.typography.bodyLarge)
                                if (project.kind == ProjectKind.NUMBER) Text("数字项目" + if (project.unit.isEmpty()) "" else " · ${project.unit}",
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    project.options.filter { it.active }.forEach { option ->
                                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraSmall) {
                                            Text(option.label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                                if (project.kind == ProjectKind.NUMBER && onNumeric != null) Button(shape = MaterialTheme.shapes.small,
                                    onClick = { onNumeric(project.id) }, modifier = Modifier.fillMaxWidth().testTag("record-number-${project.id}")) { Text("记录数字") }
                                else project.pendingId?.let { id -> Button(shape = MaterialTheme.shapes.small, onClick = { onAnswer(id) }, modifier = Modifier.fillMaxWidth()) { Text("记录当下") } }
                            }
                        }
                    }
                    item { OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onCreate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("＋ 创建项目") } }
                    if (versionLabel != null) item(key = "version") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (onUpdates == null) Text(versionLabel, Modifier.padding(vertical = 12.dp),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else TextButton(onClick = onUpdates, shape = MaterialTheme.shapes.small,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("version-updates")) {
                                Text("$versionLabel · 检查更新", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
