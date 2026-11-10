package com.nowly.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nowly.app.projects.HomeScreen
import com.nowly.app.projects.HomeUiState
import com.nowly.app.ui.theme.NowlyTheme
import com.nowly.app.updates.AppUpdater
import com.nowly.app.updates.UpdateDialog
import com.nowly.app.updates.UpdateSettings
import com.nowly.app.updates.UpdateState
import com.nowly.app.updates.UpdateTransport
import com.nowly.app.updates.release
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The update entry on the home screen and its dialog, driven by a stand-in for the network. */
@RunWith(AndroidJUnit4::class)
class UpdateDialogTest {
    @get:Rule val compose = createComposeRule()

    private class Settings : UpdateSettings {
        override var autoCheck = true
        override var nextCheckAt = 0L
        override var skippedVersion = 0
    }

    private class Transport(var manifest: String?) : UpdateTransport {
        override fun text(url: String, limit: Int): String = manifest ?: throw IOException("offline")
        override fun download(url: String, target: File, limit: Long, onBytes: (Long) -> Unit): Unit = throw IOException("offline")
    }

    private val settings = Settings()
    private val transport = Transport(null)
    private val updater = AppUpdater(true, 8, "0.2.6", settings, transport,
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "update-dialog-test"),
        CoroutineScope(Dispatchers.Unconfined)) { null }

    private fun show() = compose.setContent {
        NowlyTheme {
            val state by updater.state.collectAsStateWithLifecycle()
            val skipped by updater.skipped.collectAsStateWithLifecycle()
            var open by androidx.compose.runtime.remember { mutableStateOf(false) }
            HomeScreen(HomeUiState.Ready(emptyList()), onCreate = {}, onStatistics = {}, onAnswer = {},
                updateNotice = state.release?.takeIf { it.versionCode != skipped }?.let { "新版本 ${it.versionName} 可用" },
                versionLabel = "此刻 ${updater.versionName}", onUpdates = { open = true })
            if (open) UpdateDialog(updater) { open = false; updater.acknowledge() }
        }
    }

    @Test fun manualCheckReportsFailureAndTheLatestVersionAndKeepsTheSwitch() {
        show()
        compose.onNodeWithTag("update-notice").assertDoesNotExist()
        compose.onNodeWithTag("version-updates").assertTextEquals("此刻 0.2.6 · 检查更新").performClick()
        compose.onNodeWithText("版本与更新").assertIsDisplayed()
        compose.onNodeWithText("当前版本 0.2.6").assertIsDisplayed()
        compose.onNodeWithTag("auto-check-switch").assertIsOn().performClick()
        compose.onNodeWithTag("auto-check-switch").assertIsOff()
        assertFalse(settings.autoCheck)
        compose.onNodeWithTag("update-check").performClick()
        compose.onNodeWithText("暂时无法检查更新，请检查网络后重试。").assertIsDisplayed()
        transport.manifest = """{"tag":"v0.2.6","versionCode":8,"versionName":"0.2.6","apkUrl":"https://github.com/SandyKidYao/nowly/releases/download/v0.2.6/nowly.apk","sha256":"${"0".repeat(64)}","size":1048576}"""
        compose.onNodeWithText("重新检查").performClick()
        compose.onNodeWithText("已是最新版本。").assertIsDisplayed()
        compose.onNodeWithTag("update-close").performClick()
        compose.onNodeWithTag("update-dialog").assertDoesNotExist()
        // A result that only answered the manual check does not linger.
        assertEquals(UpdateState.Idle, updater.state.value)
    }

    @Test fun newReleaseIsAnnouncedOnHomeAndCanBeRetriedOrSkipped() {
        transport.manifest = """{"tag":"v0.3.0","versionCode":9,"versionName":"0.3.0","apkUrl":"https://github.com/SandyKidYao/nowly/releases/download/v0.3.0/nowly.apk","sha256":"${"0".repeat(64)}","size":5242880,"notes":"修复提醒延迟"}"""
        show()
        compose.runOnIdle { updater.checkIfDue() }
        compose.onNodeWithText("新版本 0.3.0 可用").assertIsDisplayed()
        compose.onNodeWithTag("update-notice").performClick()
        compose.onNodeWithText("新版本 0.3.0").assertIsDisplayed()
        compose.onNodeWithText("安装包 5.0 MB，下载后由系统确认安装，现有数据会保留。").assertIsDisplayed()
        compose.onNodeWithTag("update-notes").assertTextEquals("修复提醒延迟")
        // The switch belongs to the idle view; an offer shows only what concerns this release.
        compose.onNodeWithTag("auto-check-switch").assertDoesNotExist()
        compose.onNodeWithTag("update-download").assertTextEquals("下载并安装").performClick()
        compose.onNodeWithText("下载未完成，请检查网络后重试。").assertIsDisplayed()
        compose.onNodeWithTag("update-download").assertTextEquals("重新下载")
        compose.onNodeWithTag("update-page").assertTextEquals("打开下载页")
        compose.onNodeWithTag("update-close").performClick()
        compose.onNodeWithText("新版本 0.3.0 可用").assertIsDisplayed()
        // Skipping from the offer hides the reminder without losing the release.
        compose.runOnIdle { updater.check() }
        compose.onNodeWithTag("update-notice").performClick()
        compose.onNodeWithTag("update-skip").performClick()
        compose.onNodeWithTag("update-dialog").assertDoesNotExist()
        compose.onNodeWithTag("update-notice").assertDoesNotExist()
        assertEquals(9, settings.skippedVersion)
        compose.onNodeWithTag("version-updates").performClick()
        compose.onNodeWithText("新版本 0.3.0").assertIsDisplayed()
    }
}
