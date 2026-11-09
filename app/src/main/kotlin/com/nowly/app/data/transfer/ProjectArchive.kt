package com.nowly.app.data.transfer

import com.nowly.app.data.local.OptionEntity
import com.nowly.app.data.local.ProjectEntity
import com.nowly.app.data.local.SampleEntity
import com.nowly.app.data.local.SampleStatus
import com.nowly.app.data.local.SampleOrigin
import com.nowly.app.projects.ProjectKind
import com.nowly.app.projects.ReminderFrequency
import com.nowly.app.projects.NumericValues
import java.util.UUID
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Portable project snapshot. The file format version is independent of the Room schema. */
@Serializable
data class ProjectArchive(
    @Required val format: String = "nowly.project",
    @Required val version: Int = 2,
    val exportedAt: Long,
    val project: ProjectEntity,
    val options: List<OptionEntity>,
    val times: List<Int>,
    val samples: List<SampleEntity>,
)

class ProjectArchiveException(message: String) : IllegalArgumentException(message)

object ProjectArchiveCodec {
    const val MAX_BYTES = 32 * 1024 * 1024
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun fileName(name: String): String = "nowly-${name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")}.nowly"

    fun encode(archive: ProjectArchive): ByteArray {
        validate(archive)
        return json.encodeToString(archive).toByteArray(Charsets.UTF_8).also { checkSize(it.size) }
    }

    fun read(input: InputStream): ProjectArchive {
        // Bound memory before parsing, including providers that do not report a file size.
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            checkSize(output.size() + count)
            output.write(buffer, 0, count)
        }
        return decode(output.toByteArray())
    }

    fun decode(bytes: ByteArray): ProjectArchive {
        checkSize(bytes.size)
        return try {
            json.decodeFromString<ProjectArchive>(bytes.decodeToString(throwOnInvalidSequence = true)).also(::validate)
        } catch (error: ProjectArchiveException) {
            throw error
        } catch (_: SerializationException) {
            throw ProjectArchiveException("无法识别项目文件，请选择 Nowly 导出的 .nowly 文件")
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw ProjectArchiveException("项目文件编码已损坏，未导入任何数据")
        } catch (_: IllegalArgumentException) {
            throw ProjectArchiveException("项目文件内容不完整或不合法，未导入任何数据")
        }
    }

    private fun checkSize(size: Int) {
        if (size > MAX_BYTES) throw ProjectArchiveException("项目文件超过 32 MB，无法导入或导出")
    }

    fun validate(archive: ProjectArchive) {
        if (archive.format != "nowly.project") throw ProjectArchiveException("这不是 Nowly 项目文件")
        if (archive.version !in 1..2) throw ProjectArchiveException("暂不支持此项目文件版本，请更新 Nowly 后重试")
        try {
            val project = archive.project
            require(project.id.isNotBlank() && project.id.length <= 200)
            require(project.name.trim().length in 1..40 && project.question.trim().length in 1..160)
            require(project.ordered)
            require(project.kind in setOf(ProjectKind.CHOICE, ProjectKind.NUMBER))
            val numeric = project.kind == ProjectKind.NUMBER
            require(project.unit.trim() == project.unit && project.unit.length <= 16)
            require(numeric || project.unit.isEmpty())
            require(project.reminderFrequency in setOf(ReminderFrequency.DAILY, ReminderFrequency.WEEKLY))
            require(project.reminderDays in 1..127)
            if (archive.version == 1) require(!numeric && project.reminderFrequency == ReminderFrequency.DAILY && project.reminderDays == 127)
            if (numeric) require(archive.options.isEmpty()) else require(archive.options.count { it.active } in 2..20)
            require(archive.options.map { it.id }.distinct().size == archive.options.size)
            require(archive.options.filter { it.active }.map { it.position }.distinct().size == archive.options.count { it.active })
            archive.options.forEach {
                require(it.projectId == project.id && it.id.isNotBlank() && it.id.length <= 200)
                require(it.label.trim().length in 1..30 && it.position >= 0 && it.colorIndex >= 0)
            }
            require(archive.times.size in 1..24 && archive.times.distinct().size == archive.times.size)
            require(archive.times.all { it in 0..1439 })
            require(archive.samples.map { it.id }.distinct().size == archive.samples.size)
            require(archive.samples.count { it.status == SampleStatus.PENDING } <= 1)
            val options = archive.options.associateBy { it.id }
            archive.samples.forEach {
                require(it.projectId == project.id)
                require(it.origin in setOf(SampleOrigin.REMINDER, SampleOrigin.MANUAL))
                if (it.origin == SampleOrigin.REMINDER) require(it.id == "${project.id}@${it.plannedAt}")
                else {
                    require(numeric && it.id.startsWith("${project.id}#"))
                    require(UUID.fromString(it.id.substringAfterLast('#')).toString() == it.id.substringAfterLast('#'))
                    require(it.status == SampleStatus.ANSWERED && it.plannedAt == it.answeredAt && it.zoneId == it.answeredZoneId)
                }
                if (archive.version == 1) require(it.origin == SampleOrigin.REMINDER && it.numericValue == null)
                require(it.status in setOf(SampleStatus.PENDING, SampleStatus.ANSWERED, SampleStatus.SKIPPED, SampleStatus.UNPROCESSED))
                ZoneId.of(it.zoneId)
                val answered = it.status == SampleStatus.ANSWERED
                if (numeric) {
                    require(it.optionId == null && answered == (it.numericValue != null))
                    it.numericValue?.let { value -> require(NumericValues.normalize(value) == value) }
                } else require(answered == (it.optionId != null) && it.numericValue == null)
                require(answered == (it.answeredAt != null))
                require(answered == (it.answeredZoneId != null))
                if (answered && !numeric) require(options.containsKey(it.optionId))
                val eventAt = if (answered) it.answeredAt!! else it.plannedAt
                require(it.eventAt == eventAt)
                val local = Instant.ofEpochMilli(eventAt).atZone(ZoneId.of(it.answeredZoneId ?: it.zoneId))
                require(LocalDate.parse(it.localDate) == local.toLocalDate() && it.localHour == local.hour)
            }
        } catch (_: RuntimeException) {
            throw ProjectArchiveException("项目文件内容不完整或不合法，未导入任何数据")
        }
    }
}
