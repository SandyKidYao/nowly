package com.nowly.app.statistics

import androidx.compose.ui.graphics.Color
import kotlin.math.cbrt
import kotlin.math.pow

/**
 * Diverging ramp for an ordered scale: ochre at the lowest level, warm gray in the middle (odd counts only),
 * slate blue at the highest. Neither pole reads as good or bad, so mood and pressure scales both fit.
 * Each arm is interpolated in OKLab between a dark pole and a light tint, so adjacent levels stay apart
 * under color-vision deficiency for up to 8 levels; charts still pair every color with a label.
 */
object ScaleColors {
    private const val LOW_POLE = 0x9A5B26
    private const val LOW_TINT = 0xD4AC80
    private const val MIDDLE = 0xCDC8BC
    private const val HIGH_TINT = 0x9DB6C9
    private const val HIGH_POLE = 0x2E5E78
    /** Retired options have no level; a darker neutral keeps them apart from the middle gray. */
    val retired = Color(0xFF8C8F88)

    fun colors(count: Int): List<Color> {
        val arm = count / 2
        val low = mix(LOW_POLE, LOW_TINT, arm)
        val high = mix(HIGH_POLE, HIGH_TINT, arm).asReversed()
        return low + (if (count % 2 == 1) listOf(rgb(MIDDLE)) else emptyList()) + high
    }

    private fun mix(from: Int, to: Int, steps: Int): List<Color> {
        val a = oklab(from); val b = oklab(to)
        return List(steps) { i ->
            val t = if (steps == 1) 0.0 else i / (steps - 1.0)
            fromOklab(DoubleArray(3) { a[it] + (b[it] - a[it]) * t })
        }
    }

    private fun rgb(value: Int) = Color(0xFF000000.toInt() or value)

    private fun linear(channel: Int): Double = (channel / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    private fun gamma(value: Double): Float = (if (value <= 0.0031308) 12.92 * value else 1.055 * value.pow(1 / 2.4) - 0.055).coerceIn(0.0, 1.0).toFloat()

    private fun oklab(value: Int): DoubleArray {
        val r = linear(value shr 16 and 0xFF); val g = linear(value shr 8 and 0xFF); val b = linear(value and 0xFF)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)
    }

    private fun fromOklab(lab: DoubleArray): Color {
        val l = (lab[0] + 0.3963377774 * lab[1] + 0.2158037573 * lab[2]).pow(3)
        val m = (lab[0] - 0.1055613458 * lab[1] - 0.0638541728 * lab[2]).pow(3)
        val s = (lab[0] - 0.0894841775 * lab[1] - 1.2914855480 * lab[2]).pow(3)
        return Color(gamma(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
            gamma(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
            gamma(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s))
    }
}
