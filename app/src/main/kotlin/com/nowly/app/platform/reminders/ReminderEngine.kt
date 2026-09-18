package com.nowly.app.platform.reminders

import com.nowly.app.data.local.MetadataEntity
import com.nowly.app.data.local.SampleEntity
import com.nowly.app.data.repository.OfflineProjectRepository
import com.nowly.app.projects.ProjectDraft
import com.nowly.app.projects.RecordProject
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ReminderGateway {
    fun canNotify(): Boolean
    fun schedule(at: Long, token: String)
    fun cancelAlarm()
    fun hasAlarmIntent(): Boolean = false
    fun publish(project: RecordProject, sample: SampleEntity, silent: Boolean)
    fun cancelProject(projectId: String)
    fun visibleProjects(): Set<String>
}

/** One process-wide writer serializes database transitions and notification effects. */
class ReminderEngine(
    private val repository: OfflineProjectRepository,
    private val gateway: ReminderGateway,
    private val clock: Clock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val mutex = Mutex()
    private val _issues = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val issues = _issues.asSharedFlow()

    suspend fun reconcile(rebuildSchedule: Boolean = false) = mutex.withLock {
        repository.initialize(clock.millis())
        synchronizeEffects(preserveExisting = !rebuildSchedule)
    }

    suspend fun save(draft: ProjectDraft) = mutex.withLock {
        repository.initialize(clock.millis())
        repository.saveDraft(draft, clock.millis())
        synchronizeEffects()
    }

    suspend fun enable(id: String, enabled: Boolean) = mutex.withLock {
        repository.dao.setEnabled(id, enabled)
        synchronizeEffects()
    }

    suspend fun delete(id: String) = mutex.withLock {
        repository.dao.deleteProject(id)
        cancelAfterCommit(id)
        synchronizeEffects()
    }

    suspend fun answer(id: String, optionId: String, at: Long, zoneAtTap: ZoneId = zone()): Boolean = mutex.withLock {
        val sample = repository.dao.sample(id)
        val changed = repository.answer(id, optionId, at, zoneAtTap)
        if (changed && sample != null) cancelAfterCommit(sample.projectId)
        changed
    }

    suspend fun skip(id: String) = mutex.withLock {
        val sample = repository.dao.sample(id)
        if (repository.dao.skip(id) == 1 && sample != null) cancelAfterCommit(sample.projectId)
    }

    suspend fun fire(token: String, plannedAt: Long) = mutex.withLock {
        repository.initialize(clock.millis())
        if (repository.dao.metadata("alarm")?.value != "$token|$plannedAt") return@withLock
        repository.dao.upsertMetadata(MetadataEntity("alarm", ""))
        val projects = repository.projects()
        // Establish the next future wake-up before posting current notifications.
        scheduleLocked(projects)
        if (gateway.canNotify()) {
            projects.filter { it.remindersEnabled && ReminderPlanner.isDue(Instant.ofEpochMilli(plannedAt), zone(), it.times) }
                .forEach { project ->
                    repository.newOccurrence(project.id, plannedAt, zone())?.let { sample ->
                        gateway.publish(project, sample, silent = sample.submittedAt != null)
                        repository.dao.markSubmitted(sample.id, clock.millis())
                    }
                }
        }
    }

    private fun cancelAfterCommit(projectId: String) {
        // The answer/skip is already durable. A system cleanup failure must not
        // be presented as a failed save; reconcile retries cleanup on recovery.
        try { gateway.cancelProject(projectId) }
        catch (error: Exception) { android.util.Log.w("Nowly", "Notification cleanup deferred", error) }
    }

    private suspend fun synchronizeEffects(preserveExisting: Boolean = false) {
        try { reconcileLocked(preserveExisting) }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (error: Exception) {
            android.util.Log.w("Nowly", "Reminder effects deferred until recovery", error)
            _issues.tryEmit("内容已保存在本机，但提醒暂时无法更新。重新打开应用会重试。")
        }
    }

    private suspend fun reconcileLocked(preserveExisting: Boolean) {
        val projects = repository.projects()
        scheduleLocked(projects, preserveExisting)
        val pending = repository.dao.pending()
        val pendingProjects = pending.map { it.projectId }.toSet()
        gateway.visibleProjects().filterNot { it in pendingProjects }.forEach(gateway::cancelProject)
        if (gateway.canNotify()) pending.forEach { sample ->
            projects.firstOrNull { it.id == sample.projectId }?.let { project ->
                gateway.publish(project, sample, silent = true)
                if (sample.submittedAt == null) repository.dao.markSubmitted(sample.id, clock.millis())
            }
        }
    }

    private suspend fun scheduleLocked(projects: List<RecordProject>, preserveExisting: Boolean = false) {
        val old = repository.dao.metadata("alarm")?.value.orEmpty().split('|')
        val oldAt = old.getOrNull(1)?.toLongOrNull()
        // Opening the UI must not cancel an inexact alarm which is already late
        // but has not delivered its broadcast yet. Preserve its identity; the
        // receiver consumes it once. Boot/clock changes and edits explicitly rebuild.
        if (preserveExisting && oldAt != null && old[0].isNotEmpty() && gateway.canNotify() &&
            projects.any { it.remindersEnabled && it.times.isNotEmpty() } && gateway.hasAlarmIntent() &&
            repository.dao.metadata("alarm_zone")?.value == zone().id) {
            gateway.schedule(oldAt, old[0])
            return
        }
        val at = if (gateway.canNotify()) ReminderPlanner.next(clock.instant(), zone(),
            projects.filter { it.remindersEnabled }.flatMap { it.times }.distinct())?.toEpochMilli() else null
        if (at == null) {
            repository.dao.upsertMetadata(MetadataEntity("alarm", ""))
            gateway.cancelAlarm()
        } else {
            val token = if (old.size == 2 && old[1] == at.toString()) old[0] else UUID.randomUUID().toString()
            repository.dao.upsertMetadata(MetadataEntity("alarm", "$token|$at"))
            repository.dao.upsertMetadata(MetadataEntity("alarm_zone", zone().id))
            gateway.schedule(at, token)
        }
    }
}
