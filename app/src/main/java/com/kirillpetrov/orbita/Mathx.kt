package com.kirillpetrov.orbita

import kotlin.math.abs

const val TAU = 6.2831855f
const val PI_F = 3.1415927f

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

fun clamp01(t: Float): Float = if (t < 0f) 0f else if (t > 1f) 1f else t

fun easeOut(t: Float): Float {
    val x = 1f - clamp01(t)
    return 1f - x * x * x
}

fun wrapAngle(a: Float): Float {
    var x = a % TAU
    if (x < 0f) x += TAU
    return x
}

/** Минимальная разница между углами, всегда >= 0. */
fun angleDiff(a: Float, b: Float): Float {
    var d = (a - b) % TAU
    if (d > PI_F) d -= TAU
    if (d < -PI_F) d += TAU
    return abs(d)
}
