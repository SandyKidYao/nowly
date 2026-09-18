package com.nowly.app.platform.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.nowly.app.R
import com.nowly.app.data.local.SampleEntity
import com.nowly.app.projects.RecordProject
import com.nowly.app.recording.AnswerActivity

class AndroidReminderGateway(private val context: Context) : ReminderGateway {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    init {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "采样提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "按你设置的时间，提醒记录当下状态"
        })
    }

    fun canSchedulePrecisely(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    override fun canNotify(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    private fun alarmIntent(token: String = "", at: Long = 0): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, AlarmReceiver::class.java).setAction(ACTION_ALARM)
            .setData("nowly://alarm".toUri())
            .putExtra("token", token).putExtra("plannedAt", at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun schedule(at: Long, token: String) {
        val intent = alarmIntent(token, at)
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            }
        } catch (_: SecurityException) {
            // Permission may be revoked between the check and the system call.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    override fun cancelAlarm() { alarms.cancel(alarmIntent()) }

    override fun hasAlarmIntent(): Boolean = PendingIntent.getBroadcast(context, 0,
        Intent(context, AlarmReceiver::class.java).setAction(ACTION_ALARM).setData("nowly://alarm".toUri()),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) != null

    override fun publish(project: RecordProject, sample: SampleEntity, silent: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!canNotify()) return
        val answer = PendingIntent.getActivity(context, 0,
            Intent(context, AnswerActivity::class.java)
                .setData(Uri.Builder().scheme("nowly").authority("answer").appendPath(sample.id).build())
                .putExtra(EXTRA_OCCURRENCE, sample.id)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(kind: String) = PendingIntent.getBroadcast(context, 0,
            Intent(context, NotificationActionReceiver::class.java).setAction(kind)
                .setData(Uri.Builder().scheme("nowly").authority(kind).appendPath(sample.id).build())
                .putExtra(EXTRA_OCCURRENCE, sample.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(project.name).setContentText(project.question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(project.question + "\n轻触通知，记录当下"))
            .setContentIntent(answer).setDeleteIntent(action(ACTION_DISMISS))
            .addAction(0, "跳过", action(ACTION_SKIP))
            .setAutoCancel(false).setOnlyAlertOnce(silent).setSilent(silent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addExtras(Bundle().apply { putString(EXTRA_OCCURRENCE, sample.id) })
            .build()
        notifications.notify(TAG_PREFIX + project.id, 1, notification)
    }

    override fun cancelProject(projectId: String) { notifications.cancel(TAG_PREFIX + projectId, 1) }
    override fun visibleProjects(): Set<String> = notifications.activeNotifications.mapNotNull {
        it.tag?.takeIf { tag -> tag.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX)
    }.toSet()

    companion object {
        const val CHANNEL = "sampling"
        const val EXTRA_OCCURRENCE = "occurrenceId"
        const val ACTION_ALARM = "com.nowly.ALARM"
        const val ACTION_SKIP = "com.nowly.SKIP"
        const val ACTION_DISMISS = "com.nowly.DISMISS"
        private const val TAG_PREFIX = "nowly:"
    }
}
