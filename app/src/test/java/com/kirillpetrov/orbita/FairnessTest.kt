package com.kirillpetrov.orbita

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Регрессионные тесты честности игры. Игровая логика — чистый Kotlin, поэтому
 * «бот-планировщик» проверяет, что хаос не превращается в непроходимые ловушки.
 */
class Bot(val w: World, val every: Float, val horizon: Float, val margin: Float) {
    private var t = 0f
    private val step = 0.05f
    fun step(dt: Float) {
        t -= dt
        if (t > 0f) return
        t = every
        decide()
    }

    /** true, если на орбите lane в момент ts (через ts секунд) будет смертельно. */
    private fun lethal(lane: Int, ts: Float): Boolean {
        val a = w.pAngle + w.omega * ts
        for (b in w.barriers) {
            val bAge = b.age + ts * w.hz
            if (bAge < World.WARN || bAge >= World.WARN + b.life) continue
            if (b.lane != lane) continue
            val ba = b.angle + b.vel * ts * w.hz
            val half = b.sweep / 2f + (w.bandW * 0.5f + w.pRad * 0.8f) / w.laneR(b.lane.toFloat()) + margin
            if (angleDiff(a, ba) < half) return true
        }
        for (m in w.meteors) {
            val mAge = m.age + ts * w.hz
            if (mAge < World.METEOR_WARN) continue
            val r = if (m.age >= World.METEOR_WARN) m.r - m.speed * ts * w.hz else m.r - m.speed * (mAge - World.METEOR_WARN)
            if (r < w.coreR) continue
            val mx = w.cx + cos(m.angle) * r
            val my = w.cy + sin(m.angle) * r
            val px = w.cx + w.laneR(lane.toFloat()) * cos(a)
            val py = w.cy + w.laneR(lane.toFloat()) * sin(a)
            val dx = mx - px; val dy = my - py
            val rad = w.unit * (0.065f + margin * 0.3f)
            if (dx * dx + dy * dy < rad * rad) return true
        }
        return false
    }

    private fun decide() {
        val n = (horizon / step).toInt()
        val INF = 1e9f
        val cost = Array(n + 1) { FloatArray(World.LANES) { INF } }
        val par = Array(n + 1) { IntArray(World.LANES) { -1 } }
        cost[0][w.pLane] = 0f
        for (i in 1..n) {
            val ts = i * step
            for (l in 0 until World.LANES) {
                var best = INF; var bp = -1
                for (pl in (l - 1)..(l + 1)) {
                    if (pl < 0 || pl >= World.LANES) continue
                    if (i <= 1 && pl != l) continue
                    if (cost[i - 1][pl] >= INF) continue
                    var c = cost[i - 1][pl]
                    if (pl != l) {
                        // переключение: опасны обе орбиты, поздние переключения чуть дешевле
                        c += 0.05f + 0.001f * (n - i)
                        if (lethal(pl, ts) || lethal(l, ts)) c += 100f
                    }
                    if (c < best) { best = c; bp = pl }
                }
                if (bp < 0) continue
                val haz = if (lethal(l, ts)) 100f else 0f
                cost[i][l] = best + haz
                par[i][l] = bp
            }
        }
        var bl = 0; var bc = INF
        for (l in 0 until World.LANES) if (cost[n][l] < bc) { bc = cost[n][l]; bl = l }
        // откатываемся к шагу 2 — первое реальное решение
        var cur = bl
        var i = n
        while (i > 2) { cur = par[i][cur]; i-- }
        val target = cur
        if (target > w.pLane) w.moveLane(1) else if (target < w.pLane) w.moveLane(-1)
    }
}


class FairnessTest {
    private fun newWorld(seed: Long): World {
        val w = World(seed)
        w.unit = 1000f
        w.cx = 500f
        w.cy = 900f
        w.reset()
        return w
    }

    private fun survival(seed: Long, kind: Int, maxT: Float): Float {
        val w = newWorld(seed)
        val bot = when (kind) {
            1 -> Bot(w, 0.05f, 2.0f, 0.04f)
            2 -> Bot(w, 0.16f, 1.1f, 0.12f)
            else -> null
        }
        var t = 0f
        val dt = 1f / 60f
        while (t < maxT && !w.dead) {
            bot?.step(dt)
            w.update(dt, true)
            t += dt
        }
        return t
    }

    private fun median(xs: List<Float>): Float = xs.sorted()[xs.size / 2]

    @Test
    fun gateAlwaysLeavesExactlyOneFreeLane() {
        var spawned = 0
        for (seed in 1L..300L) {
            val w = newWorld(seed)
            w.time = 40f
            w.update(0.01f, true)
            if (!w.forceSpawn(World.K_GATE)) continue
            spawned++
            val gate = w.barriers.filter { it.kind == World.K_GATE }
            assertEquals(3, gate.size)
            assertEquals(3, gate.map { it.lane }.toSet().size)
            val safe = gate.first { it.safeLane >= 0 }.safeLane
            assertTrue(gate.none { it.lane == safe })
        }
        assertTrue("ворота должны спавниться", spawned > 200)
    }

    @Test
    fun idlePlayerLosesQuickly() {
        val times = (1L..40L).map { survival(it, 0, 120f) }
        assertTrue("без управления игра обязана быть проиграна быстро, median=${median(times)}", median(times) < 20f)
    }

    @Test
    fun plannerSurvivesAndGameStaysChallenging() {
        val seeds = 1L..24L
        val perfect = seeds.map { survival(it, 1, 150f) }
        val human = seeds.map { survival(it, 2, 150f) }
        val p10 = perfect.sorted()[perfect.size / 10]
        assertTrue("идеальный игрок должен жить заметно дольше (p10=$p10)", p10 > 25f)
        assertTrue("человекоподобный бот: median=${median(human)}", median(human) > 35f)
        assertTrue("игра не должна быть непобедимой для бота", median(human) < 140f)
    }
}
