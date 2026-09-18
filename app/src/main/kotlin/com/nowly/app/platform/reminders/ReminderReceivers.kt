package com.nowly.app.platform.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nowly.app.NowlyApplication
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidReminderGateway.ACTION_ALARM) return
        val token = intent.getStringExtra("token") ?: return
        val at = intent.getLongExtra("plannedAt", 0)
        val pending = goAsync()
        val container = (context.applicationContext as NowlyApplication).container
        container.scope.launch {
            try { container.reminders.fire(token, at) }
            catch (error: Exception) { Log.e("Nowly", "Alarm processing failed", error) }
            finally { pending.finish() }
        }
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(AndroidReminderGateway.ACTION_SKIP, AndroidReminderGateway.ACTION_DISMISS)) return
        val id = intent.getStringExtra(AndroidReminderGateway.EXTRA_OCCURRENCE) ?: return
        val pending = goAsync()
        val container = (context.applicationContext as NowlyApplication).container
        container.scope.launch {
            try { container.reminders.skip(id) }
            catch (error: Exception) { Log.e("Nowly", "Notification action failed", error) }
            finally { pending.finish() }
        }
    }
}

class RestoreRemindersReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        val container = (context.applicationContext as NowlyApplication).container
        container.scope.launch {
            try { container.reminders.reconcile(rebuildSchedule = true) }
            catch (error: Exception) { Log.e("Nowly", "Reminder recovery failed", error) }
            finally { pending.finish() }
        }
    }
}
