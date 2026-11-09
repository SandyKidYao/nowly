package com.nowly.app.projects

import java.math.BigDecimal

/** Decimal text is the source of truth; chart coordinates are the only floating-point conversion. */
object NumericValues {
    private val pattern = Regex("[+-]?[0-9]{1,12}([.,][0-9]{1,6})?")
    fun normalize(input: String): String? {
        val text = input.trim()
        if (!pattern.matches(text)) return null
        return text.replace(',', '.').toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()
    }
    fun format(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
    fun signed(value: BigDecimal): String = (if (value.signum() > 0) "+" else "") + format(value)
    fun withUnit(value: String, unit: String): String = if (unit.isBlank()) value else "$value $unit"
    const val ERROR = "请输入有效数字，最多 12 位整数、6 位小数"
}
