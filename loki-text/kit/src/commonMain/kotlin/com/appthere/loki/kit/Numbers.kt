package com.appthere.loki.kit

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * [value] with exactly [decimals] digits after a full stop, rounded half up: what `"%.Nf".format` gives on the JVM, but
 * in common code (Kotlin/Native has no `String.format`) and the same in every locale. Not-a-number and infinities are
 * written as Kotlin writes them.
 */
fun fixed(value: Double, decimals: Int): String {
    if (!value.isFinite()) return value.toString()
    val d = decimals.coerceIn(0, 9)
    var scale = 1L
    repeat(d) { scale *= 10 }
    val n = (abs(value) * scale).roundToLong()
    val sign = if (value < 0 && n != 0L) "-" else ""
    val whole = n / scale
    return if (d == 0) "$sign$whole" else "$sign$whole." + (n % scale).toString().padStart(d, '0')
}
