package com.nowly.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val question: String,
    val remindersEnabled: Boolean,
    val createdAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "0") val ordered: Boolean = false,
)
