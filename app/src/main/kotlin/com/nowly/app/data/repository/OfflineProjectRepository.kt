package com.nowly.app.data.repository

import androidx.room.withTransaction
import com.nowly.app.data.local.*
import com.nowly.app.projects.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class OfflineProjectRepository(private val database: NowlyDatabase) : ProjectRepository {
    val dao = database.projectDao()

    override fun observeProjects(): Flow<List<RecordProject>> =
        combine(dao.observeBundles(), dao.observePending()) { bundles, pending ->
            bundles.map { bundle -> bundle.toModel(pending.firstOrNull { it.projectId == bundle.project.id }?.id) }
        }

    suspend fun projects(): List<RecordProject> = database.withTransaction {
        val pending = dao.pending()
        dao.bundles().map { bundle -> bundle.toModel(pending.firstOrNull { it.projectId == bundle.project.id }?.id) }
    }

    override suspend fun saveProject(project: RecordProject) {
        dao.upsert(ProjectEntity(project.id, project.name, project.question, project.remindersEnabled, project.createdAtEpochMillis, project.ordered))
    }

    suspend fun initialize(now: Long) = database.withTransaction {
        if (dao.metadata("initialized") != null) return@withTransaction
        if (dao.bundles().isEmpty()) {
            val id = UUID.randomUUID().toString()
            dao.upsert(ProjectEntity(id, "心情", "此刻，你的心情怎么样？", true, now, true))
        }
        dao.bundles().forEach { bundle ->
            if (bundle.options.isEmpty()) dao.upsertOptions(defaultLabels.mapIndexed { index, label ->
                OptionEntity(UUID.randomUUID().toString(), bundle.project.id, label, index, true)
            })
            if (bundle.times.isEmpty()) dao.upsertTimes(defaultTimes.map { ReminderTimeEntity(bundle.project.id, it) })
        }
        dao.upsertMetadata(MetadataEntity("initialized", "1"))
    }

    suspend fun saveDraft(draft: ProjectDraft, now: Long) = database.withTransaction {
        require(DraftValidation.error(draft) == null) { DraftValidation.error(draft).orEmpty() }
        draft.options.forEach { option -> require(dao.option(option.id)?.projectId?.let { it == draft.id } != false) }
        val existing = dao.project(draft.id)
        dao.upsert(ProjectEntity(draft.id, draft.name.trim(), draft.question.trim(), existing?.remindersEnabled ?: true, existing?.createdAtEpochMillis ?: now, draft.ordered))
        val previousOptions = dao.options(draft.id).associateBy { it.id }
        var nextColor = (previousOptions.values.maxOfOrNull { it.colorIndex } ?: -1) + 1
        dao.deactivateOptions(draft.id)
        dao.upsertOptions(draft.options.mapIndexed { index, option ->
            OptionEntity(option.id, draft.id, option.label.trim(), index, true, previousOptions[option.id]?.colorIndex ?: nextColor++)
        })
        dao.clearTimes(draft.id)
        dao.upsertTimes(draft.times.sorted().map { ReminderTimeEntity(draft.id, it) })
    }

    suspend fun newOccurrence(projectId: String, plannedAt: Long, zone: ZoneId): SampleEntity? = database.withTransaction {
        if (dao.project(projectId)?.remindersEnabled != true) return@withTransaction null
        val id = "$projectId@$plannedAt"
        dao.sample(id)?.let { return@withTransaction it.takeIf { sample -> sample.status == SampleStatus.PENDING } }
        dao.supersede(projectId)
        val local = Instant.ofEpochMilli(plannedAt).atZone(zone)
        SampleEntity(id, projectId, plannedAt, null, SampleStatus.PENDING, null, null, local.toLocalDate().toString(), local.hour, zone.id, plannedAt)
            .also { dao.upsertSample(it) }
    }

    suspend fun answer(id: String, optionId: String, at: Long, zone: ZoneId): Boolean = database.withTransaction {
        val sample = dao.sample(id) ?: return@withTransaction false
        val option = dao.option(optionId) ?: return@withTransaction false
        if (sample.status != SampleStatus.PENDING || option.projectId != sample.projectId || !option.active) return@withTransaction false
        val local = Instant.ofEpochMilli(at).atZone(zone)
        dao.upsertSample(sample.copy(status = SampleStatus.ANSWERED, optionId = optionId, answeredAt = at,
            localDate = local.toLocalDate().toString(), localHour = local.hour, answeredZoneId = zone.id, eventAt = at))
        true
    }

    suspend fun claimFlag(key: String): Boolean = database.withTransaction {
        if (dao.metadata(key) != null) false else {
            dao.upsertMetadata(MetadataEntity(key, "1")); true
        }
    }

    private fun ProjectBundle.toModel(pendingId: String?) = RecordProject(
        project.id, project.name, project.question, project.remindersEnabled, project.createdAtEpochMillis,
        project.ordered, options.sortedWith(compareBy<OptionEntity> { it.position }.thenBy { it.id }).map { AnswerOption(it.id, it.label, it.position, it.active, it.colorIndex) },
        times.map { it.minute }.sorted(), pendingId,
    )

    companion object {
        val defaultLabels = listOf("很不好", "不太好", "一般", "不错", "很好")
        val defaultTimes = listOf(600, 720, 900, 1020, 1200)
    }
}
