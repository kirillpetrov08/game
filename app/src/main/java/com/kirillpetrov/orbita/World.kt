package com.kirillpetrov.orbita

import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Чистая симуляция без Android-зависимостей (тестируется на JVM).
 * Игрок летит по одной из 4 орбит вокруг ядра; касание слева/справа — орбита внутрь/наружу.
 */
class World(seed: Long = System.nanoTime()) {

    interface Events {
        fun onSwitch() {}
        fun onCrystal(x: Float, y: Float, gain: Int, mult: Int) {}
        fun onPower(type: Int, x: Float, y: Float) {}
        fun onShieldBreak(x: Float, y: Float) {}
        fun onDeath(x: Float, y: Float) {}
        fun onLevel(level: Int) {}
        fun onMeteorEnd(x: Float, y: Float) {}
        fun onGraze(x: Float, y: Float, points: Int) {}
    }

    class Barrier(val lane: Int, var angle: Float, val sweep: Float, val vel: Float, val life: Float) {
        var age = 0f
        var armed = false
        var grazeCd = 0f

        /** K_BARRIER / K_GATE / K_RING — для статистики и отрисовки. */
        var kind = K_BARRIER

        /** >= 0 у одного из «ворот»: номер свободной орбиты (рисуется как безопасный коридор). */
        var safeLane = -1

        fun active(): Boolean = age < WARN + life
    }

    class Meteor(val angle: Float, val speed: Float, var r: Float, delay: Float) {
        var age = -delay
        var dead = false
        var grazed = false
    }

    class Pickup(val lane: Int, val angle: Float, val life: Float, val type: Int) {
        var age = 0f
        var ox = 0f
        var oy = 0f
    }

    companion object {
        const val WARN = 1.0f
        const val FADE = 0.35f
        const val METEOR_WARN = 0.9f
        const val LANES = 4

        const val P_SHIELD = 0
        const val P_MAGNET = 1
        const val P_SLOW = 2
        const val MAGNET_TIME = 7f
        const val SLOW_TIME = 5f
        const val GRAZE = 0.16f

        const val K_BARRIER = 0
        const val K_METEOR = 1
        const val K_GATE = 2
        const val K_RING = 3
        const val K_SHOWER = 4
    }

    var ev: Events? = null
    private val rnd = Random(seed)

    // геометрия (задаёт View / тест)
    var cx = 0f
    var cy = 0f
    var unit = 1f

    fun laneR(f: Float): Float = unit * (0.18f + 0.085f * f)
    val bandW: Float get() = unit * 0.05f
    val pRad: Float get() = unit * 0.024f
    val coreR: Float get() = unit * 0.075f
    val meteorStartR: Float get() = unit * 0.62f

    // состояние забега
    var time = 0f
    var level = 1
    var score = 0f
    var gems = 0
    var combo = 0
    var comboT = 0f
    var shield = false
    var magnetT = 0f
    var slowT = 0f
    var hz = 1f
    var invuln = 0f
    var dead = false
    var omega = 1.3f
    var grazes = 0

    /** Что убило игрока: K_BARRIER / K_GATE / K_RING / K_METEOR (или -1). */
    var killer = -1

    var pAngle = -PI_F / 2f
    var pLane = 2
    var pLaneF = 2f

    val barriers = ArrayList<Barrier>()
    val meteors = ArrayList<Meteor>()
    val crystals = ArrayList<Pickup>()
    val powers = ArrayList<Pickup>()

    private var spawnT = 0f
    private var crystalT = 0f
    private var powerT = 0f
    private var gateCd = 0f
    private var ringCd = 0f
    private var showerCd = 0f

    fun reset() {
        time = 0f; level = 1; score = 0f; gems = 0; combo = 0; comboT = 0f; grazes = 0; killer = -1
        shield = false; magnetT = 0f; slowT = 0f; hz = 1f; invuln = 0f; dead = false; omega = 1.3f
        pAngle = -PI_F / 2f; pLane = 2; pLaneF = 2f
        barriers.clear(); meteors.clear(); crystals.clear(); powers.clear()
        spawnT = 2.6f; crystalT = 0.9f; powerT = 12f
        gateCd = 0f; ringCd = 0f; showerCd = 0f
    }

