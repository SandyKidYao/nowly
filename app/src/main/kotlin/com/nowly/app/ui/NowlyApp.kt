package com.nowly.app.ui

import android.Manifest
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.nowly.app.AppContainer
import com.nowly.app.projects.*
import com.nowly.app.recording.AnswerRoute
import com.nowly.app.statistics.StatisticsScreen
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable private data object Home : NavKey
@Serializable private data class Detail(val id: String) : NavKey
@Serializable private data class Editor(val id: String, val create: Boolean) : NavKey
@Serializable private data class Statistics(val id: String) : NavKey
@Serializable private data class Answer(val id: String) : NavKey

@Composable
fun NowlyApp(container: AppContainer) {
    val vm = viewModel { MvpViewModel(container) }
    val state by vm.projects.collectAsStateWithLifecycle()
    val projects = (state as? HomeUiState.Ready)?.projects.orEmpty()
    val backStack = rememberNavBackStack(Home)
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var permissionsVersion by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionsVersion++; vm.refresh() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { permissionsVersion++; vm.refresh() } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(vm) { vm.errors.collect { snackbars.showSnackbar(it) } }
    LaunchedEffect(state is HomeUiState.Ready) {
        if (state is HomeUiState.Ready && Build.VERSION.SDK_INT >= 33 && !container.reminderGateway.canNotify() && container.repository.claimFlag("notification_prompted")) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val notificationsAllowed = remember(permissionsVersion) { container.reminderGateway.canNotify() }
    val preciseAllowed = remember(permissionsVersion) { container.reminderGateway.canSchedulePrecisely() }
    fun back() { if (backStack.size > 1) backStack.removeLastOrNull() }
    Box(Modifier.fillMaxSize()) {
        NavDisplay(backStack = backStack, onBack = { back() }, entryProvider = entryProvider {
            entry<Home> {
                HomeScreen(state, { backStack.add(Editor(UUID.randomUUID().toString(), true)) },
                    { backStack.add(Detail(it)) }, vm::enable, { backStack.add(Answer(it)) })
            }
            entry<Detail> { route ->
                ProjectDetailScreen(route.id, projects.firstOrNull { it.id == route.id }, vm, ::back,
                    { backStack.add(Editor(route.id, false)) }, { backStack.add(Statistics(route.id)) }, { backStack.add(Answer(it)) })
            }
            entry<Editor> { route ->
                ProjectEditorScreen(route.id, route.create, projects.firstOrNull { it.id == route.id },
                    notificationsAllowed, preciseAllowed, onBack = ::back,
                    onNotificationPermission = {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    },
                    onExactPermission = {
                        if (Build.VERSION.SDK_INT >= 31) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
                    },
                    onSave = { draft ->
                        vm.save(draft)
                        back()
                        if (route.create) backStack.add(Detail(draft.id))
                    },
                    onDelete = {
                        vm.delete(route.id)
                        while (backStack.size > 1) backStack.removeLastOrNull()
                    })
            }
            entry<Statistics> { route -> StatisticsScreen(route.id, projects.firstOrNull { it.id == route.id }, vm, ::back) }
            entry<Answer> { route -> AnswerRoute(route.id, vm, ::back) }
        })
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}
