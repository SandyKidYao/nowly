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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.nowly.app.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(state: HomeUiState, onCreate: () -> Unit, onOpen: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit, onAnswer: (String) -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).testTag("projects-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.brand_name), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        Text("此刻", style = MaterialTheme.typography.headlineLarge)
                    }
                    TextButton(shape = MaterialTheme.shapes.small, onClick = onCreate, modifier = Modifier.size(48.dp).semantics { contentDescription = "创建记录项目" }) {
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(project.name + " ›", Modifier.weight(1f).clickable { onOpen(project.id) }.padding(vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
                                    Switch(project.remindersEnabled, { onToggle(project.id, it) }, modifier = Modifier.semantics { contentDescription = "${project.name}提醒" })
                                }
                                Text(project.question)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    project.options.filter { it.active }.forEach { option ->
                                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraSmall) {
                                            Text(option.label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                                Text("每天 ${project.times.size} 次 · 手动时间点", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                project.pendingId?.let { id -> Button(shape = MaterialTheme.shapes.small, onClick = { onAnswer(id) }, modifier = Modifier.fillMaxWidth()) { Text("记录当下") } }
                            }
                        }
                    }
                    item { OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onCreate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("＋ 创建记录项目") } }
                }
            }
        }
    }
}
