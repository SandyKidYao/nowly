package com.nowly.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val question: String,
    val remindersEnabled: Boolean,
    val createdAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "0") val ordered: Boolean = false,
    @ColumnInfo(defaultValue = "'choice'") val kind: String = "choice",
    @ColumnInfo(defaultValue = "''") val unit: String = "",
    @ColumnInfo(defaultValue = "'daily'") val reminderFrequency: String = "daily",
    @ColumnInfo(defaultValue = "127") val reminderDays: Int = 127,
)
