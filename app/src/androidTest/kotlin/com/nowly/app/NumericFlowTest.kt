package com.nowly.app

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.lifecycle.ViewModelProvider
import com.nowly.app.data.local.SampleOrigin
import com.nowly.app.data.local.SampleStatus
import com.nowly.app.platform.reminders.AndroidReminderGateway
import com.nowly.app.projects.*
import com.nowly.app.recording.AnswerActivity
import com.nowly.app.ui.MvpViewModel
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NumericFlowTest {
    @get:Rule(order = 0) val permission: GrantPermissionRule = GrantPermissionRule.grant(*if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as NowlyApplication).container
    private fun home() = compose.waitUntil(10_000) { compose.onAllNodesWithTag("projects-list").fetchSemanticsNodes().isNotEmpty() }
    private fun write(value: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("numeric-value").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("numeric-value").performTextReplacement(value)
        compose.onNodeWithTag("numeric-save").performScrollTo().performClick()
    }

    @Test fun createsWeeklyWeightTracksExactChangesRetainsDraftAndCanEditOrDeleteMeasurements() {
        home()
        compose.onNodeWithContentDescription("创建记录").performClick()
        compose.onNodeWithTag("type-number").performClick()
        compose.onNodeWithText("使用每周体重示例").performClick()
        compose.onNodeWithTag("editor-list").performScrollToNode(hasTestTag("reminder-switch"))
        compose.onNodeWithTag("reminder-switch").performClick()
        compose.onNodeWithTag("frequency-weekly").assertIsSelected()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("numeric-latest").fetchSemanticsNodes().isNotEmpty() }
        val project = runBlocking { container.repository.projects().first { it.kind == ProjectKind.NUMBER && it.name == "体重" } }
        try {
            assertEquals("kg", project.unit); assertEquals(ReminderFrequency.WEEKLY, project.reminderFrequency)
            assertEquals(64, project.reminderDays); assertEquals(listOf(480), project.times)
            compose.onNodeWithText("记录数字").performClick()
            write("65.80")
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("numeric-latest").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("numeric-latest").assertTextEquals("65.8 kg")
            // Keep the first observation a week earlier so the rendered chart exercises real calendar spacing.
            runBlocking {
                val first = container.repository.dao.allSamples(project.id).single()
                val zone = ZoneId.systemDefault()
                val measuredAt = java.time.Instant.ofEpochMilli(first.eventAt).atZone(zone).minusWeeks(1).toInstant().toEpochMilli()
                assertTrue(container.reminders.saveNumeric(project.id, "65.8", measuredAt, zone, UUID.randomUUID().toString(), editing = first))
            }
            compose.onNodeWithText("记录数字").performClick()
            compose.onNodeWithTag("numeric-value").performTextReplacement("65.4")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("numeric-value").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("numeric-value").assert(hasText("65.4"))
            compose.onNodeWithTag("numeric-save").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("numeric-latest").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("numeric-change").assertTextEquals("较上次 -0.4 kg")
            val latest = runBlocking { container.repository.dao.allSamples(project.id).maxBy { it.eventAt } }
            compose.onNodeWithTag("statistics-list").performScrollToNode(hasTestTag("numeric-row-${latest.id}"))
            compose.onNodeWithTag("numeric-row-${latest.id}").performClick()
            write("65.25")
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("statistics-list").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("statistics-list").performScrollToIndex(0)
            compose.onNodeWithTag("numeric-latest").assertTextEquals("65.25 kg")
            compose.onNodeWithTag("numeric-change").assertTextEquals("较上次 -0.55 kg")
            screenshot("numeric-statistics")
            compose.onNodeWithTag("statistics-list").performScrollToNode(hasTestTag("numeric-row-${latest.id}"))
            compose.onNodeWithTag("numeric-row-${latest.id}").performClick()
            compose.onNodeWithTag("numeric-delete").performScrollTo().performClick()
            compose.onNodeWithText("取消").performClick()
            assertNotNull(runBlocking { container.repository.dao.sample(latest.id) })
            compose.onNodeWithTag("numeric-delete").performScrollTo().performClick()
            compose.onNodeWithTag("confirm-numeric-delete").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("statistics-list").fetchSemanticsNodes().isNotEmpty() }
            assertNull(runBlocking { container.repository.dao.sample(latest.id) })
            assertEquals(1, runBlocking { container.repository.dao.allSamples(project.id).size })
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("projects-list").performScrollToNode(hasTestTag("project-settings-${project.id}"))
            compose.onNodeWithTag("project-settings-${project.id}").performClick()
            compose.onNodeWithTag("type-number").assertIsNotEnabled()
        } finally { runBlocking { container.reminders.delete(project.id) } }
    }

    @Test fun backfillIsIndependentAndNotificationAnswerSavesAndClosesNormally() {
        home()
        val id = UUID.randomUUID().toString()
        val draft = ProjectDraft(id, "补记测试", "这次是多少？", true, emptyList(), listOf(480), true,
            ProjectKind.NUMBER, "kg", ReminderFrequency.WEEKLY, 64)
        val zone = ZoneId.systemDefault()
        val pending = runBlocking {
            container.reminders.save(draft)
            val sample = container.repository.newOccurrence(id, System.currentTimeMillis() - 60_000, zone)!!
            container.reminders.enable(id, false)
            sample
        }
        try {
            compose.onNodeWithTag("projects-list").performScrollToNode(hasTestTag("record-number-$id"))
            compose.onNodeWithTag("record-number-$id").performClick()
            compose.onNodeWithTag("numeric-value").performTextReplacement("66.1")
            compose.onNodeWithTag("numeric-date").performScrollTo().performClick()
            val yesterday = LocalDate.now().minusDays(1)
            onView(isAssignableFrom(DatePicker::class.java)).perform(object : ViewAction {
                override fun getConstraints(): Matcher<View> = isAssignableFrom(DatePicker::class.java)
                override fun getDescription() = "Choose yesterday for an independent measurement"
                override fun perform(uiController: UiController, view: View) {
                    (view as DatePicker).updateDate(yesterday.year, yesterday.monthValue - 1, yesterday.dayOfMonth)
                    uiController.loopMainThreadUntilIdle()
                }
            })
            onView(withId(android.R.id.button1)).perform(click())
            screenshot("numeric-entry-backfill")
            compose.onNodeWithTag("numeric-save").performScrollTo().performClick()
            home()
            val rows = runBlocking { container.repository.dao.allSamples(id) }
            assertEquals(SampleStatus.PENDING, rows.first { it.id == pending.id }.status)
            assertEquals(yesterday.toString(), rows.first { it.origin == SampleOrigin.MANUAL }.localDate)
            compose.runOnUiThread { compose.activity.startActivity(Intent(compose.activity, AnswerActivity::class.java)
                .putExtra(AndroidReminderGateway.EXTRA_OCCURRENCE, pending.id)) }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("numeric-value").fetchSemanticsNodes().isNotEmpty() }
            write("65.8")
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("numeric-value").fetchSemanticsNodes().isEmpty() }
            val answered = runBlocking { container.repository.dao.sample(pending.id)!! }
            assertEquals(SampleStatus.ANSWERED, answered.status)
            assertEquals("65.8", answered.numericValue)
            assertEquals(2, runBlocking { container.repository.dao.allSamples(id).size })
        } finally { runBlocking { container.reminders.delete(id) } }
    }

    @Test fun recreatedHomeFormCannotAnswerAReminderThatReplacedItsOriginal() {
        home()
        val id = UUID.randomUUID().toString()
        val zone = ZoneId.systemDefault()
        val old = runBlocking {
            container.reminders.save(ProjectDraft(id, "提醒更新测试", "多少？", true, emptyList(), listOf(480), true,
                ProjectKind.NUMBER, "kg", ReminderFrequency.WEEKLY, 64))
            container.repository.newOccurrence(id, System.currentTimeMillis() - 60_000, zone)!!
        }
        try {
            val vm = ViewModelProvider(compose.activity)[MvpViewModel::class.java]
            compose.waitUntil(10_000) { (vm.projects.value as? HomeUiState.Ready)?.projects?.firstOrNull { it.id == id }?.pendingId == old.id }
            compose.onNodeWithTag("projects-list").performScrollToNode(hasTestTag("record-number-$id"))
            compose.onNodeWithTag("record-number-$id").performClick()
            compose.onNodeWithTag("numeric-value").performTextReplacement("65.8")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("numeric-value").fetchSemanticsNodes().isNotEmpty() }
            val latest = runBlocking { container.repository.newOccurrence(id, System.currentTimeMillis() - 1000, zone)!! }
            compose.waitUntil(10_000) { (vm.projects.value as? HomeUiState.Ready)?.projects?.firstOrNull { it.id == id }?.pendingId == latest.id }
            compose.onNodeWithTag("numeric-save").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("numeric-error").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(SampleStatus.UNPROCESSED, runBlocking { container.repository.dao.sample(old.id)!!.status })
            assertEquals(SampleStatus.PENDING, runBlocking { container.repository.dao.sample(latest.id)!!.status })
            assertEquals(2, runBlocking { container.repository.dao.allSamples(id).size })
        } finally { runBlocking { container.reminders.delete(id) } }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("captureScreenshots") != "true") return
        compose.waitForIdle()
        runCatching { instrumentation.uiAutomation.waitForIdle(500, 5000) }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val directory = compose.activity.getExternalFilesDir("mvp-evidence")!!; directory.mkdirs()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
