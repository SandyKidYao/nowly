package com.nowly.app.data

import com.nowly.app.data.local.*
import com.nowly.app.data.transfer.*
import com.nowly.app.projects.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class NumericArchiveTest {
    @Test fun readsOriginalVersionOneFilesWithNoNewFields() {
        val original = """{"format":"nowly.project","version":1,"exportedAt":2000,"project":{"id":"legacy","name":"心情","question":"如何？","remindersEnabled":true,"createdAtEpochMillis":0,"ordered":true},"options":[{"id":"a","projectId":"legacy","label":"低","position":0,"active":true,"colorIndex":0},{"id":"b","projectId":"legacy","label":"高","position":1,"active":true,"colorIndex":1}],"times":[600],"samples":[{"id":"legacy@0","projectId":"legacy","plannedAt":0,"submittedAt":500,"status":"answered","optionId":"a","answeredAt":1000,"localDate":"1970-01-01","localHour":0,"zoneId":"UTC","eventAt":1000,"answeredZoneId":"UTC"}]}"""
        val archive = ProjectArchiveCodec.decode(original.toByteArray())
        assertEquals(1, archive.version)
        assertEquals(ProjectKind.CHOICE, archive.project.kind)
        assertEquals(ReminderFrequency.DAILY, archive.project.reminderFrequency)
        assertEquals(127, archive.project.reminderDays)
        assertNull(archive.samples.single().numericValue)
        assertEquals(SampleOrigin.REMINDER, archive.samples.single().origin)
    }
    @Test fun numericVersionTwoRoundTripsAndRejectsAmbiguousValuesOrOldVersionTags() {
        val archive = ProjectArchive(exportedAt = 2000, project = ProjectEntity("weight", "体重", "多少？", false, 0, true,
            ProjectKind.NUMBER, "kg", ReminderFrequency.WEEKLY, 64), options = emptyList(), times = listOf(480),
            samples = listOf(SampleEntity("weight#${UUID.randomUUID()}", "weight", 1000, null, SampleStatus.ANSWERED,
                null, 1000, "1970-01-01", 0, "UTC", 1000, "UTC", "65.8", SampleOrigin.MANUAL)))
        assertEquals(archive, ProjectArchiveCodec.decode(ProjectArchiveCodec.encode(archive)))
        reject { ProjectArchiveCodec.validate(archive.copy(version = 1)) }
        reject { ProjectArchiveCodec.validate(archive.copy(samples = listOf(archive.samples.single().copy(numericValue = "NaN")))) }
        reject { ProjectArchiveCodec.validate(archive.copy(samples = listOf(archive.samples.single().copy(numericValue = null)))) }
        reject { ProjectArchiveCodec.validate(archive.copy(samples = listOf(archive.samples.single().copy(optionId = "a")))) }
    }
    private fun reject(block: () -> Unit) {
        try { block(); fail("Invalid archive accepted") } catch (_: ProjectArchiveException) { }
    }
}
