package com.nowly.app.data

import com.nowly.app.data.local.*
import com.nowly.app.data.transfer.*
import java.io.InputStream
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ProjectArchiveTest {
    private val at = Instant.parse("2026-09-18T16:01:00Z").toEpochMilli()
    private fun archive() = ProjectArchive(exportedAt = at,
        project = ProjectEntity("project", "心情", "此刻如何？", false, 123, true),
        options = listOf(OptionEntity("old", "project", "旧选项", 0, false, 2),
            OptionEntity("low", "project", "低", 1, true, 0), OptionEntity("high", "project", "高", 0, true, 1)),
        times = listOf(600, 1200),
        samples = listOf(SampleEntity("project@$at", "project", at, at, SampleStatus.ANSWERED, "old", at,
            "2026-09-19", 0, "America/Los_Angeles", at, "Asia/Shanghai")))

    @Test fun roundTripPreservesRetiredOptionsIdentityOrderAndHistoricalZones() {
        val original = archive()
        assertEquals(original, ProjectArchiveCodec.decode(ProjectArchiveCodec.encode(original)))
        assertEquals(original, ProjectArchiveCodec.read(ProjectArchiveCodec.encode(original).inputStream()))
        assertEquals("nowly-心情_记录.nowly", ProjectArchiveCodec.fileName("心情/记录"))
    }

    @Test fun corruptCsvAndFutureVersionsAreRejected() {
        reject { ProjectArchiveCodec.decode(byteArrayOf(0xC3.toByte(), 0x28)) }
        reject { ProjectArchiveCodec.decode("日期,时间,选项\n2026-09-19,00:01,低".toByteArray()) }
        reject { ProjectArchiveCodec.decode(ProjectArchiveCodec.encode(archive()).copyOf(40)) }
        val future = ProjectArchiveCodec.encode(archive()).decodeToString().replace("\"version\":2", "\"version\":3")
        assertTrue(reject { ProjectArchiveCodec.decode(future.toByteArray()) }.message!!.contains("版本"))
        val missing = ProjectArchiveCodec.encode(archive()).decodeToString().replace("\"format\":\"nowly.project\",", "")
        reject { ProjectArchiveCodec.decode(missing.toByteArray()) }
    }

    @Test fun invalidReferencesDuplicatesAndInconsistentStatusAreRejected() {
        val a = archive()
        reject { ProjectArchiveCodec.validate(a.copy(samples = listOf(a.samples.single().copy(optionId = "missing")))) }
        reject { ProjectArchiveCodec.validate(a.copy(samples = a.samples + a.samples)) }
        reject { ProjectArchiveCodec.validate(a.copy(options = a.options + a.options.first())) }
        reject { ProjectArchiveCodec.validate(a.copy(times = listOf(600, 600))) }
        reject { ProjectArchiveCodec.validate(a.copy(samples = listOf(a.samples.single().copy(projectId = "other")))) }
        reject { ProjectArchiveCodec.validate(a.copy(samples = listOf(a.samples.single().copy(status = SampleStatus.SKIPPED)))) }
        reject { ProjectArchiveCodec.validate(a.copy(samples = listOf(a.samples.single().copy(localDate = "2026-09-18")))) }
    }

    @Test fun multiplePendingSamplesAreRejected() {
        val a = archive()
        val pending = a.samples.single().copy(status = SampleStatus.PENDING, optionId = null, answeredAt = null,
            answeredZoneId = null, zoneId = "Asia/Shanghai")
        reject { ProjectArchiveCodec.validate(a.copy(samples = listOf(pending, pending.copy(id = "project@${at + 1}", plannedAt = at + 1, eventAt = at + 1)))) }
    }

    @Test fun inputSizeIsBoundedEvenWhenProviderDoesNotReportSize() {
        val input = object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int { b.fill(0, off, off + len); return len }
        }
        assertTrue(reject { ProjectArchiveCodec.read(input) }.message!!.contains("32 MB"))
    }

    private fun reject(block: () -> Unit): ProjectArchiveException {
        try { block(); fail("Expected an invalid archive to be rejected") }
        catch (error: ProjectArchiveException) { return error }
        error("unreachable")
    }
}
