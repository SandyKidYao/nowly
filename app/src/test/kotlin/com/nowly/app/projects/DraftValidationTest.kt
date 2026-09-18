package com.nowly.app.projects

import org.junit.Assert.*
import org.junit.Test

class DraftValidationTest {
    private val valid = ProjectDraft("p", "项目", "现在如何？", false,
        listOf(AnswerOption("a", "相同名称", 0), AnswerOption("b", "相同名称", 1)), listOf(600, 900))
    @Test fun identicalLabelsRemainValidIndependentOptions() { assertNull(DraftValidation.error(valid)) }
    @Test fun rejectsDuplicateTimesAndOptionIdentities() {
        assertNotNull(DraftValidation.error(valid.copy(times = listOf(600, 600))))
        assertNotNull(DraftValidation.error(valid.copy(options = listOf(valid.options[0], valid.options[0]))))
    }
    @Test fun cannotSaveAnUnanswerableOrUnschedulableProject() {
        assertNotNull(DraftValidation.error(valid.copy(options = emptyList())))
        assertNotNull(DraftValidation.error(valid.copy(times = emptyList())))
        assertNotNull(DraftValidation.error(valid.copy(name = "  ")))
    }
}
