package com.nowly.app.data.repository

import androidx.room.withTransaction
import com.nowly.app.data.local.*
import com.nowly.app.projects.*
import com.nowly.app.data.transfer.ProjectArchive
import com.nowly.app.data.transfer.ProjectArchiveCodec
import com.nowly.app.data.transfer.ProjectArchiveException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class OfflineProjectRepository(private val database: NowlyDatabase) : ProjectRepository {
    val dao = database.projectDao()

    override fun observeProjects(): Flow<List<RecordProject>> =
        combine(dao.observeBundles(), dao.observePending(), dao.observeSampleCounts()) { bundles, pending, counts ->
            val histories = counts.associate { it.projectId to it.count }
            bundles.map { bundle -> bundle.toModel(pending.firstOrNull { it.projectId == bundle.project.id }?.id, (histories[bundle.project.id] ?: 0) > 0) }
        }

    suspend fun projects(): List<RecordProject> = database.withTransaction {
        val pending = dao.pending()
        dao.bundles().map { bundle -> bundle.toModel(pending.firstOrNull { it.projectId == bundle.project.id }?.id, dao.sampleCount(bundle.project.id) > 0) }
    }

    suspend fun exportProject(id: String, exportedAt: Long): ProjectArchive = database.withTransaction {
        val bundle = dao.bundles().firstOrNull { it.project.id == id } ?: error("项目已不存在")
        ProjectArchive(version = 2, exportedAt = exportedAt, project = bundle.project,
            options = bundle.options.sortedWith(compareBy<OptionEntity> { it.position }.thenBy { it.id }),
            times = bundle.times.map { it.minute }.sorted(), samples = dao.allSamples(id))
    }

    suspend fun importProject(archive: ProjectArchive, replace: Boolean) = database.withTransaction {
        ProjectArchiveCodec.validate(archive)
        val id = archive.project.id
        if (!replace && dao.project(id) != null) throw ProjectArchiveException("同 ID 项目已存在，请重新导入并确认替换")
        // Reject foreign identities before deleting anything; never upsert into another project.
        archive.options.forEach { option ->
            if (dao.option(option.id)?.projectId?.let { it != id } == true)
                throw ProjectArchiveException("选项 ID 与其他项目冲突，未导入任何数据")
        }
        archive.samples.forEach { sample ->
            if (dao.sample(sample.id)?.projectId?.let { it != id } == true)
                throw ProjectArchiveException("记录 ID 与其他项目冲突，未导入任何数据")
        }
        dao.deleteProject(id)
        dao.upsert(archive.project)
        dao.upsertOptions(archive.options)
        dao.upsertTimes(archive.times.map { ReminderTimeEntity(id, it) })
        dao.upsertSamples(archive.samples)
        dao.upsertMetadata(MetadataEntity("initialized", "1"))
        // Recovery after a process interruption must rebuild, rather than preserve an old alarm.
        dao.upsertMetadata(MetadataEntity("alarm", ""))
    }

    override suspend fun saveProject(project: RecordProject) = database.withTransaction {
        val existing = dao.project(project.id)
        require(existing == null || existing.kind == project.kind)
        require(existing == null || dao.sampleCount(project.id) == 0 || existing.unit == project.unit)
        dao.upsert(ProjectEntity(project.id, project.name, project.question, project.remindersEnabled,
            existing?.createdAtEpochMillis ?: project.createdAtEpochMillis, true,
            project.kind, project.unit, project.reminderFrequency, project.reminderDays))
    }

    suspend fun initialize(now: Long) = database.withTransaction {
        // Legacy categories become ordered scales in their existing option order.
        // Preserve every option/sample identity when normalizing legacy scales.
        dao.useOrderedOptions()
        if (dao.metadata("initialized") != null) return@withTransaction
        if (dao.bundles().isEmpty()) {
            val id = UUID.randomUUID().toString()
            dao.upsert(ProjectEntity(id, "心情", "此刻，你的心情怎么样？", true, now, true))
        }
        dao.bundles().forEach { bundle ->
            if (bundle.project.kind == ProjectKind.CHOICE && bundle.options.isEmpty()) dao.upsertOptions(defaultLabels.mapIndexed { index, label ->
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
        if (existing != null && dao.sampleCount(draft.id) > 0) {
            require(existing.kind == draft.kind) { "已有记录，不能更改项目类型" }
            require(existing.unit == draft.unit.trim()) { "已有记录，不能更改单位" }
        }
        dao.upsert(ProjectEntity(draft.id, draft.name.trim(), draft.question.trim(),
            draft.remindersEnabled ?: existing?.remindersEnabled ?: true, existing?.createdAtEpochMillis ?: now, true,
            draft.kind, draft.unit.trim(), draft.reminderFrequency, draft.reminderDays))
        val previousOptions = dao.options(draft.id).associateBy { it.id }
        var nextColor = (previousOptions.values.maxOfOrNull { it.colorIndex } ?: -1) + 1
        dao.deactivateOptions(draft.id)
        dao.upsertOptions(draft.options.mapIndexed { index, option ->
            OptionEntity(option.id, draft.id, option.label.trim(), index, true, previousOptions[option.id]?.colorIndex ?: nextColor++)
        })
        if (draft.kind == ProjectKind.NUMBER) dao.deleteOptions(draft.id)
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
        if (dao.project(sample.projectId)?.kind != ProjectKind.CHOICE || sample.status != SampleStatus.PENDING || option.projectId != sample.projectId || !option.active) return@withTransaction false
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

    /** Save a measurement, complete the explicitly named reminder, or revise an existing answer. */
    suspend fun saveNumeric(projectId: String, input: String, measuredAt: Long, now: Long, zone: ZoneId,
        entryId: String, pendingId: String? = null, editing: SampleEntity? = null): String? = database.withTransaction {
        val project = dao.project(projectId) ?: return@withTransaction null
        if (project.kind != ProjectKind.NUMBER) return@withTransaction null
        val value = requireNotNull(NumericValues.normalize(input)) { NumericValues.ERROR }
        require(measuredAt <= now) { "测量时间不能晚于现在" }
        val previous = when {
            editing != null -> dao.sample(editing.id)?.takeIf {
                it == editing && it.projectId == projectId && it.status == SampleStatus.ANSWERED && it.numericValue != null
            } ?: return@withTransaction null
            pendingId != null -> dao.sample(pendingId)?.takeIf {
                it.projectId == projectId && it.status == SampleStatus.PENDING
            } ?: return@withTransaction null
            else -> null
        }
        if (previous == null) require(UUID.fromString(entryId).toString() == entryId) { "记录标识无效" }
        val id = previous?.id ?: "$projectId#$entryId"
        // A stable form identity makes retries idempotent even across Activity recreation.
        if (previous == null && dao.sample(id) != null) return@withTransaction null
        val local = Instant.ofEpochMilli(measuredAt).atZone(zone)
        val row = previous ?: SampleEntity(id, projectId, measuredAt, null, SampleStatus.ANSWERED, null, measuredAt,
            local.toLocalDate().toString(), local.hour, zone.id, measuredAt, zone.id, origin = SampleOrigin.MANUAL)
        dao.upsertSample(row.copy(status = SampleStatus.ANSWERED, optionId = null, numericValue = value,
            answeredAt = measuredAt, eventAt = measuredAt, localDate = local.toLocalDate().toString(),
            localHour = local.hour, answeredZoneId = zone.id,
            // Manual rows have no independent reminder time to retain when their date is corrected.
            plannedAt = if (row.origin == SampleOrigin.MANUAL) measuredAt else row.plannedAt,
            zoneId = if (row.origin == SampleOrigin.MANUAL) zone.id else row.zoneId))
        id
    }

    suspend fun deleteNumeric(expected: SampleEntity): Boolean = database.withTransaction {
        if (dao.project(expected.projectId)?.kind != ProjectKind.NUMBER ||
            expected.status != SampleStatus.ANSWERED || expected.numericValue == null || dao.sample(expected.id) != expected) return@withTransaction false
        dao.deleteSample(expected.id)
        true
    }

    private fun ProjectBundle.toModel(pendingId: String?, hasHistory: Boolean) = RecordProject(
        project.id, project.name, project.question, project.remindersEnabled, project.createdAtEpochMillis,
        project.ordered, options.sortedWith(compareBy<OptionEntity> { it.position }.thenBy { it.id }).map { AnswerOption(it.id, it.label, it.position, it.active, it.colorIndex) },
        times.map { it.minute }.sorted(), pendingId, project.kind, project.unit, project.reminderFrequency, project.reminderDays, hasHistory,
    )

    companion object {
        val defaultLabels = listOf("很不好", "不太好", "一般", "不错", "很好")
        val defaultTimes = listOf(600, 720, 900, 1020, 1200)
    }
}
