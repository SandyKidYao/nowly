package com.nowly.app.projects

import org.junit.Assert.*
import org.junit.Test

class NumericValuesTest {
    @Test fun preservesExactDecimalsWithoutBinaryRoundingAndNormalizesKeyboardInput() {
        assertEquals("65.8", NumericValues.normalize(" 65.800 "))
        assertEquals("65.25", NumericValues.normalize("65,25"))
        assertEquals("0", NumericValues.normalize("-0.000"))
        assertEquals("-12.345678", NumericValues.normalize("-12.345678"))
        assertEquals("999999999999.999999", NumericValues.normalize("999999999999.999999"))
        assertEquals("+0.1", NumericValues.signed("65.3".toBigDecimal().subtract("65.2".toBigDecimal())))
    }
    @Test fun rejectsIncompleteAmbiguousAndUnboundedInputInsteadOfRoundingIt() {
        listOf("", "-", ".", "65.", "1.2345678", "1,000.2", "NaN", "Infinity", "1e6", "1000000000000", "六十五").forEach {
            assertNull("Should reject $it", NumericValues.normalize(it))
        }
    }
    @Test fun numericDraftNeedsNoOptionsAndWeeklyDraftNeedsAtLeastOneDay() {
        val draft = ProjectDraft("id", "体重", "多少？", true, emptyList(), listOf(480), false,
            ProjectKind.NUMBER, "kg", ReminderFrequency.WEEKLY, 64)
        assertNull(DraftValidation.error(draft))
        assertNotNull(DraftValidation.error(draft.copy(reminderDays = 0)))
        assertNotNull(DraftValidation.error(draft.copy(unit = "x".repeat(17))))
        assertNotNull(DraftValidation.error(draft.copy(options = listOf(AnswerOption("a", "低", 0)))))
    }
}
