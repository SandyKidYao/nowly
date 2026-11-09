package com.nowly.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nowly.app.data.local.*
import com.nowly.app.data.repository.OfflineProjectRepository
import com.nowly.app.data.transfer.*
import com.nowly.app.platform.reminders.*
import com.nowly.app.projects.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectTransferTest {
    private lateinit var sourceDb: NowlyDatabase
    private lateinit var targetDb: NowlyDatabase
    private lateinit var source: OfflineProjectRepository
    private lateinit var target: OfflineProjectRepository
    private val zone = ZoneId.of("Asia/Shanghai")
    private val at = Instant.parse("2026-09-18T01:00:00Z").toEpochMilli()
    private fun draft() = ProjectDraft(UUID.randomUUID().toString(), "心情", "此刻如何？", true,
        listOf(AnswerOption(UUID.randomUUID().toString(), "低", 0), AnswerOption(UUID.randomUUID().toString(), "高", 1)), listOf(600, 1200))

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        sourceDb = Room.inMemoryDatabaseBuilder(context, NowlyDatabase::class.java).build()
        targetDb = Room.inMemoryDatabaseBuilder(context, NowlyDatabase::class.java).build()
        source = OfflineProjectRepository(sourceDb); target = OfflineProjectRepository(targetDb)
    }
    @After fun close() { sourceDb.close(); targetDb.close() }

    private suspend fun snapshot(): ProjectArchive {
        val d = draft(); source.saveDraft(d, 1)
        val answered = source.newOccurrence(d.id, at, zone)!!
        source.answer(answered.id, d.options.first().id, at + 60_000, ZoneId.of("America/Los_Angeles"))
        source.saveDraft(d.copy(options = listOf(d.options[1].copy(position = 0), AnswerOption(UUID.randomUUID().toString(), "新选项", 1))), 2)
        val skipped = source.newOccurrence(d.id, at + 120_000, zone)!!
        source.dao.skip(skipped.id)
        source.newOccurrence(d.id, at + 180_000, zone)
        val pending = source.newOccurrence(d.id, at + 240_000, zone)!!
        source.dao.markSubmitted(pending.id, at + 240_001)
        return ProjectArchiveCodec.decode(ProjectArchiveCodec.encode(source.exportProject(d.id, at + 300_000)))
    }

    @Test fun completeTransferAndConfirmedReplacementPreserveOtherProjectsAndDoNotDuplicateHistory() = runBlocking {
        val archive = snapshot()
        val other = draft(); target.saveDraft(other, 1)
        target.importProject(archive, replace = false)
        assertEquals(archive, target.exportProject(archive.project.id, archive.exportedAt))
        assertNotNull(target.dao.project(other.id))
        val extra = target.newOccurrence(archive.project.id, at + 600_000, zone)!!
        try { target.importProject(archive, replace = false); fail("Replacement requires confirmation") }
        catch (_: ProjectArchiveException) { }
        assertNotNull(target.dao.sample(extra.id))
        repeat(2) { target.importProject(archive, replace = true) }
        assertNull(target.dao.sample(extra.id))
        assertEquals(archive, target.exportProject(archive.project.id, archive.exportedAt))
        assertEquals(2, target.projects().size)
        target.initialize(999)
        assertEquals(2, target.projects().size)
    }

    @Test fun conflictingOptionIdentityIsRejectedWithoutChangingEitherProject() = runBlocking {
        val archive = snapshot(); val other = draft(); target.saveDraft(other, 1)
        target.importProject(archive, false)
        val conflicting = archive.copy(options = archive.options.mapIndexed { index, option ->
            if (index == 0) option.copy(id = other.options.first().id) else option
        }, samples = archive.samples.filter { it.optionId != archive.options.first().id })
        try { target.importProject(conflicting, true); fail("Foreign option identity must be rejected") }
        catch (_: ProjectArchiveException) { }
        assertEquals(archive, target.exportProject(archive.project.id, archive.exportedAt))
        assertEquals(other.options.first().id, target.dao.options(other.id).first { it.position == 0 }.id)
    }

    @Test fun writeFailureAfterDeletionRollsBackTheEntireReplacement() = runBlocking {
        val archive = snapshot(); target.importProject(archive, false)
        targetDb.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_samples BEFORE INSERT ON samples BEGIN SELECT RAISE(ABORT, 'simulated disk write failure'); END")
        try { target.importProject(archive.copy(project = archive.project.copy(name = "不应保存")), true); fail("Expected write failure") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(archive, target.exportProject(archive.project.id, archive.exportedAt))
    }

    @Test fun engineRestoresPendingSilentlyAndSchedulesOnlyFutureAlarms() = runBlocking {
        val archive = snapshot()
        val gateway = FakeGateway()
        val clock = Clock.fixed(Instant.ofEpochMilli(at + 86_400_000), zone)
        val engine = ReminderEngine(target, gateway, clock) { zone }
        engine.importProject(archive, false)
        assertTrue(gateway.scheduled!! > clock.millis())
        assertEquals(listOf(true), gateway.silent)
        assertEquals(archive, target.exportProject(archive.project.id, archive.exportedAt))
        val ticket = target.dao.metadata("alarm")!!.value
        val oldToken = ticket.substringBefore('|'); val oldTime = ticket.substringAfter('|').toLong()
        engine.importProject(archive.copy(project = archive.project.copy(remindersEnabled = false)), true)
        assertNull(gateway.scheduled)
        engine.fire(oldToken, oldTime)
        assertEquals(archive.samples.size, target.dao.allSamples(archive.project.id).size)
    }

    private class FakeGateway : ReminderGateway {
        var scheduled: Long? = null
        val silent = mutableListOf<Boolean>()
        private val visible = mutableSetOf<String>()
        override fun canNotify() = true
        override fun schedule(at: Long, token: String) { scheduled = at }
        override fun cancelAlarm() { scheduled = null }
        override fun publish(project: RecordProject, sample: SampleEntity, silent: Boolean) { this.silent += silent; visible += project.id }
        override fun cancelProject(projectId: String) { visible -= projectId }
        override fun visibleProjects(): Set<String> = visible.toSet()
    }
}
