package com.nowly.app

import android.content.Context
import androidx.room.Room
import com.nowly.app.data.local.NowlyDatabase
import com.nowly.app.data.repository.OfflineProjectRepository
import com.nowly.app.projects.ProjectRepository
import java.time.Clock
import com.nowly.app.platform.reminders.AndroidReminderGateway
import com.nowly.app.platform.reminders.ReminderEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The application owns one database. UI code receives interfaces, not Context. */
class AppContainer(context: Context, databaseName: String = "nowly.db") {
    private val database: NowlyDatabase by lazy {
        Room.databaseBuilder(
            context.applicationContext,
            NowlyDatabase::class.java,
            databaseName,
        ).addMigrations(NowlyDatabase.MIGRATION_1_2).build()
    }

    val clock: Clock = Clock.systemUTC()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repository by lazy { OfflineProjectRepository(database) }
    val projectRepository: ProjectRepository get() = repository
    val reminderGateway by lazy { AndroidReminderGateway(context.applicationContext) }
    val reminders by lazy { ReminderEngine(repository, reminderGateway, clock) }
}
