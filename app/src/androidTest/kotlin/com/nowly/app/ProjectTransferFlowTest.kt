package com.nowly.app

import android.Manifest
import android.os.Build
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import com.nowly.app.data.transfer.ProjectArchiveCodec
import com.nowly.app.projects.AnswerOption
import com.nowly.app.projects.ProjectDraft
import com.nowly.app.ui.MvpViewModel
import java.util.UUID
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectTransferFlowTest {
    @get:Rule(order = 0) val permission: GrantPermissionRule = GrantPermissionRule.grant(*if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private var createdId: String? = null
    @After fun cleanup() { createdId?.let { id -> runBlocking {
        (compose.activity.application as NowlyApplication).container.reminders.delete(id)
    } } }

    @Test fun importPreviewCanCancelAndReplacementRequiresExplicitConfirmation() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("import-project").fetchSemanticsNodes().isNotEmpty() }
        val container = (compose.activity.application as NowlyApplication).container
        val vm = ViewModelProvider(compose.activity)[MvpViewModel::class.java]
        val id = UUID.randomUUID().toString()
        createdId = id
        val draft = ProjectDraft(id, "迁移测试项目", "此刻如何？", true,
            listOf(AnswerOption(UUID.randomUUID().toString(), "低", 0), AnswerOption(UUID.randomUUID().toString(), "高", 1)), listOf(600), false)
        val bytes = runBlocking {
            container.reminders.save(draft)
            ProjectArchiveCodec.encode(container.reminders.exportProject(id))
        }
        runBlocking { container.reminders.delete(id) }
        compose.runOnIdle { vm.readProject { bytes.inputStream() } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("confirm-project-import").fetchSemanticsNodes().isNotEmpty() }
        captureTransfer("project-import-new")
        compose.onNodeWithText("取消").performClick()
        assertNull(runBlocking { container.repository.dao.project(id) })
        compose.runOnIdle { vm.readProject { bytes.inputStream() } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("confirm-project-import").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("confirm-project-import").performClick()
        compose.waitUntil(10_000) { vm.importPreview.value == null && !vm.transferBusy.value }
        assertEquals("迁移测试项目", runBlocking { container.repository.dao.project(id)!!.name })
        runBlocking { container.reminders.save(draft.copy(name = "目标中的新名称")) }
        compose.runOnIdle { vm.readProject { bytes.inputStream() } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("替换已有项目？").fetchSemanticsNodes().isNotEmpty() }
        captureTransfer("project-import-replace")
        compose.onNodeWithText("取消").performClick()
        assertEquals("目标中的新名称", runBlocking { container.repository.dao.project(id)!!.name })
        compose.runOnIdle { vm.readProject { bytes.inputStream() } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("替换项目").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("替换项目").performClick()
        compose.waitUntil(10_000) { vm.importPreview.value == null && !vm.transferBusy.value }
        assertEquals("迁移测试项目", runBlocking { container.repository.dao.project(id)!!.name })
        compose.onNodeWithTag("projects-list").performScrollToNode(hasTestTag("project-card-$id"))
        compose.onNodeWithTag("project-card-$id").performClick()
        compose.onNodeWithTag("statistics-list").performScrollToNode(hasTestTag("export-project"))
        compose.onNodeWithTag("export-project").assertIsEnabled()
        captureTransfer("project-export-section")
    }

    private fun captureTransfer(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureScreenshots") != "true") return
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val directory = compose.activity.getExternalFilesDir("mvp-evidence")!!
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
