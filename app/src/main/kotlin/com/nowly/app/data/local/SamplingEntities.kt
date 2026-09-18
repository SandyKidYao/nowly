package com.nowly.app.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "options", foreignKeys = [ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)], indices = [Index("projectId")])
data class OptionEntity(@PrimaryKey val id: String, val projectId: String, val label: String, val position: Int, val active: Boolean, val colorIndex: Int = position)

@Entity(tableName = "reminder_times", primaryKeys = ["projectId", "minute"], foreignKeys = [ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)])
data class ReminderTimeEntity(val projectId: String, val minute: Int)

@Entity(tableName = "samples", foreignKeys = [
    ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = OptionEntity::class, parentColumns = ["id"], childColumns = ["optionId"]),
], indices = [Index(value = ["projectId", "eventAt"]), Index("optionId"), Index(value = ["projectId", "localDate"])])
data class SampleEntity(
    @PrimaryKey val id: String, val projectId: String, val plannedAt: Long, val submittedAt: Long?,
    val status: String, val optionId: String?, val answeredAt: Long?,
    val localDate: String, val localHour: Int, val zoneId: String, val eventAt: Long,
    val answeredZoneId: String? = null,
)

object SampleStatus {
    const val PENDING = "pending"
    const val ANSWERED = "answered"
    const val SKIPPED = "skipped"
    const val UNPROCESSED = "unprocessed"
}

@Entity(tableName = "metadata")
data class MetadataEntity(@PrimaryKey val key: String, val value: String)

data class ProjectBundle(
    @Embedded val project: ProjectEntity,
    @Relation(parentColumn = "id", entityColumn = "projectId") val options: List<OptionEntity>,
    @Relation(parentColumn = "id", entityColumn = "projectId") val times: List<ReminderTimeEntity>,
)
