package com.nowly.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nowly.app.data.local.*
import com.nowly.app.data.repository.OfflineProjectRepository
import com.nowly.app.data.transfer.ProjectArchiveCodec
import com.nowly.app.platform.reminders.*
import com.nowly.app.projects.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NumericRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: NowlyDatabase
    private lateinit var repo: OfflineProjectRepository
    private val zone = ZoneId.of("Asia/Shanghai")
    private val at = Instant.parse("2026-11-08T00:00:00Z").toEpochMilli()
    private val now = at + 86_400_000
    private fun token() = UUID.randomUUID().toString()
    private fun draft() = ProjectDraft(token(), "体重", "多少？", true, emptyList(), listOf(480), true,
        ProjectKind.NUMBER, "kg", ReminderFrequency.WEEKLY, 64)
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, NowlyDatabase::class.java).build(); repo = OfflineProjectRepository(db) }
    @After fun close() { db.close() }

    @Test fun manualMeasurementsKeepExactValuesAllowSameDayAndRetryDoesNotDuplicate() = runBlocking {
        val p = draft(); repo.saveDraft(p, at)
        val entry = token()
        val id = repo.saveNumeric(p.id, "65.800", at, now, zone, entry)!!
        assertEquals("65.8", repo.dao.sample(id)!!.numericValue)
        assertEquals(SampleOrigin.MANUAL, repo.dao.sample(id)!!.origin)
        assertNull(repo.saveNumeric(p.id, "65.800", at, now, zone, entry))
        repo.saveNumeric(p.id, "65.70", at + 60_000, now, zone, token())
        assertEquals(2, repo.dao.allSamples(p.id).size)
        assertTrue(repo.projects().single().hasHistory)
        assertTrue(repo.dao.options(p.id).isEmpty())
        repo.initialize(now)
        assertTrue(repo.dao.options(p.id).isEmpty())
        assertEquals("kg", repo.projects().single().unit)
    }

    @Test fun backfillKeepsPendingWhileNormalMeasurementCompletesOnlyThatReminder() = runBlocking {
        val p = draft(); repo.saveDraft(p, at)
        val pending = repo.newOccurrence(p.id, now - 60_000, zone)!!
        val backfill = repo.saveNumeric(p.id, "66", at, now, zone, token())!!
        assertEquals("2026-11-08", repo.dao.sample(backfill)!!.localDate)
        assertEquals(SampleStatus.PENDING, repo.dao.sample(pending.id)!!.status)
        val gateway = FakeGateway()
        val engine = ReminderEngine(repo, gateway, Clock.fixed(Instant.ofEpochMilli(now), zone)) { zone }
        assertTrue(engine.saveNumeric(p.id, "65.8", now, zone, token(), pending.id))
        val answered = repo.dao.sample(pending.id)!!
        assertEquals(pending.plannedAt, answered.plannedAt)
        assertEquals(SampleOrigin.REMINDER, answered.origin)
        assertEquals(now, answered.answeredAt)
        assertEquals("65.8", answered.numericValue)
        assertTrue(p.id in gateway.cancelled)
        assertFalse(engine.saveNumeric(p.id, "65.8", now, zone, token(), pending.id))
        assertEquals(2, repo.dao.allSamples(p.id).size)
    }

    @Test fun staleReminderCannotAnswerTheReplacementAndFutureOrInvalidInputWritesNothing() = runBlocking {
        val p = draft(); repo.saveDraft(p, at)
        val old = repo.newOccurrence(p.id, at, zone)!!
        val latest = repo.newOccurrence(p.id, at + 60_000, zone)!!
        assertNull(repo.saveNumeric(p.id, "65", now, now, zone, token(), old.id))
        assertEquals(SampleStatus.PENDING, repo.dao.sample(latest.id)!!.status)
        for ((value, time) in listOf("NaN" to now, "1.1234567" to now, "65" to (now + 1))) {
            try { repo.saveNumeric(p.id, value, time, now, zone, token()); fail("Invalid measurement accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(2, repo.dao.allSamples(p.id).size)
    }

    @Test fun historyCanBeCorrectedAndDeletedButStaleEditorsCannotOverwriteNewData() = runBlocking {
        val p = draft(); repo.saveDraft(p, at)
        val id = repo.saveNumeric(p.id, "65.8", at, now, zone, token())!!
        val original = repo.dao.sample(id)!!
        assertEquals(id, repo.saveNumeric(p.id, "65.25", at - 86_400_000, now, zone, token(), editing = original))
        val corrected = repo.dao.sample(id)!!
        assertEquals("65.25", corrected.numericValue)
        assertEquals("2026-11-07", corrected.localDate)
        assertEquals(corrected.plannedAt, corrected.eventAt)
        assertNull(repo.saveNumeric(p.id, "70", at, now, zone, token(), editing = original))
        assertFalse(repo.deleteNumeric(original))
        assertTrue(repo.deleteNumeric(corrected))
        assertNull(repo.dao.sample(id))
    }

    @Test fun projectTypeAndUnitAreLockedOnceHistoryExists() = runBlocking {
        val p = draft(); repo.saveDraft(p, at)
        repo.saveNumeric(p.id, "65.8", at, now, zone, token())
        try { repo.saveDraft(p.copy(unit = "lb"), now); fail("Unit changed beneath history") }
        catch (_: IllegalArgumentException) { }
        val choices = listOf(AnswerOption(token(), "低", 0), AnswerOption(token(), "高", 1))
        try { repo.saveDraft(p.copy(kind = ProjectKind.CHOICE, unit = "", options = choices), now); fail("Type changed beneath history") }
        catch (_: IllegalArgumentException) { }
        assertEquals("kg", repo.dao.project(p.id)!!.unit)
        assertEquals(ProjectKind.NUMBER, repo.dao.project(p.id)!!.kind)
    }

    @Test fun numericProjectRoundTripsThroughVersionTwoIncludingManualAndReminderAnswers() = runBlocking {
        val p = draft(); repo.saveDraft(p, at)
        repo.saveNumeric(p.id, "66.1", at, now, zone, token())
        val pending = repo.newOccurrence(p.id, now - 60_000, zone)!!
        repo.saveNumeric(p.id, "65.8", now, now, ZoneId.of("America/Los_Angeles"), token(), pending.id)
        val archive = ProjectArchiveCodec.decode(ProjectArchiveCodec.encode(repo.exportProject(p.id, now)))
        assertEquals(2, archive.version)
        val targetDb = Room.inMemoryDatabaseBuilder(context, NowlyDatabase::class.java).build()
        try {
            val target = OfflineProjectRepository(targetDb)
            target.importProject(archive, false)
            assertEquals(archive, target.exportProject(p.id, now))
            target.importProject(archive, true)
            assertEquals(2, target.dao.allSamples(p.id).size)
        } finally { targetDb.close() }
    }

    @Test fun globalAlarmRespectsEachProjectsWeekdaysInsteadOfFlatteningAllTimes() = runBlocking {
        val weekly = draft(); repo.saveDraft(weekly, at)
        val daily = ProjectDraft(token(), "心情", "如何？", true,
            listOf(AnswerOption(token(), "低", 0), AnswerOption(token(), "高", 1)), listOf(540))
        repo.saveDraft(daily, at)
        val clock = MutableClock(Instant.parse("2026-11-02T00:00:00Z"))
        val gateway = FakeGateway()
        val engine = ReminderEngine(repo, gateway, clock) { zone }
        engine.reconcile()
        val ticket = gateway.scheduled!!
        assertEquals(Instant.parse("2026-11-02T01:00:00Z").toEpochMilli(), ticket.first)
        clock.current = Instant.ofEpochMilli(ticket.first)
        engine.fire(ticket.second, ticket.first)
        assertEquals(1, repo.dao.allSamples(daily.id).size)
        assertTrue(repo.dao.allSamples(weekly.id).isEmpty())
        engine.enable(daily.id, false)
        val sunday = gateway.scheduled!!
        assertEquals(Instant.parse("2026-11-08T00:00:00Z").toEpochMilli(), sunday.first)
        clock.current = Instant.ofEpochMilli(sunday.first)
        engine.fire(sunday.second, sunday.first)
        assertEquals(1, repo.dao.allSamples(weekly.id).size)
    }

    @Test fun migrationFromVersionTwoPreservesExistingChoicesAndPendingHistory() = runBlocking {
        val name = "numeric-migration-${token()}.db"
        val file = context.getDatabasePath(name); file.parentFile!!.mkdirs()
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.nowly.app.data.local.NowlyDatabase/2.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices")
                for (i in 0 until (indices?.length() ?: 0)) old.execSQL(indices!!.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO projects VALUES ('legacy','心情','如何？',1,0,1)")
            old.execSQL("INSERT INTO options VALUES ('a','legacy','低',0,1,0),('b','legacy','高',1,1,1)")
            old.execSQL("INSERT INTO reminder_times VALUES ('legacy',600)")
            old.execSQL("INSERT INTO samples VALUES ('legacy@0','legacy',0,500,'answered','a',1000,'1970-01-01',0,'UTC',1000,'UTC')")
            old.execSQL("INSERT INTO samples VALUES ('legacy@60000','legacy',60000,60001,'pending',NULL,NULL,'1970-01-01',0,'UTC',60000,NULL)")
            old.version = 2
        }
        val migrated = Room.databaseBuilder(context, NowlyDatabase::class.java, name).addMigrations(NowlyDatabase.MIGRATION_2_3).build()
        try {
            val target = OfflineProjectRepository(migrated)
            val p = target.dao.project("legacy")!!
            assertEquals(ProjectKind.CHOICE, p.kind); assertEquals("", p.unit)
            assertEquals(ReminderFrequency.DAILY, p.reminderFrequency); assertEquals(127, p.reminderDays)
            val answer = target.dao.sample("legacy@0")!!
            assertEquals("a", answer.optionId); assertEquals(1000L, answer.answeredAt); assertNull(answer.numericValue)
            assertEquals(SampleOrigin.REMINDER, answer.origin)
            assertEquals(SampleStatus.PENDING, target.dao.sample("legacy@60000")!!.status)
            target.initialize(now)
            assertEquals(listOf("legacy"), target.projects().map { it.id })
            assertEquals(2, target.dao.allSamples("legacy").size)
            assertEquals(2, target.dao.options("legacy").size)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    private class MutableClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current
    }
    private class FakeGateway : ReminderGateway {
        var scheduled: Pair<Long, String>? = null
        val cancelled = mutableSetOf<String>()
        override fun canNotify() = true
        override fun schedule(at: Long, token: String) { scheduled = at to token }
        override fun cancelAlarm() { scheduled = null }
        override fun publish(project: RecordProject, sample: SampleEntity, silent: Boolean) { }
        override fun cancelProject(projectId: String) { cancelled += projectId }
        override fun visibleProjects() = emptySet<String>()
    }
}
