package com.nowly.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ProjectEntity::class, OptionEntity::class, ReminderTimeEntity::class, SampleEntity::class, MetadataEntity::class], version = 2, exportSchema = true)
abstract class NowlyDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN ordered INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS options (id TEXT NOT NULL PRIMARY KEY, projectId TEXT NOT NULL, label TEXT NOT NULL, position INTEGER NOT NULL, active INTEGER NOT NULL, colorIndex INTEGER NOT NULL, FOREIGN KEY(projectId) REFERENCES projects(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_options_projectId ON options(projectId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS reminder_times (projectId TEXT NOT NULL, minute INTEGER NOT NULL, PRIMARY KEY(projectId, minute), FOREIGN KEY(projectId) REFERENCES projects(id) ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS samples (id TEXT NOT NULL PRIMARY KEY, projectId TEXT NOT NULL, plannedAt INTEGER NOT NULL, submittedAt INTEGER, status TEXT NOT NULL, optionId TEXT, answeredAt INTEGER, localDate TEXT NOT NULL, localHour INTEGER NOT NULL, zoneId TEXT NOT NULL, eventAt INTEGER NOT NULL, answeredZoneId TEXT, FOREIGN KEY(projectId) REFERENCES projects(id) ON DELETE CASCADE, FOREIGN KEY(optionId) REFERENCES options(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_samples_projectId_eventAt ON samples(projectId, eventAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_samples_optionId ON samples(optionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_samples_projectId_localDate ON samples(projectId, localDate)")
                db.execSQL("CREATE TABLE IF NOT EXISTS metadata (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
            }
        }
    }
}
