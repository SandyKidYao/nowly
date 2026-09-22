package com.nowly.app

import android.Manifest
import android.os.Build
import android.app.NotificationManager
import com.nowly.app.platform.reminders.AndroidReminderGateway
import com.nowly.app.data.local.SampleStatus
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupTest {
    @get:Rule(order = 0) val permission: GrantPermissionRule = GrantPermissionRule.grant(*if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun opensDirectlyWithThePresetMoodProject() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("心情").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("心情").assertIsDisplayed()
        compose.onNodeWithContentDescription("心情提醒").assertDoesNotExist()
        compose.onNodeWithContentDescription("心情设置").assertExists()
        compose.onNodeWithContentDescription("心情统计").assertExists()
        compose.onNodeWithText("设置").assertDoesNotExist()
        compose.onNodeWithText("统计").assertDoesNotExist()
        compose.onNodeWithText("已暂停", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("每天 5 次 · 手动时间点").assertDoesNotExist()
        screenshot("home")
    }

    @Test fun reminderSwitchLivesInSettingsAndPausedProjectsAreMarkedOnHome() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("心情").fetchSemanticsNodes().isNotEmpty() }
        val container = (compose.activity.application as NowlyApplication).container
        val id = java.util.UUID.randomUUID().toString()
        runBlocking {
            container.reminders.save(com.nowly.app.projects.ProjectDraft(id, "暂停测试", "现在如何？", true,
                listOf(com.nowly.app.projects.AnswerOption(java.util.UUID.randomUUID().toString(), "好", 0),
                    com.nowly.app.projects.AnswerOption(java.util.UUID.randomUUID().toString(), "差", 1)), listOf(600)))
        }
        try {
            compose.onNodeWithTag("projects-list").performScrollToNode(hasTestTag("project-settings-$id"))
            compose.onNodeWithTag("project-paused-$id", useUnmergedTree = true).assertDoesNotExist()
            openHomeAction("暂停测试设置")
            compose.onNodeWithTag("editor-list").performScrollToNode(hasTestTag("reminder-switch"))
            compose.onNodeWithTag("reminder-switch").assertIsOn().performClick()
            compose.onNodeWithTag("reminder-switch").assertIsOff()
            // Leaving without saving must not pause the project.
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("projects-list").performScrollToNode(hasTestTag("project-settings-$id"))
            compose.onNodeWithTag("project-paused-$id", useUnmergedTree = true).assertDoesNotExist()
            org.junit.Assert.assertTrue(runBlocking { container.repository.projects().first { it.id == id }.remindersEnabled })
            openHomeAction("暂停测试设置")
            compose.onNodeWithTag("editor-list").performScrollToNode(hasTestTag("reminder-switch"))
            compose.onNodeWithTag("reminder-switch").assertIsOn().performClick()
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("projects-list").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) { runBlocking { !container.repository.projects().first { it.id == id }.remindersEnabled } }
            // The badge sits inside the card's merged click target, so read it from the unmerged tree.
            compose.onNodeWithTag("projects-list").performScrollToNode(hasTestTag("project-settings-$id"))
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("project-paused-$id", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("project-paused-$id", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("已暂停", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("暂停测试设置，已暂停").assertExists()
            screenshot("home-paused")
            openHomeAction("暂停测试设置，已暂停")
            compose.onNodeWithTag("editor-list").performScrollToNode(hasTestTag("reminder-switch"))
            compose.onNodeWithTag("reminder-switch").assertIsOff().performClick()
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("projects-list").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) { runBlocking { container.repository.projects().first { it.id == id }.remindersEnabled } }
            compose.onNodeWithTag("projects-list").performScrollToNode(hasTestTag("project-settings-$id"))
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("project-paused-$id", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        } finally { runBlocking { container.reminders.delete(id) } }
    }

    @Test fun createsEditsViewsStatisticsAndDeletesAProject() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("心情").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("创建记录").performClick()
        compose.onNodeWithText("普通分类").assertDoesNotExist()
        compose.onNodeWithText("有序等级").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("名称")).performTextReplacement("测试专注")
        compose.onNode(hasSetTextAction() and hasText("采样问题")).performTextReplacement("现在专注吗？")
        compose.onNodeWithTag("editor-list").performScrollToNode(hasSetTextAction() and hasText("选项 1"))
        compose.onNode(hasSetTextAction() and hasText("选项 1")).performTextReplacement("专注")
        compose.onNodeWithTag("editor-list").performScrollToNode(hasSetTextAction() and hasText("选项 2"))
        compose.onNode(hasSetTextAction() and hasText("选项 2")).performTextReplacement("分心")
        compose.onNodeWithText("保存").performClick()
        // Creating a record lands on its merged statistics-and-records page.
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("statistics-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("screen-title").assertTextEquals("统计")
        compose.onNodeWithText("测试专注").assertIsDisplayed()
        compose.onNodeWithText("采样记录").assertDoesNotExist()
        compose.onNodeWithText("今天还没有采样记录。").assertDoesNotExist()
        compose.onNodeWithText("详细图表").assertDoesNotExist()
        compose.onNodeWithText("日期趋势").assertIsDisplayed()
        screenshot("statistics-empty")
        compose.onNodeWithTag("statistics-list").performScrollToNode(hasText("按选项统计"))
        compose.onNodeWithText("按选项统计").assertIsDisplayed()
        compose.onNodeWithTag("statistics-list").performScrollToNode(hasText("记录"))
        compose.onAllNodesWithText("这个日期范围内还没有回答记录").assertCountEquals(4)
        compose.onNodeWithContentDescription("返回").performClick()
        openHomeAction("测试专注统计")
        compose.onNodeWithTag("screen-title").assertTextEquals("统计")
        compose.onNodeWithContentDescription("返回").performClick()
        openHomeAction("测试专注设置")
        compose.onNode(hasSetTextAction() and hasText("名称")).performTextReplacement("专注观察")
        compose.activityRule.scenario.recreate()
        screenshot("editor")
        compose.onNode(hasSetTextAction() and hasText("名称")).assertTextContains("专注观察")
        compose.onNodeWithTag("editor-list").performScrollToNode(hasText("删除"))
        compose.onNodeWithText("删除").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("专注观察").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("专注观察").assertIsDisplayed()
        openHomeAction("专注观察设置")
        compose.onNodeWithTag("editor-list").performScrollToNode(hasText("删除"))
        compose.onNodeWithText("删除").performScrollTo().performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("projects-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("专注观察").assertDoesNotExist()
    }
    @Test fun answeringFromANotificationSavesAndReturnsToThePreviousActivity() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("心情").fetchSemanticsNodes().isNotEmpty() }
        val container = (compose.activity.application as NowlyApplication).container
        val pair = runBlocking {
            val project = container.repository.projects().first { it.name == "心情" }
            val record = container.repository.newOccurrence(project.id, System.currentTimeMillis(), ZoneId.systemDefault())!!
            container.reminders.reconcile()
            project to record
        }
        val notifications = compose.activity.getSystemService(NotificationManager::class.java)
        notifications.activeNotifications.first { it.notification.extras.getString(AndroidReminderGateway.EXTRA_OCCURRENCE) == pair.second.id }.notification.contentIntent.send()
        val option = pair.first.options.first { it.active }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("answer-${option.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("记录你现在的状态。轻点即保存并返回。").assertDoesNotExist()
        screenshot("answer")
        compose.onNodeWithTag("answer-${option.id}").performClick()
        compose.waitUntil(10_000) { runBlocking { container.repository.dao.sample(pair.second.id)?.status == SampleStatus.ANSWERED } }
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { activity -> org.junit.Assert.assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, activity.lifecycle.currentState) }
        compose.onNodeWithText("NOWLY").assertIsDisplayed()
    }

    private fun openHomeAction(description: String) {
        compose.onNodeWithTag("projects-list").performScrollToNode(hasContentDescription(description))
        compose.onNodeWithContentDescription(description).performClick()
    }

    private fun screenshot(name: String) = captureEvidence(compose, name)
}
