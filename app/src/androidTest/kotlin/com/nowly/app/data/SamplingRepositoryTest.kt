package com.nowly.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nowly.app.data.local.*
import com.nowly.app.data.repository.OfflineProjectRepository
import com.nowly.app.platform.reminders.*
import com.nowly.app.projects.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SamplingRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: NowlyDatabase
    private lateinit var repo: OfflineProjectRepository
    private val zone = ZoneId.of("Asia/Shanghai")
    private val planned = Instant.parse("2026-09-18T15:59:00Z").toEpochMilli()
    private fun draft(id: String = UUID.randomUUID().toString()) = ProjectDraft(id, "压力", "此刻如何？", true,
        listOf(AnswerOption(UUID.randomUUID().toString(), "低", 0), AnswerOption(UUID.randomUUID().toString(), "高", 1)), listOf(600, 720))
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, NowlyDatabase::class.java).build(); repo = OfflineProjectRepository(db) }
    @After fun teardown() { db.close() }

    @Test fun initializationIsAtomicIdempotentAndDoesNotRecreateDeletedProjects() = runBlocking {
        coroutineScope { repeat(4) { launch { repo.initialize(100) } } }
        val projects = repo.projects()
        assertEquals(1, projects.size); assertEquals(5, projects.single().options.size)
        assertEquals(5, projects.single().times.size); assertTrue(projects.single().remindersEnabled)
        repo.dao.deleteProject(projects.single().id)
        repo.initialize(200)
        assertTrue(repo.projects().isEmpty())
    }

    @Test fun answerUsesTapDateAndRenameAndDeactivationKeepHistoricalIdentity() = runBlocking {
        val draft = draft(); repo.saveDraft(draft, 1)
        val sample = repo.newOccurrence(draft.id, planned, zone)!!
        val answerAt = Instant.parse("2026-09-18T16:01:00Z").toEpochMilli()
        assertTrue(repo.answer(sample.id, draft.options.first().id, answerAt, zone))
        val renamed = draft.copy(options = draft.options.mapIndexed { index, option -> if (index == 0) option.copy(label = "轻微") else option })
        repo.saveDraft(renamed, 2)
        val saved = repo.dao.sample(sample.id)!!
        assertEquals("2026-09-19", saved.localDate); assertEquals(0, saved.localHour)
        assertEquals(answerAt, saved.answeredAt); assertEquals(planned, saved.plannedAt)
        assertEquals("轻微", repo.projects().single().options.first { it.id == saved.optionId }.label)
        repo.saveDraft(renamed.copy(options = listOf(renamed.options[1], AnswerOption(UUID.randomUUID().toString(), "中", 1))), 3)
        assertFalse(repo.dao.option(saved.optionId!!)!!.active)
        assertEquals(saved, repo.dao.sample(sample.id))
        assertEquals(1, repo.dao.observeRange(draft.id, "2026-09-19", "2026-09-19").first().size)
        assertEquals(0, repo.dao.observeRange(draft.id, "2026-09-18", "2026-09-18").first().size)
    }

    @Test fun replacementAndLateDismissalCannotChangeTheNewPendingInstance() = runBlocking {
        val draft = draft(); repo.saveDraft(draft, 1)
        val old = repo.newOccurrence(draft.id, planned, zone)!!
        val next = repo.newOccurrence(draft.id, planned + 60_000, zone)!!
        assertEquals(SampleStatus.UNPROCESSED, repo.dao.sample(old.id)?.status)
        assertEquals(0, repo.dao.skip(old.id))
        assertFalse(repo.answer(old.id, draft.options[0].id, planned + 90_000, zone))
        assertEquals(next.id, repo.dao.pending().single().id)
        assertEquals(next.id, repo.newOccurrence(draft.id, planned + 60_000, zone)?.id)
        assertEquals(2, repo.dao.observeHistory(draft.id, 30).first().size)
    }

    @Test fun answerAndSkipRaceCanOnlyProduceOneTerminalOutcome() = runBlocking {
        val draft = draft(); repo.saveDraft(draft, 1)
        val sample = repo.newOccurrence(draft.id, planned, zone)!!
        coroutineScope {
            launch { repo.answer(sample.id, draft.options[0].id, planned + 1000, zone) }
            launch { repo.dao.skip(sample.id) }
        }
        val result = repo.dao.sample(sample.id)!!
        assertTrue(result.status in setOf(SampleStatus.ANSWERED, SampleStatus.SKIPPED))
        assertEquals(result.status == SampleStatus.ANSWERED, result.optionId != null)
        assertFalse(repo.answer(sample.id, draft.options[1].id, planned + 2000, zone))
        assertEquals(0, repo.dao.skip(sample.id))
        assertEquals(result, repo.dao.sample(sample.id))
    }

    @Test fun cannotAnswerWithAnotherProjectsOptionAndDeletionCascadesSafely() = runBlocking {
        val a = draft(); val b = draft(); repo.saveDraft(a, 1); repo.saveDraft(b, 2)
        val sample = repo.newOccurrence(a.id, planned, zone)!!
        assertFalse(repo.answer(sample.id, b.options[0].id, planned + 1000, zone))
        assertTrue(repo.answer(sample.id, a.options[0].id, planned + 1000, zone))
        repo.dao.deleteProject(a.id)
        assertNull(repo.dao.sample(sample.id)); assertNull(repo.dao.option(a.options[0].id))
        assertEquals(b.id, repo.projects().single().id)
    }

    @Test fun pausingOnlyStopsFutureSamplingAndTimeAloneNeverExpiresPending() = runBlocking {
        val draft = draft(); repo.saveDraft(draft, 1)
        val sample = repo.newOccurrence(draft.id, planned, zone)!!
        val gateway = FakeGateway()
        val clock = MutableClock(Instant.ofEpochMilli(planned + 86_400_000))
        val engine = ReminderEngine(repo, gateway, clock) { zone }
        engine.enable(draft.id, false)
        assertNull(gateway.scheduled)
        assertEquals(SampleStatus.PENDING, repo.dao.sample(sample.id)?.status)
        assertTrue(engine.answer(sample.id, draft.options[0].id, clock.millis()))
        assertTrue(repo.dao.pending().isEmpty())
    }

    @Test fun committedPendingNotificationIsRecoveredAfterPostingFailure() = runBlocking {
        repo.initialize(1)
        val clock = MutableClock(Instant.parse("2026-09-18T01:59:00Z"))
        val gateway = FakeGateway()
        val engine = ReminderEngine(repo, gateway, clock) { zone }
        engine.reconcile()
        val (at, token) = gateway.scheduled!!
        clock.current = Instant.ofEpochMilli(at)
        gateway.failPublish = true
        try { engine.fire(token, at); fail("Posting failure should be observable") } catch (_: IllegalStateException) { }
        val pending = repo.dao.pending().single()
        assertNull(pending.submittedAt)
        gateway.failPublish = false
        engine.reconcile()
        assertTrue(gateway.visible.contains(pending.projectId))
        assertNotNull(repo.dao.sample(pending.id)?.submittedAt)
        engine.fire(token, at)
        assertEquals(1, repo.dao.observeHistory(pending.projectId, 30).first().size)
    }

    @Test fun databaseV1MigrationKeepsExistingData() = runBlocking {
        val name = "migration-test-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name); file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE projects (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, question TEXT NOT NULL, remindersEnabled INTEGER NOT NULL, createdAtEpochMillis INTEGER NOT NULL)")
            old.execSQL("INSERT INTO projects VALUES ('original', '已有项目', '原问题', 1, 1234)")
            old.version = 1
        }
        val migrated = Room.databaseBuilder(context, NowlyDatabase::class.java, name).addMigrations(NowlyDatabase.MIGRATION_1_2).build()
        try {
            val old = migrated.projectDao().project("original")!!
            assertEquals("已有项目", old.name); assertEquals(1234L, old.createdAtEpochMillis)
            val repository = OfflineProjectRepository(migrated); repository.initialize(9000)
            assertEquals("original", repository.projects().single().id)
            assertEquals(5, repository.projects().single().options.size)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    @Test fun savingBeforeTheFirstHomeStillCreatesThePresetOnce() = runBlocking {
        val engine = ReminderEngine(repo, FakeGateway(), Clock.fixed(Instant.ofEpochMilli(planned), zone)) { zone }
        val custom = draft()
        engine.save(custom)
        engine.reconcile()
        assertEquals(2, repo.projects().size)
        assertEquals(1, repo.projects().count { it.name == "心情" })
    }

    @Test fun committedEditIsNotReportedAsFailedWhenSystemNotificationUpdateFails() = runBlocking {
        val custom = draft(); repo.saveDraft(custom, 1)
        repo.newOccurrence(custom.id, planned, zone)
        val gateway = FakeGateway().apply { failPublish = true }
        val engine = ReminderEngine(repo, gateway, Clock.fixed(Instant.ofEpochMilli(planned), zone)) { zone }
        val diagnostic = async(start = CoroutineStart.UNDISPATCHED) { engine.issues.first() }
        engine.save(custom.copy(name = "已保存的名称"))
        assertEquals("已保存的名称", repo.projects().single().name)
        assertTrue(diagnostic.await().contains("已保存在本机"))
    }

    @Test fun travelKeepsOriginalReminderZoneAndGroupsHistoryBySavedLocalDate() = runBlocking {
        val custom = draft(); repo.saveDraft(custom, 1)
        val first = repo.newOccurrence(custom.id, planned, zone)!!
        repo.answer(first.id, custom.options[0].id, planned + 120_000, zone)
        val second = repo.newOccurrence(custom.id, planned + 240_000, zone)!!
        val destination = ZoneId.of("America/Los_Angeles")
        repo.answer(second.id, custom.options[1].id, planned + 300_000, destination)
        val saved = repo.dao.sample(second.id)!!
        assertEquals(zone.id, saved.zoneId)
        assertEquals(destination.id, saved.answeredZoneId)
        assertEquals("2026-09-18", saved.localDate)
        assertEquals(listOf(first.id, second.id), repo.dao.observeHistory(custom.id, 30).first().map { it.id })
    }

    @Test fun openingTheAppDoesNotDiscardAnAlreadyLateAlarm() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-09-18T01:59:00Z"))
        val gateway = FakeGateway()
        val engine = ReminderEngine(repo, gateway, clock) { zone }
        engine.reconcile()
        val ticket = gateway.scheduled!!
        clock.current = Instant.ofEpochMilli(ticket.first + 120_000)
        engine.reconcile()
        assertEquals(ticket, gateway.scheduled)
        engine.fire(ticket.second, ticket.first)
        assertEquals(ticket.first, repo.dao.pending().single().plannedAt)
    }

    @Test fun rebootRecoveryRebuildsFutureAlarmsWithoutInventingMissedSamples() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-09-18T01:59:00Z"))
        val gateway = FakeGateway()
        val engine = ReminderEngine(repo, gateway, clock) { zone }
        engine.reconcile()
        val old = gateway.scheduled!!
        clock.current = Instant.ofEpochMilli(old.first + 120_000)
        gateway.cancelAlarm()
        engine.reconcile(rebuildSchedule = true)
        assertTrue(gateway.scheduled!!.first > clock.millis())
        engine.fire(old.second, old.first)
        assertTrue(repo.dao.pending().isEmpty())
    }

    private class MutableClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current
    }
    private class FakeGateway : ReminderGateway {
        var scheduled: Pair<Long, String>? = null
        var failPublish = false
        val visible = mutableSetOf<String>()
        override fun canNotify() = true
        override fun schedule(at: Long, token: String) { scheduled = at to token }
        override fun cancelAlarm() { scheduled = null }
        override fun hasAlarmIntent() = scheduled != null
        override fun publish(project: RecordProject, sample: SampleEntity, silent: Boolean) {
            if (failPublish) throw IllegalStateException("Simulated process interruption before posting")
            visible.add(project.id)
        }
        override fun cancelProject(projectId: String) { visible.remove(projectId) }
        override fun visibleProjects(): Set<String> = visible.toSet()
    }
}
