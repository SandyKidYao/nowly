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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.nowly.app.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(state: HomeUiState, onCreate: () -> Unit, onEdit: (String) -> Unit,
    onStatistics: (String) -> Unit, onAnswer: (String) -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).testTag("projects-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.brand_name), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        Text("此刻", style = MaterialTheme.typography.headlineLarge)
                    }
                    TextButton(shape = MaterialTheme.shapes.small, onClick = onCreate, modifier = Modifier.size(48.dp).semantics { contentDescription = "创建记录" }) {
                        Text("＋", style = MaterialTheme.typography.headlineSmall)
                    }
                }
                Text(LocalDate.now().format(DateTimeFormatter.ofPattern("M 月 d 日，EEEE", Locale.SIMPLIFIED_CHINESE)),
                    Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f).clip(MaterialTheme.shapes.small)
                                        .clickable { onEdit(project.id) }.heightIn(min = 48.dp)
                                        .testTag("project-settings-${project.id}")
                                        .semantics(mergeDescendants = true) { contentDescription = "${project.name}设置" + if (project.remindersEnabled) "" else "，已暂停" },
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text(project.name, style = MaterialTheme.typography.titleLarge,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (!project.remindersEnabled) {
                                            Surface(Modifier.padding(top = 4.dp).testTag("project-paused-${project.id}"),
                                                shape = MaterialTheme.shapes.extraSmall,
                                                color = MaterialTheme.colorScheme.secondaryContainer) {
                                                Text("已暂停", Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                    IconButton(onClick = { onStatistics(project.id) },
                                        modifier = Modifier.size(48.dp).testTag("project-statistics-${project.id}")) {
                                        Icon(painterResource(R.drawable.ic_statistics), "${project.name}统计",
                                            Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Text(project.question, style = MaterialTheme.typography.bodyLarge)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    project.options.filter { it.active }.forEach { option ->
                                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraSmall) {
                                            Text(option.label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                                project.pendingId?.let { id -> Button(shape = MaterialTheme.shapes.small, onClick = { onAnswer(id) }, modifier = Modifier.fillMaxWidth()) { Text("记录当下") } }
                            }
                        }
                    }
                    item { OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onCreate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("＋ 创建记录") } }
                }
            }
        }
    }
}
