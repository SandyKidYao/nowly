package com.nowly.app.projects

/** Identity is independent of the editable project name. */
data class RecordProject(
    val id: String,
    val name: String,
    val question: String,
    val remindersEnabled: Boolean,
    val createdAtEpochMillis: Long,
    val ordered: Boolean = true,
    val options: List<AnswerOption> = emptyList(),
    val times: List<Int> = emptyList(),
    val pendingId: String? = null,
    val kind: String = ProjectKind.CHOICE,
    val unit: String = "",
    val reminderFrequency: String = ReminderFrequency.DAILY,
    val reminderDays: Int = 127,
    val hasHistory: Boolean = false,
)

object ProjectKind { const val CHOICE = "choice"; const val NUMBER = "number" }
object ReminderFrequency { const val DAILY = "daily"; const val WEEKLY = "weekly" }

data class AnswerOption(val id: String, val label: String, val position: Int, val active: Boolean = true, val colorIndex: Int = position)
data class ProjectDraft(val id: String, val name: String, val question: String, val ordered: Boolean,
    val options: List<AnswerOption>, val times: List<Int>, val remindersEnabled: Boolean? = null,
    val kind: String = ProjectKind.CHOICE, val unit: String = "",
    val reminderFrequency: String = ReminderFrequency.DAILY, val reminderDays: Int = 127)

object DraftValidation {
    fun error(draft: ProjectDraft): String? = when {
        draft.name.trim().length !in 1..40 -> "名称需要 1–40 个字"
        draft.question.trim().length !in 1..160 -> "问题需要 1–160 个字"
        draft.kind !in setOf(ProjectKind.CHOICE, ProjectKind.NUMBER) -> "请选择有效的项目类型"
        draft.kind == ProjectKind.CHOICE && draft.options.size !in 2..20 -> "请保留 2–20 个回答选项"
        draft.kind == ProjectKind.NUMBER && draft.options.isNotEmpty() -> "数字项目不使用回答选项"
        draft.unit.trim().length > 16 -> "单位最多 16 个字"
        draft.kind == ProjectKind.CHOICE && draft.unit.isNotEmpty() -> "选项项目不使用单位"
        draft.options.any { it.label.trim().length !in 1..30 } -> "每个选项需要 1–30 个字"
        draft.options.map { it.id }.distinct().size != draft.options.size -> "选项标识重复，请重新打开编辑页"
        draft.times.size !in 1..24 -> "请设置 1–24 个提醒时间"
        draft.times.any { it !in 0..1439 } -> "请选择有效的提醒时间"
        draft.times.distinct().size != draft.times.size -> "提醒时间不能重复"
        draft.reminderFrequency !in setOf(ReminderFrequency.DAILY, ReminderFrequency.WEEKLY) -> "请选择有效的提醒频率"
        draft.reminderDays !in 1..127 -> "请至少选择一个提醒日"
        else -> null
    }
}
