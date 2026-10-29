package com.kirillpetrov.orbita

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/** Общие инструменты отрисовки. Ничего не аллоцирует в горячем пути, кроме кэшей. */
class Gfx(context: Context) {
    val density: Float = context.resources.displayMetrics.density
    fun dp(v: Float): Float = v * density

    val font: Typeface = try {
        Typeface.createFromAsset(context.assets, "fonts/RussoOne-Regular.ttf")
    } catch (e: Exception) {
        Typeface.DEFAULT_BOLD
    }

    private val glowBmp: Bitmap = buildGlow()
    private val filters = HashMap<Int, PorterDuffColorFilter>()
    private val gp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val path = Path()

    val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    val fp = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun buildGlow(): Bitmap {
        val s = 128
        val px = IntArray(s * s)
        val c = (s - 1) / 2f
        for (y in 0 until s) {
            for (x in 0 until s) {
                val d = hypot(x - c, y - c) / c
                val t = (1f - d).coerceIn(0f, 1f)
                val a = t.pow(2.2f)
                px[y * s + x] = ((a * 255f).toInt() shl 24) or 0x00FFFFFF
            }
        }
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        b.setPixels(px, 0, s, 0, 0, s, s)
        return b
    }

    private fun filter(color: Int): PorterDuffColorFilter {
        val key = color or (0xFF shl 24)
        var f = filters[key]
        if (f == null) {
            f = PorterDuffColorFilter(key, PorterDuff.Mode.SRC_IN)
            filters[key] = f
        }
        return f
    }

    /** Мягкое свечение: тонированный радиальный градиент. */
    fun glow(c: Canvas, x: Float, y: Float, radius: Float, color: Int, a: Float) {
        if (a <= 0.01f || radius <= 0.5f) return
        rect.set(x - radius, y - radius, x + radius, y + radius)
        gp.colorFilter = filter(color)
        gp.alpha = (a.coerceIn(0f, 1f) * 255f).toInt()
        c.drawBitmap(glowBmp, null, rect, gp)
    }

    fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.CENTER, glow: Int = 0, a: Float = 1f
    ) {
        if (a <= 0.01f) return
        tp.typeface = font
        tp.textSize = size
        tp.textAlign = align
        if (glow != 0) {
            tp.style = Paint.Style.STROKE
            tp.strokeJoin = Paint.Join.ROUND
            tp.strokeWidth = size * 0.30f
            tp.color = withAlpha(glow, 0.10f * a)
            c.drawText(s, x, y, tp)
            tp.strokeWidth = size * 0.18f
            tp.color = withAlpha(glow, 0.17f * a)
            c.drawText(s, x, y, tp)
            tp.strokeWidth = size * 0.08f
            tp.color = withAlpha(glow, 0.32f * a)
            c.drawText(s, x, y, tp)
        }
        tp.style = Paint.Style.FILL
        tp.color = withAlpha(color, a)
        c.drawText(s, x, y, tp)
    }

    /** Размер шрифта, при котором текст помещается в maxW. */
    fun fit(s: String, maxW: Float, maxSize: Float): Float {
        tp.typeface = font
        tp.textSize = maxSize
        val w = tp.measureText(s)
        return if (w > maxW && w > 0f) maxSize * maxW / w else maxSize
    }

    fun measure(s: String, size: Float): Float {
        tp.typeface = font
        tp.textSize = size
        return tp.measureText(s)
    }

    // ---------- иконки ----------

    fun gem(c: Canvas, x: Float, y: Float, s: Float, color: Int, rot: Float = 0f, a: Float = 1f) {
        c.save()
        c.translate(x, y)
        c.rotate(rot)
        path.reset()
        path.moveTo(0f, -s)
        path.lineTo(s * 0.72f, 0f)
        path.lineTo(0f, s)
        path.lineTo(-s * 0.72f, 0f)
        path.close()
        fp.style = Paint.Style.FILL
        fp.color = withAlpha(color, a)
        c.drawPath(path, fp)
        path.reset()
        path.moveTo(0f, -s * 0.55f)
        path.lineTo(s * 0.34f, 0f)
        path.lineTo(0f, s * 0.55f)
        path.lineTo(-s * 0.34f, 0f)
        path.close()
        fp.color = withAlpha(Pal.white, 0.55f * a)
        c.drawPath(path, fp)
        c.restore()
    }

    fun pauseIcon(c: Canvas, x: Float, y: Float, s: Float, color: Int) {
        fp.style = Paint.Style.FILL
        fp.color = color
        rect.set(x - s * 0.55f, y - s * 0.7f, x - s * 0.15f, y + s * 0.7f)
        c.drawRoundRect(rect, s * 0.12f, s * 0.12f, fp)
        rect.set(x + s * 0.15f, y - s * 0.7f, x + s * 0.55f, y + s * 0.7f)
        c.drawRoundRect(rect, s * 0.12f, s * 0.12f, fp)
    }

    fun playIcon(c: Canvas, x: Float, y: Float, s: Float, color: Int) {
        path.reset()
        path.moveTo(x - s * 0.5f, y - s * 0.7f)
        path.lineTo(x + s * 0.7f, y)
        path.lineTo(x - s * 0.5f, y + s * 0.7f)
        path.close()
        fp.style = Paint.Style.FILL
        fp.color = color
        c.drawPath(path, fp)
    }

    /** Шеврон: dir = -1 влево, +1 вправо. */
    fun chevron(c: Canvas, x: Float, y: Float, s: Float, dir: Int, color: Int, w: Float) {
        path.reset()
        path.moveTo(x - dir * s * 0.45f, y - s)
        path.lineTo(x + dir * s * 0.45f, y)
        path.lineTo(x - dir * s * 0.45f, y + s)
        fp.style = Paint.Style.STROKE
        fp.strokeWidth = w
        fp.strokeCap = Paint.Cap.ROUND
        fp.strokeJoin = Paint.Join.ROUND
        fp.color = color
        c.drawPath(path, fp)
    }

    fun speakerIcon(c: Canvas, x: Float, y: Float, s: Float, color: Int, on: Boolean) {
        fp.style = Paint.Style.FILL
        fp.color = color
        path.reset()
        path.moveTo(x - s * 0.9f, y - s * 0.38f)
        path.lineTo(x - s * 0.4f, y - s * 0.38f)
        path.lineTo(x + s * 0.1f, y - s * 0.85f)
        path.lineTo(x + s * 0.1f, y + s * 0.85f)
        path.lineTo(x - s * 0.4f, y + s * 0.38f)
        path.lineTo(x - s * 0.9f, y + s * 0.38f)
        path.close()
        c.drawPath(path, fp)
        fp.style = Paint.Style.STROKE
        fp.strokeCap = Paint.Cap.ROUND
        fp.strokeWidth = s * 0.16f
        if (on) {
            rect.set(x - s * 0.25f, y - s * 0.45f, x + s * 0.65f, y + s * 0.45f)
            c.drawArc(rect, -50f, 100f, false, fp)
            rect.set(x - s * 0.5f, y - s * 0.8f, x + s * 1.0f, y + s * 0.8f)
            c.drawArc(rect, -50f, 100f, false, fp)
        } else {
            c.drawLine(x + s * 0.4f, y - s * 0.4f, x + s * 1.0f, y + s * 0.4f, fp)
            c.drawLine(x + s * 0.4f, y + s * 0.4f, x + s * 1.0f, y - s * 0.4f, fp)
        }
    }

    fun noteIcon(c: Canvas, x: Float, y: Float, s: Float, color: Int, on: Boolean) {
        fp.color = color
        fp.style = Paint.Style.FILL
        rect.set(x - s * 0.75f, y + s * 0.15f, x - s * 0.05f, y + s * 0.85f)
        c.drawOval(rect, fp)
        fp.style = Paint.Style.STROKE
        fp.strokeCap = Paint.Cap.ROUND
        fp.strokeJoin = Paint.Join.ROUND
        fp.strokeWidth = s * 0.16f
        c.drawLine(x - s * 0.05f, y + s * 0.5f, x - s * 0.05f, y - s * 0.85f, fp)
        path.reset()
        path.moveTo(x - s * 0.05f, y - s * 0.85f)
        path.quadTo(x + s * 0.2f, y - s * 0.55f, x + s * 0.7f, y - s * 0.4f)
        c.drawPath(path, fp)
        if (!on) {
            c.drawLine(x - s * 0.9f, y - s * 0.85f, x + s * 0.85f, y + s * 0.85f, fp)
        }
    }

    fun shieldIcon(c: Canvas, x: Float, y: Float, s: Float, color: Int, a: Float) {
        path.reset()
        for (i in 0 until 6) {
            val ang = i * TAU / 6f - PI_F / 2f
            val px = x + cos(ang) * s
            val py = y + sin(ang) * s
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        fp.style = Paint.Style.STROKE
        fp.strokeWidth = s * 0.22f
        fp.strokeJoin = Paint.Join.ROUND
        fp.color = withAlpha(color, a)
        c.drawPath(path, fp)
        fp.style = Paint.Style.FILL
        fp.color = withAlpha(color, 0.25f * a)
        c.drawPath(path, fp)
    }
}

