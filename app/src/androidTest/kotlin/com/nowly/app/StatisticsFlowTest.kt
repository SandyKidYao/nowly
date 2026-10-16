package com.nowly.app

import android.Manifest
import android.os.Build
import android.view.View
import android.widget.DatePicker
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.nowly.app.data.local.SampleEntity
import com.nowly.app.data.local.SampleStatus
import com.nowly.app.projects.AnswerOption
import com.nowly.app.projects.ProjectDraft
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matcher
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatisticsFlowTest {
    @get:Rule(order = 0) val permission: GrantPermissionRule = GrantPermissionRule.grant(*if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun mergedPageSharesOneDateRangeBetweenStatisticsAndAnsweredRecords() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("心情").fetchSemanticsNodes().isNotEmpty() }
        val container = (compose.activity.application as NowlyApplication).container
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val id = UUID.randomUUID().toString()
        val options = listOf("无", "轻微", "中等", "较高", "很高").mapIndexed { index, label -> AnswerOption(UUID.randomUUID().toString(), label, index) }
        runBlocking {
            container.reminders.save(ProjectDraft(id, "统计样本", "此刻感到多大压力？", true, options, listOf(600)))
            container.reminders.enable(id, false)
            repeat(42) { index ->
                val date = today.minusDays(8).plusDays(index / 5L)
                val time = date.atTime(listOf(10, 12, 15, 17, 20)[index % 5], 10).atZone(zone)
                val answered = index < 35
                container.repository.dao.upsertSample(SampleEntity("fixture-$id-$index", id, time.toInstant().toEpochMilli() - 600_000,
                    time.toInstant().toEpochMilli(), if (answered) SampleStatus.ANSWERED else if (index < 38) SampleStatus.SKIPPED else SampleStatus.UNPROCESSED,
                    if (answered) options[index % 5].id else null, if (answered) time.toInstant().toEpochMilli() else null,
                    date.toString(), time.hour, zone.id, time.toInstant().toEpochMilli(), if (answered) zone.id else null))
            }
        }
        try {
            compose.onNodeWithTag("projects-list").performScrollToNode(hasContentDescription("统计样本统计"))
            compose.onNodeWithContentDescription("统计样本统计").performClick()
            compose.onNodeWithText("筛选").assertDoesNotExist()
            compose.onNodeWithText("查看统计").assertDoesNotExist()
            // Fixtures span today-8..today; the default 7-day window holds answers 10..34 and drops the oldest 10.
            compose.waitUntil(10_000) { compose.onAllNodesWithText("25").fetchSemanticsNodes().isNotEmpty() }
            // Every option is answered equally often in both 7-day windows, so the level average is the middle and unchanged.
            compose.onNode(hasText("3.0") and hasAnyAncestor(hasTestTag("average-level"))).assertIsDisplayed()
            compose.onNodeWithText("接近「中等」").assertIsDisplayed()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("持平") and hasAnyAncestor(hasTestTag("average-change"))).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("较前 7 天").assertIsDisplayed()
            // The record mosaic has one row per date in the 7-day window, newest first.
            compose.onNodeWithTag("statistics-list").performScrollToNode(hasTestTag("mosaic-row-${today.minusDays(6)}"))
            compose.onNodeWithTag("mosaic-row-${today.minusDays(7)}").assertDoesNotExist()
            compose.onNodeWithTag("statistics-list").performScrollToIndex(0)
            compose.onNodeWithTag("date-range").performClick()
            compose.onNodeWithText("最近 30 天").performClick()
            compose.onNodeWithText("应用筛选").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("35").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("3").assertExists()
            compose.onNodeWithText("4").assertExists()
            compose.onNodeWithText("平均趋势").assertIsDisplayed()
            compose.onNodeWithText("较前 30 天").assertIsDisplayed()
            compose.onNode(hasText("无可比数据") and hasAnyAncestor(hasTestTag("average-change"))).assertIsDisplayed()
            screenshot("statistics-populated")
            compose.onNodeWithTag("trend-chart").performTouchInput { click(Offset(right - 2f, centerY)) }
            compose.onNodeWithTag("trend-detail").assert(hasText("没有回答", substring = true))
            // Records and statistics share the date range; the oldest fixture day lists its answers under their reminder times.
            compose.onNodeWithTag("statistics-list").performScrollToNode(hasTestTag("mosaic-row-${today.minusDays(8)}"))
            compose.onNodeWithTag("mosaic-row-${today.minusDays(8)}").assert(hasContentDescription("10:00 无，12:00 轻微", substring = true))
            // Unprocessed (today) and skipped (yesterday) prompts both read as not answered.
            compose.onNodeWithTag("statistics-list").performScrollToNode(hasTestTag("mosaic-row-$today"))
            compose.onNodeWithTag("mosaic-row-$today").assert(hasContentDescription("10:00 未回答，12:00 未回答", substring = true))
            compose.onNodeWithTag("mosaic-row-${today.minusDays(1)}").assert(hasContentDescription("10:00 未回答", substring = true))
            compose.onNodeWithText("已停用选项").assertDoesNotExist()
            screenshot("statistics-records")
            compose.onNodeWithTag("statistics-list").performScrollToNode(hasText("按选项统计"))
            compose.onAllNodesWithText("7 次 · 20%").assertCountEquals(5)
            screenshot("statistics-options")
            compose.onNodeWithTag("statistics-list").performScrollToNode(hasTestTag("copy-prompt"))
            compose.onNodeWithTag("export-count").assertTextEquals("42 条")
            screenshot("statistics-export")
            compose.onNodeWithTag("preview-prompt").performClick()
            compose.onNodeWithTag("prompt-preview").assert(hasText("1 = 无，2 = 轻微，3 = 中等，4 = 较高，5 = 很高", substring = true))
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithTag("copy-prompt").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("已复制提示词").fetchSemanticsNodes().isNotEmpty() }
            val clip = compose.runOnUiThread {
                (compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            }
            assertTrue(clip.contains("问题：此刻感到多大压力？"))
            assertTrue(clip.contains("回答 35 次 · 跳过 3 次 · 未处理 4 次"))
            compose.onNodeWithTag("statistics-list").performScrollToIndex(0)
            compose.onNodeWithTag("date-range").performClick()
            compose.onNodeWithText("开始：${today.minusDays(29)}").performClick()
            chooseDate(today.plusDays(1))
            compose.onNodeWithText("开始日期不能晚于结束日期").assertIsDisplayed()
            compose.onNodeWithText("结束：$today").performClick()
            chooseDate(today.plusDays(2))
            compose.onNodeWithText("应用筛选").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("这个日期范围内还没有回答记录").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("7 次 · 20%").assertDoesNotExist()
        } finally { runBlocking { container.reminders.delete(id) } }
    }

    private fun chooseDate(date: LocalDate) {
        onView(isAssignableFrom(DatePicker::class.java)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(DatePicker::class.java)
            override fun getDescription() = "Choose a calendar date in the platform picker"
            override fun perform(uiController: UiController, view: View) {
                (view as DatePicker).updateDate(date.year, date.monthValue - 1, date.dayOfMonth)
                uiController.loopMainThreadUntilIdle()
            }
        })
        onView(withId(android.R.id.button1)).perform(click())
    }

    private fun screenshot(name: String) = captureEvidence(compose, name)
}