    fun revive() {
        dead = false
        invuln = 2.5f
        shield = false
        slowT = 0f
        barriers.clear(); meteors.clear()
        spawnT = 2.2f
    }

    fun scoreInt(): Int = score.toInt()
    fun mult(): Int = min(5, 1 + combo / 3)

    fun pr(): Float = laneR(pLaneF)
    fun px(): Float = cx + pr() * cos(pAngle)
    fun py(): Float = cy + pr() * sin(pAngle)

    fun moveLane(dir: Int): Boolean {
        if (dead) return false
        val n = (pLane + dir).coerceIn(0, LANES - 1)
        if (n == pLane) return false
        pLane = n
        ev?.onSwitch()
        return true
    }

    fun update(dt: Float, live: Boolean) {
        pLaneF += (pLane - pLaneF) * (1f - exp(-16f * dt))
        if (!live) {
            pAngle = wrapAngle(pAngle + 1.15f * dt)
            return
        }
        if (dead) return

        time += dt
        val nl = 1 + (time / 15f).toInt()
        if (nl != level) {
            level = nl
            ev?.onLevel(level)
        }
        omega = min(2.4f, 1.3f + 0.05f * (level - 1))
        pAngle = wrapAngle(pAngle + omega * dt)
        if (invuln > 0f) invuln -= dt
        if (magnetT > 0f) magnetT -= dt
        if (slowT > 0f) slowT -= dt
        val target = if (slowT > 0f) 0.5f else 1f
        hz += (target - hz) * min(1f, dt * 6f)
        val hdt = dt * hz

        score += dt * (6f + level * 1.5f)
        if (comboT > 0f) {
            comboT -= dt
            if (comboT <= 0f) combo = 0
        }

        spawning(dt)
        updateBarriers(hdt, dt)
        updateMeteors(hdt)
        updatePickups(dt)
    }

    // ================================================================ спавн

    private fun spawning(dt: Float) {
        if (gateCd > 0f) gateCd -= dt
        if (ringCd > 0f) ringCd -= dt
        if (showerCd > 0f) showerCd -= dt

        if (time > 2.4f) {
            spawnT -= dt
            if (spawnT <= 0f) spawnWave()
        }
        crystalT -= dt
        if (crystalT <= 0f) {
            if (crystals.size <= 4) spawnCrystals()
            crystalT = 2.2f + rnd.nextFloat() * 1.2f
        }
        powerT -= dt
        if (powerT <= 0f) {
            spawnPower()
            powerT = 16f + rnd.nextFloat() * 10f
        }
    }

    private fun spawnWave() {
        val wMet = if (level >= 2) 0.35f else 0f
        val wGate = if (level >= 3 && gateCd <= 0f) 0.30f else 0f
        val wRing = if (level >= 4 && ringCd <= 0f) 0.22f else 0f
        val wShow = if (level >= 6 && showerCd <= 0f) 0.16f else 0f
        var r = rnd.nextFloat() * (1f + wMet + wGate + wRing + wShow)
        var extra = 0f
        if (r < wGate) {
            if (spawnGate()) extra = 0.9f else spawnBarrier()
        } else {
            r -= wGate
            if (r < wRing) {
                if (spawnRing()) extra = 0.9f else spawnBarrier()
            } else {
                r -= wRing
                if (r < wShow) {
                    if (spawnShower()) extra = 1.0f else spawnBarrier()
                } else {
                    r -= wShow
                    if (r < wMet && meteors.size < 2) spawnMeteor() else spawnBarrier()
                }
            }
        }
        if (level >= 5 && rnd.nextFloat() < 0.25f) spawnBarrier()
        spawnT = max(0.6f, 1.75f - 0.06f * level) * (0.8f + 0.4f * rnd.nextFloat()) + extra
    }

