package com.kirillpetrov.orbita

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

private const val RAD2DEG = 57.29578f

/** Состояние, которое сцене нужно знать о «внешнем мире» (экран, скин, время). */
class SceneState {
    var clock = 0f
    var accent = Pal.accents[0]
    var corePulse = 0f
    var showPlayer = true
    var inPlay = false
    var skin: Skin = Skins.all[0]
}

/**
 * Отрисовка игровой сцены. Не создаёт объектов в onDraw: все кисти и прямоугольники переиспользуются.
 */
class Painter(
    private val g: Gfx,
    private val world: World,
    private val parts: Particles,
    private val waves: Shockwaves
) {
    companion object {
        const val TRAIL = 30
    }

    var W = 0f
        private set
    var H = 0f
        private set

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val vigPaint = Paint()
    private val tintPaint = Paint()
    private val oval = RectF()

    private val starCount = 90
    private val starA = FloatArray(starCount)
    private val starR = FloatArray(starCount)
    private val starS = FloatArray(starCount)
    private val starP = FloatArray(starCount)

    private val trx = FloatArray(TRAIL)
    private val try_ = FloatArray(TRAIL)
    private var trN = 0
    private var trAcc = 0f

    private val rnd = java.util.Random(7)

    fun resize(w: Float, h: Float) {
        W = w
        H = h
        bgPaint.shader = LinearGradient(0f, 0f, 0f, H, Pal.bgTop, Pal.bgBottom, Shader.TileMode.CLAMP)
        vigPaint.shader = RadialGradient(
            W / 2f, H / 2f, hypot(W, H) * 0.62f,
            intArrayOf(0x00000000, 0x00000000, 0x99000000.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        val maxR = hypot(W, H) * 0.52f
        for (i in 0 until starCount) {
            starA[i] = rnd.nextFloat() * TAU
            starR[i] = sqrt(rnd.nextFloat()) * maxR
            starS[i] = 0.6f + rnd.nextFloat() * 1.6f
            starP[i] = rnd.nextFloat() * TAU
        }
    }

    fun resetTrail() {
        trN = 0
    }

    fun recordTrail(dt: Float) {
        trAcc += dt
        while (trAcc >= 0.012f) {
            trAcc -= 0.012f
            if (trN < TRAIL) {
                trx[trN] = world.px(); try_[trN] = world.py(); trN++
            } else {
                System.arraycopy(trx, 1, trx, 0, TRAIL - 1)
                System.arraycopy(try_, 1, try_, 0, TRAIL - 1)
                trx[TRAIL - 1] = world.px(); try_[TRAIL - 1] = world.py()
            }
        }
    }

    // ================================================================ фон

    fun drawBackground(c: Canvas, s: SceneState) {
        c.drawRect(0f, 0f, W, H, bgPaint)
        val clock = s.clock
        val c2 = lerpColor(s.accent, Color.parseColor("#7A3DFF"), 0.6f)
        g.glow(c, W * 0.25f + sin(clock * 0.1f) * W * 0.08f, H * 0.2f, W * 0.95f, s.accent, 0.13f)
        g.glow(c, W * 0.85f + cos(clock * 0.08f) * W * 0.06f, H * 0.78f, W * 0.95f, c2, 0.14f)

        p.style = Paint.Style.FILL
        p.shader = null
        val rot = clock * 0.02f
        val sx = W / 2f
        val sy = H / 2f
        for (i in 0 until starCount) {
            val a = starA[i] + rot
            val x = sx + cos(a) * starR[i]
            val y = sy + sin(a) * starR[i]
            if (x < -4f || x > W + 4f || y < -4f || y > H + 4f) continue
            val tw = 0.55f + 0.45f * sin(clock * (0.8f + starS[i]) + starP[i])
            p.color = withAlpha(Pal.text, 0.18f + 0.55f * tw * (starS[i] / 2.2f))
            c.drawCircle(x, y, g.dp(0.6f) * starS[i], p)
        }
    }

    fun drawOverlay(c: Canvas) {
        c.drawRect(0f, 0f, W, H, vigPaint)
        val slowK = clamp01((1f - world.hz) * 2f)
        if (slowK > 0.01f) {
            tintPaint.color = withAlpha(Pal.power[World.P_SLOW], 0.10f * slowK)
            c.drawRect(0f, 0f, W, H, tintPaint)
        }
    }

    // ================================================================ сцена

    fun drawScene(c: Canvas, s: SceneState) {
        drawRings(c, s)
        drawCore(c, s)
        for (i in 0 until world.crystals.size) drawCrystal(c, world.crystals[i], s)
        for (i in 0 until world.powers.size) drawPower(c, world.powers[i], s)
        for (i in 0 until world.barriers.size) drawBarrier(c, world.barriers[i], s)
        for (i in 0 until world.meteors.size) drawMeteor(c, world.meteors[i])
        if (s.showPlayer && !world.dead) drawPlayer(c, s)
        parts.draw(c, g)
        waves.draw(c)
    }

    private fun drawRings(c: Canvas, s: SceneState) {
        val cx = world.cx
        val cy = world.cy
        val u = world.unit
        p.style = Paint.Style.STROKE
        p.shader = null
        p.strokeCap = Paint.Cap.BUTT
        for (i in 0 until World.LANES) {
            val r = world.laneR(i.toFloat())
            p.strokeWidth = world.bandW * 1.15f
            p.color = withAlpha(s.accent, 0.045f)
            c.drawCircle(cx, cy, r, p)
            val active = s.inPlay && world.pLane == i && !world.dead
            p.strokeWidth = g.dp(1.3f)
            p.color = withAlpha(s.accent, if (active) 0.6f else 0.24f)
            c.drawCircle(cx, cy, r, p)
        }
        val rt = world.laneR(3f) + world.bandW * 0.95f
        p.strokeWidth = g.dp(1.2f)
        p.color = withAlpha(s.accent, 0.28f)
        val n = 72
        val rot = s.clock * 0.06f
        for (i in 0 until n) {
            val a = rot + i * TAU / n
            val l = if (i % 6 == 0) 0.02f else 0.01f
            val ca = cos(a)
            val sa = sin(a)
            c.drawLine(cx + ca * rt, cy + sa * rt, cx + ca * (rt + u * l), cy + sa * (rt + u * l), p)
        }
    }

    private fun drawCore(c: Canvas, s: SceneState) {
        val cx = world.cx
        val cy = world.cy
        val clock = s.clock
        val cr = world.coreR * (1f + 0.06f * sin(clock * 3f) + 0.25f * s.corePulse)
        g.glow(c, cx, cy, cr * 3.8f, s.accent, 0.55f + 0.2f * s.corePulse)
        p.style = Paint.Style.STROKE
        p.shader = null
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = g.dp(2.2f)
        p.color = withAlpha(lerpColor(s.accent, Pal.white, 0.5f), 0.55f)
        for (i in 0 until 12) {
            val a = clock * 0.4f + i * TAU / 12f
            val l0 = cr * 1.2f
            val l1 = cr * (1.6f + 0.3f * sin(clock * 2f + i))
            c.drawLine(cx + cos(a) * l0, cy + sin(a) * l0, cx + cos(a) * l1, cy + sin(a) * l1, p)
        }
        p.style = Paint.Style.FILL
        p.color = lerpColor(s.accent, Pal.white, 0.45f)
        c.drawCircle(cx, cy, cr * 0.85f, p)
        p.color = Pal.white
        c.drawCircle(cx, cy, cr * 0.5f, p)
    }

    // ---------------------------------------------------------------- препятствия

    private fun drawBarrier(c: Canvas, b: World.Barrier, s: SceneState) {
        val cx = world.cx
        val cy = world.cy
        val clock = s.clock
        val lethalEnd = World.WARN + b.life
        val fade = if (b.age > lethalEnd) clamp01(1f - (b.age - lethalEnd) / World.FADE) else 1f

        p.style = Paint.Style.STROKE
        p.shader = null
        p.strokeCap = Paint.Cap.ROUND

        // безопасный коридор ворот
        if (b.safeLane >= 0 && fade > 0f) {
            val sr = world.laneR(b.safeLane.toFloat())
            oval.set(cx - sr, cy - sr, cx + sr, cy + sr)
            val pulse = 0.5f + 0.5f * sin(clock * 7f)
            p.strokeWidth = world.bandW * 0.5f
            p.color = withAlpha(Pal.safe, (0.16f + 0.22f * pulse) * fade)
            c.drawArc(oval, (b.angle - b.sweep / 2f - 0.1f) * RAD2DEG, (b.sweep + 0.2f) * RAD2DEG, false, p)
        }

        if (!b.armed && b.age >= lethalEnd) return

        val r = world.laneR(b.lane.toFloat())
        oval.set(cx - r, cy - r, cx + r, cy + r)
        val start = (b.angle - b.sweep / 2f) * RAD2DEG
        val sweep = b.sweep * RAD2DEG

        if (!b.armed) {
            val pulse = 0.5f + 0.5f * sin(b.age * 16f)
            p.strokeWidth = world.bandW * 0.30f
            p.color = withAlpha(Pal.hazard, 0.30f + 0.45f * pulse)
            c.drawArc(oval, start, sweep, false, p)
            p.strokeWidth = world.bandW
            p.color = withAlpha(Pal.hazard, 0.05f + 0.07f * pulse)
            c.drawArc(oval, start, sweep, false, p)
            return
        }

        val w = world.bandW * (0.45f + 0.55f * easeOut((b.age - World.WARN) / 0.18f))
        p.strokeWidth = w * 1.9f
        p.color = withAlpha(Pal.hazard, 0.10f * fade)
        c.drawArc(oval, start, sweep, false, p)
        p.strokeWidth = w * 1.35f
        p.color = withAlpha(Pal.hazard, 0.22f * fade)
        c.drawArc(oval, start, sweep, false, p)
        p.strokeWidth = w
        p.color = withAlpha(Pal.hazard, 0.95f * fade)
        c.drawArc(oval, start, sweep, false, p)
        p.strokeWidth = w * 0.28f
        p.color = withAlpha(Pal.hazardHot, 0.9f * fade)
        c.drawArc(oval, start, sweep, false, p)
        // бегущий блик
        val k = (clock * 0.55f + b.lane * 0.23f) % 1f
        p.strokeWidth = w * 0.5f
        p.color = withAlpha(Pal.white, 0.30f * fade)
        c.drawArc(oval, start + sweep * k * 0.8f, sweep * 0.2f, false, p)
    }

    private fun drawMeteor(c: Canvas, m: World.Meteor) {
        val cx = world.cx
        val cy = world.cy
        val u = world.unit
        val ca = cos(m.angle)
        val sa = sin(m.angle)
        p.shader = null
        if (m.age < World.METEOR_WARN) {
            val pulse = 0.5f + 0.5f * sin(m.age * 20f)
            val r0 = u * 0.60f
            val r1 = world.coreR * 1.6f
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = g.dp(2f)
            p.color = withAlpha(Pal.hazard, 0.16f + 0.25f * pulse)
            c.drawLine(cx + ca * r1, cy + sa * r1, cx + ca * r0, cy + sa * r0, p)
            val mr = u * 0.485f
            g.glow(c, cx + ca * mr, cy + sa * mr, u * 0.075f, Pal.hazard, 0.4f + 0.5f * pulse)
            p.style = Paint.Style.FILL
            p.color = withAlpha(Pal.hazardHot, 0.6f + 0.4f * pulse)
            c.drawCircle(cx + ca * mr, cy + sa * mr, g.dp(5f), p)
            return
        }
        val fadeIn = clamp01((world.meteorStartR - m.r) / (u * 0.05f))
        val hx = cx + ca * m.r
        val hy = cy + sa * m.r
        val len = u * 0.28f
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        val seg = 7
        for (i in 0 until seg) {
            val t0 = i / seg.toFloat()
            val t1 = (i + 1) / seg.toFloat()
            p.strokeWidth = u * 0.034f * (1f - t0)
            p.color = withAlpha(Pal.hazard, 0.75f * (1f - t0) * fadeIn)
            c.drawLine(hx + ca * len * t0, hy + sa * len * t0, hx + ca * len * t1, hy + sa * len * t1, p)
        }
        g.glow(c, hx, hy, u * 0.09f, Pal.hazard, 0.85f * fadeIn)
        p.style = Paint.Style.FILL
        p.color = withAlpha(Pal.hazardHot, fadeIn)
        c.drawCircle(hx, hy, u * 0.02f, p)
        p.color = withAlpha(Pal.white, fadeIn)
        c.drawCircle(hx, hy, u * 0.01f, p)
    }

    // ---------------------------------------------------------------- подборы

    private fun blink(remain: Float, limit: Float): Float =
        if (remain < limit && ((remain * 8f).toInt() % 2) != 0) 0.35f else 1f

    private fun drawCrystal(c: Canvas, k: World.Pickup, s: SceneState) {
        val x = world.pickupX(k)
        val y = world.pickupY(k)
        val a = blink(k.life - k.age, 1.6f)
        val appear = easeOut(k.age / 0.3f)
        val sz = world.unit * 0.026f * appear * (1f + 0.08f * sin(s.clock * 6f + k.angle * 3f))
        g.glow(c, x, y, sz * 3.6f, Pal.gold, 0.55f * a)
        g.gem(c, x, y, sz, Pal.gold, sin(s.clock * 2.5f + k.angle) * 14f, a)
    }

    private fun drawPower(c: Canvas, k: World.Pickup, s: SceneState) {
        val x = world.pickupX(k)
        val y = world.pickupY(k)
        val a = blink(k.life - k.age, 2f)
        val appear = easeOut(k.age / 0.3f)
        val sz = world.unit * 0.03f * appear * (1f + 0.07f * sin(s.clock * 5f))
        val col = Pal.power[k.type]
        g.glow(c, x, y, sz * 4.2f, col, 0.6f * a)
        p.style = Paint.Style.STROKE
        p.shader = null
        p.strokeWidth = g.dp(1.6f)
        p.color = withAlpha(col, 0.55f * a)
        c.drawCircle(x, y, sz * (1.7f + 0.15f * sin(s.clock * 4f)), p)
        g.powerIcon(c, k.type, x, y, sz, a, s.clock * 2f)
    }

    // ---------------------------------------------------------------- игрок

    private fun drawPlayer(c: Canvas, s: SceneState) {
        val sk = s.skin
        val head = Skins.headColor(sk, s.clock)
        val trail = Skins.trailColor(sk, s.clock)
        val alpha = if (world.invuln > 0f) 0.55f + 0.45f * sin(s.clock * 28f) else 1f
        val x = world.px()
        val y = world.py()
        val pr = world.pRad
        val clock = s.clock
        val wide = if (sk.style == SkinStyle.RIBBON) 1.9f else 1f

        // кольцо магнита
        if (world.magnetT > 0f) {
            val k = if (world.magnetT < 1.5f) (0.5f + 0.5f * sin(clock * 18f)) else 1f
            p.style = Paint.Style.STROKE
            p.shader = null
            p.strokeWidth = g.dp(1.6f)
            p.color = withAlpha(Pal.power[World.P_MAGNET], (0.18f + 0.1f * sin(clock * 5f)) * k)
            c.drawCircle(x, y, world.unit * 0.36f, p)
        }

        p.style = Paint.Style.STROKE
        p.shader = null
        p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until trN - 1) {
            val t = (i + 1) / trN.toFloat()
            p.strokeWidth = pr * 3.4f * t * wide
            p.color = withAlpha(trail, 0.12f * t * alpha)
            c.drawLine(trx[i], try_[i], trx[i + 1], try_[i + 1], p)
            p.strokeWidth = pr * 1.7f * t * wide
            p.color = withAlpha(trail, 0.8f * t * alpha)
            c.drawLine(trx[i], try_[i], trx[i + 1], try_[i + 1], p)
        }

        val glowK = if (sk.style == SkinStyle.FLARE) 1.35f else 1f
        g.glow(c, x, y, pr * 4.6f * glowK, head, 0.65f * alpha)

        when (sk.style) {
            SkinStyle.FLARE -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = g.dp(2f)
                p.color = withAlpha(head, 0.75f * alpha)
                for (i in 0 until 4) {
                    val a = clock * 2.4f + i * TAU / 4f
                    val l1 = pr * (2.7f + 0.7f * sin(clock * 5f + i))
                    c.drawLine(x + cos(a) * pr * 1.4f, y + sin(a) * pr * 1.4f, x + cos(a) * l1, y + sin(a) * l1, p)
                }
            }
            SkinStyle.TWIN -> {
                for (i in 0 until 2) {
                    val a = clock * 7f + i * PI_F
                    val ox = x + cos(a) * pr * 2.4f
                    val oy = y + sin(a) * pr * 2.4f
                    g.glow(c, ox, oy, pr * 1.8f, trail, 0.8f * alpha)
                    p.style = Paint.Style.FILL
                    p.color = withAlpha(Pal.white, alpha)
                    c.drawCircle(ox, oy, pr * 0.38f, p)
                }
            }
            SkinStyle.RAINBOW -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = g.dp(2f)
                p.color = withAlpha(trail, 0.85f * alpha)
                c.drawCircle(x, y, pr * 1.6f, p)
            }
            else -> {}
        }

        p.style = Paint.Style.FILL
        p.color = withAlpha(head, alpha)
        c.drawCircle(x, y, pr * 1.15f, p)
        p.color = withAlpha(Pal.white, alpha)
        c.drawCircle(x, y, pr * 0.62f, p)

        if (world.shield) {
            val sc = Pal.power[World.P_SHIELD]
            g.glow(c, x, y, pr * 4.4f, sc, 0.35f)
            p.style = Paint.Style.STROKE
            p.strokeWidth = g.dp(2.4f)
            p.color = withAlpha(sc, 0.9f)
            c.drawCircle(x, y, pr * 2.0f, p)
            oval.set(x - pr * 2.0f, y - pr * 2.0f, x + pr * 2.0f, y + pr * 2.0f)
            p.strokeWidth = g.dp(3.6f)
            p.color = withAlpha(Pal.white, 0.85f)
            c.drawArc(oval, clock * 220f, 70f, false, p)
        }
    }

    /** Эмиссия частиц «стиля» кометы — вызывается раз в кадр из игрового цикла. */
    fun emitSkinParticles(s: SceneState, dt: Float) {
        if (world.dead) return
        val sk = s.skin
        val x = world.px()
        val y = world.py()
        val trail = Skins.trailColor(sk, s.clock)
        val pr = world.pRad
        when (sk.style) {
            SkinStyle.SPARKS -> if (rnd.nextFloat() < dt * 28f) {
                parts.emit(x, y, trail, 1, g.dp(70f), g.dp(2.4f), 0.45f, 2.2f)
            }
            SkinStyle.FROST -> if (rnd.nextFloat() < dt * 22f) {
                parts.emit(x + (rnd.nextFloat() - 0.5f) * pr * 2f, y + (rnd.nextFloat() - 0.5f) * pr * 2f, trail, 1, g.dp(26f), g.dp(2.8f), 0.9f, 0.8f)
            }
            SkinStyle.EMBER -> if (rnd.nextFloat() < dt * 34f) {
                parts.emit(x, y, trail, 1, g.dp(95f), g.dp(2.6f), 0.6f, 2.6f, -PI_F / 2f, 1.6f)
            }
            else -> {}
        }
    }
}
