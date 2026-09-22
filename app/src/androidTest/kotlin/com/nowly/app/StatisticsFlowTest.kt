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
            compose.onNodeWithTag("statistics-list").performScrollToNode(hasText("25 条"))
            compose.onNodeWithTag("statistics-list").performScrollToNode(hasTestTag("history-record-fixture-$id-10"))
            compose.onNodeWithTag("history-record-fixture-$id-9").assertDoesNotExist()
            compose.onNodeWithTag("history-record-fixture-$id-0").assertDoesNotExist()
            compose.onNodeWithTag("statistics-list").performScrollToIndex(0)
            compose.onNodeWithTag("date-range").performClick()
            compose.onNodeWithText("最近 30 天").performClick()
            compose.onNodeWithText("应用筛选").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("35").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("3").assertExists()
            compose.onNodeWithText("4").assertExists()
            compose.onNodeWithText("日期趋势").assertIsDisplayed()
            screenshot("statistics-populated")
            compose.onNodeWithTag("statistics-list").performScrollToNode(hasText("35 条"))
            // Records and statistics share the date range: only answered rows are listed, oldest included.
            compose.onNodeWithTag("statistics-list").performScrollToNode(hasTestTag("history-record-fixture-$id-0"))
            compose.onNodeWithTag("history-record-fixture-$id-0").assertIsDisplayed()
            compose.onNodeWithTag("history-record-fixture-$id-35").assertDoesNotExist()
            compose.onNodeWithTag("history-record-fixture-$id-40").assertDoesNotExist()
            compose.onNodeWithText("已停用选项").assertDoesNotExist()
            screenshot("statistics-records")
            compose.onNodeWithTag("statistics-list").performScrollToNode(hasText("按选项统计"))
            compose.onAllNodesWithText("7 次 · 20%").assertCountEquals(5)
            screenshot("statistics-options")
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