/** Пул частиц без аллокаций во время игры. */
class Particles(private val max: Int) {
    private val x = FloatArray(max)
    private val y = FloatArray(max)
    private val vx = FloatArray(max)
    private val vy = FloatArray(max)
    private val life = FloatArray(max)
    private val maxLife = FloatArray(max)
    private val size = FloatArray(max)
    private val drag = FloatArray(max)
    private val color = IntArray(max)
    private var n = 0
    private val rnd = java.util.Random()

    fun clear() {
        n = 0
    }

    fun emit(
        px: Float, py: Float, col: Int, count: Int, speed: Float, sz: Float, lifeSec: Float,
        dragK: Float = 1.6f, dir: Float = 0f, spread: Float = TAU
    ) {
        for (i in 0 until count) {
            if (n >= max) return
            val a = dir + (rnd.nextFloat() - 0.5f) * spread
            val sp = speed * (0.35f + rnd.nextFloat() * 0.65f)
            x[n] = px
            y[n] = py
            vx[n] = cos(a) * sp
            vy[n] = sin(a) * sp
            val l = lifeSec * (0.6f + rnd.nextFloat() * 0.4f)
            life[n] = l
            maxLife[n] = l
            size[n] = sz * (0.6f + rnd.nextFloat() * 0.8f)
            drag[n] = dragK
            color[n] = col
            n++
        }
    }

    fun update(dt: Float) {
        var i = 0
        while (i < n) {
            life[i] -= dt
            if (life[i] <= 0f) {
                n--
                x[i] = x[n]; y[i] = y[n]; vx[i] = vx[n]; vy[i] = vy[n]
                life[i] = life[n]; maxLife[i] = maxLife[n]; size[i] = size[n]
                drag[i] = drag[n]; color[i] = color[n]
                continue
            }
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            val k = (1f - drag[i] * dt).coerceAtLeast(0f)
            vx[i] *= k
            vy[i] *= k
            i++
        }
    }

    fun draw(c: Canvas, g: Gfx) {
        for (i in 0 until n) {
            val t = life[i] / maxLife[i]
            val r = size[i] * (0.5f + 0.9f * t)
            g.glow(c, x[i], y[i], r * 2.6f, color[i], t * 0.9f)
            g.glow(c, x[i], y[i], r * 1.1f, Pal.white, t * 0.7f)
        }
    }
}
