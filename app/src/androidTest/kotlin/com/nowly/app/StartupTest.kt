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
        compose.waitUntil(10_000) { compose.onAllNodesWithText("心情 ›").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("心情 ›").assertIsDisplayed()
        compose.onNodeWithContentDescription("心情提醒").assertIsOn()
        screenshot("home")
    }

    @Test fun createsEditsViewsStatisticsAndDeletesAProject() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("心情 ›").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("创建记录项目").performClick()
        compose.onNode(hasSetTextAction() and hasText("项目名称")).performTextReplacement("测试专注")
        compose.onNode(hasSetTextAction() and hasText("采样问题")).performTextReplacement("现在专注吗？")
        compose.onNodeWithTag("editor-list").performScrollToNode(hasSetTextAction() and hasText("选项 1"))
        compose.onNode(hasSetTextAction() and hasText("选项 1")).performTextReplacement("专注")
        compose.onNodeWithTag("editor-list").performScrollToNode(hasSetTextAction() and hasText("选项 2"))
        compose.onNode(hasSetTextAction() and hasText("选项 2")).performTextReplacement("分心")
        compose.onNodeWithText("保存项目").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("采样记录").fetchSemanticsNodes().isNotEmpty() }
        screenshot("detail")
        compose.onNodeWithText("查看统计").performClick()
        screenshot("statistics")
        compose.onNodeWithText("日期趋势").assertIsDisplayed()
        compose.onNodeWithText("按选项统计").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onNode(hasSetTextAction() and hasText("项目名称")).performTextReplacement("专注观察")
        compose.activityRule.scenario.recreate()
        screenshot("editor")
        compose.onNode(hasSetTextAction() and hasText("项目名称")).assertTextContains("专注观察")
        compose.onNodeWithTag("editor-list").performScrollToNode(hasText("删除项目"))
        compose.onNodeWithText("删除项目").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("保存项目").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("采样记录").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("专注观察").assertIsDisplayed()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithTag("editor-list").performScrollToNode(hasText("删除项目"))
        compose.onNodeWithText("删除项目").performScrollTo().performClick()
        compose.onNodeWithText("删除项目及记录").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("NOWLY").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("专注观察 ›").assertDoesNotExist()
    }
    @Test fun answeringFromANotificationSavesAndReturnsToThePreviousActivity() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("心情 ›").fetchSemanticsNodes().isNotEmpty() }
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
        screenshot("answer")
        compose.onNodeWithTag("answer-${option.id}").performClick()
        compose.waitUntil(10_000) { runBlocking { container.repository.dao.sample(pair.second.id)?.status == SampleStatus.ANSWERED } }
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { activity -> org.junit.Assert.assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, activity.lifecycle.currentState) }
        compose.onNodeWithText("NOWLY").assertIsDisplayed()
    }

    private fun screenshot(name: String) = captureEvidence(compose, name)
}
