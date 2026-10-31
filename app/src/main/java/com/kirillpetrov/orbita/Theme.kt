package com.kirillpetrov.orbita

import android.graphics.Color

fun withAlpha(color: Int, a: Float): Int {
    val na = ((color ushr 24) * clamp01(a)).toInt()
    return (color and 0x00FFFFFF) or (na shl 24)
}

fun lerpColor(a: Int, b: Int, t: Float): Int {
    val k = clamp01(t)
    return Color.argb(
        lerp(Color.alpha(a).toFloat(), Color.alpha(b).toFloat(), k).toInt(),
        lerp(Color.red(a).toFloat(), Color.red(b).toFloat(), k).toInt(),
        lerp(Color.green(a).toFloat(), Color.green(b).toFloat(), k).toInt(),
        lerp(Color.blue(a).toFloat(), Color.blue(b).toFloat(), k).toInt()
    )
}

object Pal {
    val bgTop: Int = Color.parseColor("#04061A")
    val bgBottom: Int = Color.parseColor("#0C1038")
    val hazard: Int = Color.parseColor("#FF3D71")
    val hazardHot: Int = Color.parseColor("#FFC2D1")
    val gold: Int = Color.parseColor("#FFD84D")
    val safe: Int = Color.parseColor("#3DFFA2")
    val text: Int = Color.parseColor("#EAF0FF")
    val dim: Int = Color.parseColor("#8C97C7")
    val panel: Int = Color.parseColor("#101645")
    val ink: Int = Color.parseColor("#04061A")
    val dimBg: Int = Color.parseColor("#02030F")
    val blueEnd: Int = Color.parseColor("#4D8BFF")
    val goldEnd: Int = Color.parseColor("#FF9A1F")
    val white: Int = Color.WHITE

    /** Цвета усилений: щит, магнит, замедление. */
    val power: IntArray = intArrayOf(
        Color.parseColor("#6EA8FF"),
        Color.parseColor("#FF7AD9"),
        Color.parseColor("#7CFFC4")
    )

    val accents: IntArray = intArrayOf(
        Color.parseColor("#2DE2E6"),
        Color.parseColor("#B16CFF"),
        Color.parseColor("#FF9F43"),
        Color.parseColor("#3DFFA2"),
        Color.parseColor("#4D8BFF"),
        Color.parseColor("#FF5FA2")
    )

    /** 36 оттенков для радужного скина (набор ограничен — кэш фильтров не растёт). */
    val rainbow: IntArray = IntArray(36) { i ->
        Color.HSVToColor(floatArrayOf(i * 10f, 0.55f, 1f))
    }
}

/** Стиль кометы: влияет на хвост, частицы и «украшения» вокруг головы. */
object SkinStyle {
    const val PLAIN = 0
    const val SPARKS = 1
    const val RIBBON = 2
    const val FLARE = 3
    const val TWIN = 4
    const val FROST = 5
    const val EMBER = 6
    const val RAINBOW = 7
}

class Skin(val nameRes: Int, val head: Int, val trail: Int, val cost: Int, val style: Int)

object Skins {
    val all: List<Skin> = listOf(
        Skin(R.string.skin_nova, Color.parseColor("#5CF2FF"), Color.parseColor("#1FA7FF"), 0, SkinStyle.PLAIN),
        Skin(R.string.skin_ruby, Color.parseColor("#FF7C9A"), Color.parseColor("#FF1F5A"), 60, SkinStyle.SPARKS),
        Skin(R.string.skin_emerald, Color.parseColor("#8CFFBC"), Color.parseColor("#12C26B"), 120, SkinStyle.RIBBON),
        Skin(R.string.skin_sun, Color.parseColor("#FFE58A"), Color.parseColor("#FF9A1F"), 200, SkinStyle.FLARE),
        Skin(R.string.skin_amethyst, Color.parseColor("#DDB8FF"), Color.parseColor("#8A3DFF"), 320, SkinStyle.TWIN),
        Skin(R.string.skin_frost, Color.parseColor("#EAF8FF"), Color.parseColor("#7CC8FF"), 450, SkinStyle.FROST),
        Skin(R.string.skin_ember, Color.parseColor("#FFB06B"), Color.parseColor("#FF4B1F"), 650, SkinStyle.EMBER),
        Skin(R.string.skin_prism, Color.WHITE, Color.WHITE, 1000, SkinStyle.RAINBOW)
    )

    fun headColor(s: Skin, clock: Float): Int =
        if (s.style == SkinStyle.RAINBOW) Pal.rainbow[(clock * 22f).toInt() % Pal.rainbow.size] else s.head

    fun trailColor(s: Skin, clock: Float): Int =
        if (s.style == SkinStyle.RAINBOW) Pal.rainbow[((clock * 22f).toInt() + 12) % Pal.rainbow.size] else s.trail
}
