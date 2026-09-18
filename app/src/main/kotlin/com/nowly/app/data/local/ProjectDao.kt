package com.nowly.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAtEpochMillis ASC, id ASC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Transaction @Query("SELECT * FROM projects ORDER BY createdAtEpochMillis, id")
    fun observeBundles(): Flow<List<ProjectBundle>>
    @Transaction @Query("SELECT * FROM projects ORDER BY createdAtEpochMillis, id")
    suspend fun bundles(): List<ProjectBundle>
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun project(id: String): ProjectEntity?
    @Query("UPDATE projects SET remindersEnabled = :enabled WHERE id = :id") suspend fun setEnabled(id: String, enabled: Boolean)
    @Query("DELETE FROM projects WHERE id = :id") suspend fun deleteProject(id: String)
    @Upsert suspend fun upsertOptions(options: List<OptionEntity>)
    @Query("SELECT * FROM options WHERE projectId = :id") suspend fun options(id: String): List<OptionEntity>
    @Query("SELECT * FROM options WHERE id = :id") suspend fun option(id: String): OptionEntity?
    @Query("UPDATE options SET active = 0 WHERE projectId = :projectId") suspend fun deactivateOptions(projectId: String)
    @Query("DELETE FROM reminder_times WHERE projectId = :projectId") suspend fun clearTimes(projectId: String)
    @Upsert suspend fun upsertTimes(times: List<ReminderTimeEntity>)
    @Upsert suspend fun upsertSample(sample: SampleEntity)
    @Query("SELECT * FROM samples WHERE id = :id") suspend fun sample(id: String): SampleEntity?
    @Query("SELECT * FROM samples WHERE id = :id") fun observeSample(id: String): Flow<SampleEntity?>
    @Query("SELECT * FROM samples WHERE status = 'pending'") suspend fun pending(): List<SampleEntity>
    @Query("SELECT * FROM samples WHERE status = 'pending'") fun observePending(): Flow<List<SampleEntity>>
    @Query("UPDATE samples SET status = 'unprocessed' WHERE projectId = :projectId AND status = 'pending'") suspend fun supersede(projectId: String)
    @Query("UPDATE samples SET status = 'skipped' WHERE id = :id AND status = 'pending'") suspend fun skip(id: String): Int
    @Query("UPDATE samples SET submittedAt = :at WHERE id = :id AND status = 'pending'") suspend fun markSubmitted(id: String, at: Long)
    @Query("SELECT * FROM samples WHERE projectId = :projectId ORDER BY localDate DESC, eventAt DESC, id DESC LIMIT :limit")
    fun observeHistory(projectId: String, limit: Int): Flow<List<SampleEntity>>
    @Query("SELECT * FROM samples WHERE projectId = :projectId AND localDate >= :start AND localDate <= :end ORDER BY eventAt, id")
    fun observeRange(projectId: String, start: String, end: String): Flow<List<SampleEntity>>
    @Query("SELECT * FROM metadata WHERE `key` = :key") suspend fun metadata(key: String): MetadataEntity?
    @Upsert suspend fun upsertMetadata(value: MetadataEntity)
}