    fun forceSpawn(kind: Int): Boolean = when (kind) {
        K_BARRIER -> spawnBarrier()
        K_METEOR -> spawnMeteor()
        K_GATE -> spawnGate()
        K_RING -> spawnRing()
        K_SHOWER -> spawnShower()
        else -> false
    }

    fun forcePower(type: Int) {
        val lane = pLane
        val angle = wrapAngle(pAngle + 0.9f)
        powers.add(Pickup(lane, angle, 12f, type))
    }

    private fun hasGate(): Boolean {
        for (b in barriers) if (b.safeLane >= 0 && b.active()) return true
        return false
    }

    private fun hasRing(): Boolean {
        for (b in barriers) if (b.sweep > 3f && b.active()) return true
        return false
    }

    /** Нельзя ставить препятствие так, чтобы оно перекрыло безопасный коридор ворот. */
    private fun blocksGap(lane: Int, angle: Float, sweep: Float, vel: Float): Boolean {
        for (b in barriers) {
            if (b.safeLane < 0 || !b.active() || b.safeLane != lane) continue
            val span = b.sweep / 2f + sweep / 2f + 0.8f + abs(vel) * 5.5f
            if (angleDiff(b.angle, angle) < span) return true
        }
        return false
    }

    private fun spawnBarrier(): Boolean {
        if (barriers.size >= min(9, 3 + level)) return false
        val sweepMax = min(1.15f, 0.75f + level * 0.05f)
        for (attempt in 0 until 8) {
            val lane = rnd.nextInt(LANES)
            val sweep = 0.5f + rnd.nextFloat() * (sweepMax - 0.5f)
            val ahead = 1.9f + rnd.nextFloat() * (TAU - 3.8f)
            val angle = wrapAngle(pAngle + ahead)
            val vel = if (rnd.nextFloat() < 0.5f) 0f else (0.25f + rnd.nextFloat() * 0.35f) * (if (rnd.nextBoolean()) 1f else -1f)
            if (stacked(angle, sweep, lane) || blocksGap(lane, angle, sweep, vel)) continue
            barriers.add(Barrier(lane, angle, sweep, vel, 5f + rnd.nextFloat() * 3f))
            return true
        }
        return false
    }

    private fun stacked(angle: Float, sweep: Float, lane: Int): Boolean {
        var mask = 0
        for (o in barriers) {
            if (!o.active()) continue
            val span = (o.sweep + sweep) / 2f + 0.35f
            if (angleDiff(o.angle, angle) < span) {
                if (o.lane == lane) return true
                mask = mask or (1 shl o.lane)
            }
        }
        val maxStack = if (level < 4) 1 else 2
        return Integer.bitCount(mask) >= maxStack
    }

    /** Три барьера на одном угле, одна орбита свободна — классические «ворота». */
    private fun spawnGate(): Boolean {
        if (barriers.size > 5 || hasRing() || hasGate()) return false
        val gap = rnd.nextInt(LANES)
        val sweep = 0.6f + rnd.nextFloat() * 0.12f
        val angle = wrapAngle(pAngle + 2.6f + rnd.nextFloat() * 1.2f)
        for (b in barriers) {
            if (b.lane == gap && b.active() &&
                angleDiff(b.angle, angle) < b.sweep / 2f + sweep / 2f + 0.8f + abs(b.vel) * 5.5f
            ) return false
        }
        var first = true
        for (l in 0 until LANES) {
            if (l == gap) continue
            val b = Barrier(l, angle, sweep, 0f, 4.5f)
            b.kind = K_GATE
            if (first) {
                b.safeLane = gap
                first = false
            }
            barriers.add(b)
        }
        gateCd = 7f
        return true
    }

