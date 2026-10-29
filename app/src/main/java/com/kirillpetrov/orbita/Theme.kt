package com.kirillpetrov.orbita

import android.graphics.Color
import java.util.Locale
import kotlin.math.abs

const val TAU = 6.2831855f
const val PI_F = 3.1415927f

fun withAlpha(color: Int, a: Float): Int {
    val na = ((color ushr 24) * a.coerceIn(0f, 1f)).toInt()
    return (color and 0x00FFFFFF) or (na shl 24)
}

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

fun lerpColor(a: Int, b: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    return Color.argb(
        lerp(Color.alpha(a).toFloat(), Color.alpha(b).toFloat(), k).toInt(),
        lerp(Color.red(a).toFloat(), Color.red(b).toFloat(), k).toInt(),
        lerp(Color.green(a).toFloat(), Color.green(b).toFloat(), k).toInt(),
        lerp(Color.blue(a).toFloat(), Color.blue(b).toFloat(), k).toInt()
    )
}

fun easeOut(t: Float): Float {
    val x = 1f - t.coerceIn(0f, 1f)
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

object Pal {
    val bgTop: Int = Color.parseColor("#04061A")
    val bgBottom: Int = Color.parseColor("#0C1038")
    val hazard: Int = Color.parseColor("#FF3D71")
    val hazardHot: Int = Color.parseColor("#FFC2D1")
    val gold: Int = Color.parseColor("#FFD84D")
    val shield: Int = Color.parseColor("#6EA8FF")
    val text: Int = Color.parseColor("#EAF0FF")
    val dim: Int = Color.parseColor("#8C97C7")
    val panel: Int = Color.parseColor("#101645")
    val white: Int = Color.WHITE

    val accents: IntArray = intArrayOf(
        Color.parseColor("#2DE2E6"),
        Color.parseColor("#B16CFF"),
        Color.parseColor("#FF9F43"),
        Color.parseColor("#3DFFA2"),
        Color.parseColor("#4D8BFF"),
        Color.parseColor("#FF5FA2")
    )

    /** 36 оттенков для радужного скина (ограниченный набор — кэш фильтров не растёт). */
    val rainbow: IntArray = IntArray(36) { i ->
        Color.HSVToColor(floatArrayOf(i * 10f, 0.55f, 1f))
    }
}

class Skin(
    private val ru: String,
    private val en: String,
    val head: Int,
    val trail: Int,
    val cost: Int,
    val rainbow: Boolean = false
) {
    fun name(): String = if (Txt.isRu) ru else en
}

object Skins {
    val all: List<Skin> = listOf(
        Skin("Нова", "Nova", Color.parseColor("#5CF2FF"), Color.parseColor("#1FA7FF"), 0),
        Skin("Рубин", "Ruby", Color.parseColor("#FF7C9A"), Color.parseColor("#FF1F5A"), 60),
        Skin("Изумруд", "Emerald", Color.parseColor("#8CFFBC"), Color.parseColor("#12C26B"), 120),
        Skin("Солнце", "Sun", Color.parseColor("#FFE58A"), Color.parseColor("#FF9A1F"), 200),
        Skin("Аметист", "Amethyst", Color.parseColor("#DDB8FF"), Color.parseColor("#8A3DFF"), 320),
        Skin("Призма", "Prism", Color.WHITE, Color.WHITE, 500, true)
    )
}

object Txt {
    val isRu: Boolean = Locale.getDefault().language.let { it == "ru" || it == "uk" || it == "be" || it == "kk" }
    fun t(r: String, e: String): String = if (isRu) r else e
}
