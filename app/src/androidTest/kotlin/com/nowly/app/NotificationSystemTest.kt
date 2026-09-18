package com.nowly.app

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.nowly.app.data.local.SampleEntity
import com.nowly.app.data.local.SampleStatus
import com.nowly.app.platform.reminders.AndroidReminderGateway
import com.nowly.app.projects.AnswerOption
import com.nowly.app.projects.ProjectDraft
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationSystemTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(*if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
    private val app = ApplicationProvider.getApplicationContext<NowlyApplication>()
    private val container get() = app.container
    private val id = UUID.randomUUID().toString()
    private val draft = ProjectDraft(id, "通知测试", "现在怎么样？", false,
        listOf(AnswerOption(UUID.randomUUID().toString(), "甲", 0), AnswerOption(UUID.randomUUID().toString(), "乙", 1)), listOf(600))
    private val notifications = app.getSystemService(NotificationManager::class.java)
    @After fun cleanup() = runBlocking { container.reminders.delete(id) }

    private suspend fun post(at: Long): SampleEntity {
        container.reminders.save(draft)
        val sample = container.repository.newOccurrence(id, at, ZoneId.systemDefault())!!
        container.reminders.reconcile()
        return sample
    }
    private fun notification() = notifications.activeNotifications.first { it.notification.extras.getString(AndroidReminderGateway.EXTRA_OCCURRENCE)?.startsWith("$id@") == true }.notification
    private suspend fun awaitStatus(sample: SampleEntity, status: String) = withTimeout(10_000) {
        container.repository.dao.observeSample(sample.id).first { it?.status == status }
    }

    @Test fun swipeClearCallbackIsPersistedAsSkippedWithoutAnAnswer() = runBlocking {
        val sample = post(System.currentTimeMillis())
        notification().deleteIntent.send()
        val saved = awaitStatus(sample, SampleStatus.SKIPPED)!!
        assertNull(saved.optionId); assertNull(saved.answeredAt)
    }

    @Test fun replacementKeepsOneNotificationAndIgnoresTheOldDeleteCallback() = runBlocking {
        val old = post(System.currentTimeMillis())
        val staleDelete = notification().deleteIntent
        val latest = container.repository.newOccurrence(id, old.plannedAt + 1, ZoneId.systemDefault())!!
        container.reminders.reconcile()
        assertEquals(1, notifications.activeNotifications.count { it.notification.extras.getString(AndroidReminderGateway.EXTRA_OCCURRENCE)?.startsWith("$id@") == true })
        staleDelete.send()
        assertEquals(SampleStatus.UNPROCESSED, container.repository.dao.sample(old.id)?.status)
        notification().actions.first().actionIntent.send()
        awaitStatus(latest, SampleStatus.SKIPPED)
        assertNull(container.repository.dao.sample(latest.id)?.optionId)
    }

    @Test fun actualAlarmBroadcastProducesAPendingNotification() = runBlocking {
        assumeTrue("This test requires exact-alarm access on the dedicated test emulator", container.reminderGateway.canSchedulePrecisely())
        val zone = ZoneId.systemDefault()
        val now = Instant.now().atZone(zone)
        var due = now.plusMinutes(1).withSecond(0).withNano(0)
        if (Duration.between(now, due).seconds < 10) due = due.plusMinutes(1)
        container.reminders.save(draft.copy(times = listOf(due.hour * 60 + due.minute)))
        val received = withTimeout(100_000) {
            container.repository.dao.observeHistory(id, 30).first { it.isNotEmpty() }.single()
        }
        assertEquals(due.toInstant().toEpochMilli(), received.plannedAt)
        assertEquals(SampleStatus.PENDING, received.status)
        withTimeout(10_000) { container.repository.dao.observeSample(received.id).first { it?.submittedAt != null } }
        assertEquals(received.id, notification().extras.getString(AndroidReminderGateway.EXTRA_OCCURRENCE))
    }
}