    /** Почти полное кольцо на одной орбите с вращающимся проходом. */
    private fun spawnRing(): Boolean {
        if (barriers.size > 6 || hasGate() || hasRing()) return false
        val lane = rnd.nextInt(LANES)
        for (b in barriers) if (b.lane == lane && b.active()) return false
        val gapW = max(1.0f, 1.35f - level * 0.02f)
        val gapAngle = wrapAngle(pAngle + 2.2f + rnd.nextFloat() * 1.5f)
        val vel = (if (rnd.nextBoolean()) 1f else -1f) * (0.25f + rnd.nextFloat() * 0.2f)
        val ring = Barrier(lane, wrapAngle(gapAngle + PI_F), TAU - gapW, vel, 5.5f)
        ring.kind = K_RING
        barriers.add(ring)
        ringCd = 9f
        return true
    }

    private fun meteorSpeed(): Float = unit * (0.55f + 0.04f * level)

    private fun spawnMeteor(): Boolean {
        meteors.add(Meteor(rnd.nextFloat() * TAU, meteorSpeed(), meteorStartR, 0f))
        return true
    }

    private fun spawnShower(): Boolean {
        if (meteors.isNotEmpty()) return false
        val base = rnd.nextFloat() * TAU
        val dir = if (rnd.nextBoolean()) 1f else -1f
        for (i in 0 until 3) {
            meteors.add(Meteor(wrapAngle(base + dir * i * 0.95f), meteorSpeed(), meteorStartR, i * 0.4f))
        }
        showerCd = 10f
        return true
    }

    private fun blocked(lane: Int, angle: Float): Boolean {
        for (b in barriers) {
            if (b.lane == lane && angleDiff(b.angle, angle) < b.sweep / 2f + 0.4f) return true
        }
        return false
    }

    private fun spawnCrystals() {
        val lane = rnd.nextInt(LANES)
        val n = if (rnd.nextFloat() < 0.45f) 1 else 3 + rnd.nextInt(3)
        val room = max(0.1f, TAU - 2.4f - n * 0.34f)
        val start = wrapAngle(pAngle + 0.9f + rnd.nextFloat() * room)
        for (i in 0 until n) {
            val a = wrapAngle(start + i * 0.34f)
            if (!blocked(lane, a)) crystals.add(Pickup(lane, a, 10f, -1))
        }
    }

    private fun spawnPower() {
        if (powers.isNotEmpty()) return
        var type = rnd.nextInt(3)
        if (type == P_SHIELD && shield) type = 1 + rnd.nextInt(2)
        if (type == P_MAGNET && magnetT > 0f) type = P_SLOW
        if (type == P_SLOW && slowT > 0f) type = P_MAGNET
        val lane = rnd.nextInt(LANES)
        val angle = wrapAngle(pAngle + 1.2f + rnd.nextFloat() * (TAU - 2.4f))
        powers.add(Pickup(lane, angle, 12f, type))
    }

    // ================================================================ обновление

    private fun halfSpan(b: Barrier, radialExtra: Float): Float =
        b.sweep / 2f + (bandW * 0.5f + pRad * radialExtra) / laneR(b.lane.toFloat())

    private fun inLaneBand(b: Barrier, radialExtra: Float): Boolean =
        abs(pr() - laneR(b.lane.toFloat())) <= bandW * 0.5f + pRad * radialExtra

    private fun updateBarriers(hdt: Float, dt: Float) {
        for (b in barriers) {
            b.age += hdt
            b.angle = wrapAngle(b.angle + b.vel * hdt)
            if (b.grazeCd > 0f) b.grazeCd -= dt
            val lethalEnd = WARN + b.life
            if (!b.armed && b.age >= WARN && b.age < lethalEnd) {
                // не включаем барьер, пока игрок внутри его зоны — это честно
                if (!(inLaneBand(b, 1.0f) && angleDiff(pAngle, b.angle) < halfSpan(b, 1.0f) + 0.25f)) b.armed = true
            }
            if (b.armed && b.age < lethalEnd && !dead && inLaneBand(b, 0.6f)) {
                val edge = angleDiff(pAngle, b.angle) - halfSpan(b, 0.6f)
                if (edge < 0f) {
                    if (invuln <= 0f) takeHit(b, null)
                } else if (edge < GRAZE && invuln <= 0f && b.grazeCd <= 0f) {
                    b.grazeCd = 2f
                    graze(10)
                }
            }
        }
        barriers.removeAll { it.age > WARN + it.life + FADE }
    }

