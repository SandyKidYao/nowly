package com.nowly.app

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.nowly.app.projects.AnswerOption
import com.nowly.app.projects.ProjectDraft
import com.nowly.app.projects.RecordProject
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OptionReorderingTest {
    @get:Rule(order = 0) val permission: GrantPermissionRule = GrantPermissionRule.grant(*if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val id = UUID.randomUUID().toString()
    private val container get() = (compose.activity.application as NowlyApplication).container

    @After fun cleanup() { runBlocking { container.reminders.delete(id) } }

    private fun openEditor(count: Int): RecordProject {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("心情 ›").fetchSemanticsNodes().isNotEmpty() }
        val original = runBlocking {
            container.reminders.save(ProjectDraft(id, "排序测试", "请选择当前等级", true,
                List(count) { AnswerOption(UUID.randomUUID().toString(), "等级 ${it + 1}", it) }, listOf(600, 1200)))
            container.repository.projects().first { it.id == id }
        }
        compose.onNodeWithTag("projects-list").performScrollToNode(hasText("排序测试 ›"))
        compose.onNodeWithText("排序测试 ›").performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithTag("editor-list").performScrollToIndex(1)
        return original
    }

    private fun position(option: AnswerOption): Int =
        compose.onNodeWithTag("option-handle-${option.id}").fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription].substringAfter("第 ").substringBefore(" 项").toInt()

    private fun action(option: AnswerOption, label: String) {
        val action = compose.onNodeWithTag("option-handle-${option.id}").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].first { it.label == label }
        compose.runOnIdle { assertTrue(action.action()) }
    }

    @Test fun draggingKeepsTextIdentityAndHistoryAndCommitsOnlyWhenSaved() {
        val original = openEditor(5)
        val first = original.options[0]
        val second = original.options[1]
        val savedAnswer = runBlocking {
            val at = System.currentTimeMillis()
            val sample = container.repository.newOccurrence(id, at - 60_000, ZoneId.systemDefault())!!
            container.repository.answer(sample.id, first.id, at, ZoneId.systemDefault())
            container.reminders.enable(id, false)
            container.repository.dao.sample(sample.id)!!
        }
        compose.onNodeWithTag("option-input-${first.id}").performClick().performTextReplacement("改名后的等级")
        val start = compose.onNodeWithTag("option-handle-${first.id}").fetchSemanticsNode().boundsInRoot.center
        val end = compose.onNodeWithTag("option-handle-${second.id}").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("option-handle-${first.id}").performTouchInput {
            swipe(center, center + (end - start), durationMillis = 700)
        }
        compose.waitUntil(10_000) { position(first) == 2 }
        compose.onNodeWithTag("option-input-${first.id}").assertTextContains("改名后的等级")
        assertEquals(original.options.map { it.id }, runBlocking { container.repository.projects().first { it.id == id }.options.map { it.id } })
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("option-handle-${first.id}").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, position(first))
        compose.onNodeWithTag("option-input-${first.id}").assertTextContains("改名后的等级")
        compose.onNodeWithText("上移").assertDoesNotExist()
        captureEvidence(compose, "option-reordering")
        compose.onNodeWithText("保存项目").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("采样记录").fetchSemanticsNodes().isNotEmpty() }
        val updated = runBlocking { container.repository.projects().first { it.id == id } }
        assertEquals(listOf(second.id, first.id) + original.options.drop(2).map { it.id }, updated.options.map { it.id })
        assertEquals("改名后的等级", updated.options.first { it.id == first.id }.label)
        assertEquals(original.options.associate { it.id to it.colorIndex }, updated.options.associate { it.id to it.colorIndex })
        assertEquals(savedAnswer, runBlocking { container.repository.dao.sample(savedAnswer.id) })
    }

    @Test fun accessibilityMovesBothDirectionsAndBackDiscardsTheDraft() {
        val original = openEditor(3)
        val first = original.options.first()
        val last = original.options.last()
        assertEquals(listOf("下移"), compose.onNodeWithTag("option-handle-${first.id}").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].map { it.label })
        assertEquals(listOf("上移"), compose.onNodeWithTag("option-handle-${last.id}").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].map { it.label })
        action(first, "下移")
        assertEquals(2, position(first))
        action(first, "上移")
        assertEquals(1, position(first))
        action(last, "上移")
        assertEquals(2, position(last))
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(original.options.map { it.id }, runBlocking { container.repository.projects().first { it.id == id }.options.map { it.id } })
    }

}
