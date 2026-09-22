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
)

data class AnswerOption(val id: String, val label: String, val position: Int, val active: Boolean = true, val colorIndex: Int = position)
data class ProjectDraft(val id: String, val name: String, val question: String, val ordered: Boolean,
    val options: List<AnswerOption>, val times: List<Int>, val remindersEnabled: Boolean? = null)

object DraftValidation {
    fun error(draft: ProjectDraft): String? = when {
        draft.name.trim().length !in 1..40 -> "名称需要 1–40 个字"
        draft.question.trim().length !in 1..160 -> "问题需要 1–160 个字"
        draft.options.size !in 2..20 -> "请保留 2–20 个回答选项"
        draft.options.any { it.label.trim().length !in 1..30 } -> "每个选项需要 1–30 个字"
        draft.options.map { it.id }.distinct().size != draft.options.size -> "选项标识重复，请重新打开编辑页"
        draft.times.size !in 1..24 -> "请设置 1–24 个提醒时间"
        draft.times.any { it !in 0..1439 } -> "请选择有效的提醒时间"
        draft.times.distinct().size != draft.times.size -> "提醒时间不能重复"
        else -> null
    }
}