    private fun graze(points: Int) {
        score += points
        grazes++
        ev?.onGraze(px(), py(), points)
    }

    private fun updateMeteors(hdt: Float) {
        for (m in meteors) {
            m.age += hdt
            if (m.age < METEOR_WARN) continue
            m.r -= m.speed * hdt
            if (m.r < coreR * 0.8f) {
                m.dead = true
                ev?.onMeteorEnd(cx + cos(m.angle) * coreR, cy + sin(m.angle) * coreR)
                continue
            }
            if (!dead) {
                val dx = cx + cos(m.angle) * m.r - px()
                val dy = cy + sin(m.angle) * m.r - py()
                val rr = pRad * 0.8f + unit * 0.02f
                val d2 = dx * dx + dy * dy
                if (d2 < rr * rr) {
                    if (invuln <= 0f) takeHit(null, m)
                } else if (!m.grazed && invuln <= 0f && d2 < rr * rr * 3.4f) {
                    m.grazed = true
                    graze(15)
                }
            }
        }
        meteors.removeAll { it.dead }
    }

    fun pickupX(p: Pickup): Float = cx + laneR(p.lane.toFloat()) * cos(p.angle) + p.ox
    fun pickupY(p: Pickup): Float = cy + laneR(p.lane.toFloat()) * sin(p.angle) + p.oy

    private fun near(p: Pickup, k: Float): Boolean {
        val dx = pickupX(p) - px()
        val dy = pickupY(p) - py()
        val rr = (pRad + unit * 0.026f) * k
        return dx * dx + dy * dy < rr * rr
    }

    private fun updatePickups(dt: Float) {
        var i = 0
        while (i < crystals.size) {
            val c = crystals[i]
            c.age += dt
            if (magnetT > 0f) pull(c, dt)
            if (c.age > c.life) {
                crystals.removeAt(i)
                continue
            }
            if (!dead && near(c, if (magnetT > 0f) 1.4f else 1.15f)) {
                crystals.removeAt(i)
                collectCrystal(c)
                continue
            }
            i++
        }
        i = 0
        while (i < powers.size) {
            val s = powers[i]
            s.age += dt
            if (s.age > s.life) {
                powers.removeAt(i)
                continue
            }
            if (!dead && near(s, 1.3f)) {
                powers.removeAt(i)
                applyPower(s)
                continue
            }
            i++
        }
    }

    private fun pull(c: Pickup, dt: Float) {
        val dx = px() - pickupX(c)
        val dy = py() - pickupY(c)
        val d = hypot(dx, dy)
        val reach = unit * 0.36f
        if (d < reach && d > 1f) {
            val sp = unit * (0.5f + 1.2f * (1f - d / reach)) * dt
            c.ox += dx / d * sp
            c.oy += dy / d * sp
        }
    }

    private fun applyPower(s: Pickup) {
        when (s.type) {
            P_SHIELD -> shield = true
            P_MAGNET -> magnetT = MAGNET_TIME
            P_SLOW -> slowT = SLOW_TIME
        }
        ev?.onPower(s.type, pickupX(s), pickupY(s))
    }

    private fun collectCrystal(c: Pickup) {
        combo += 1
        comboT = 5f
        val m = mult()
        val gain = 20 * m
        score += gain
        gems += 1
        ev?.onCrystal(pickupX(c), pickupY(c), gain, m)
    }

    private fun takeHit(b: Barrier?, m: Meteor?) {
        if (shield) {
            shield = false
            invuln = 1.4f
            if (b != null) b.age = max(b.age, WARN + b.life)
            if (m != null) m.dead = true
            ev?.onShieldBreak(px(), py())
        } else {
            dead = true
            killer = if (b != null) b.kind else K_METEOR
            ev?.onDeath(px(), py())
        }
    }
}
