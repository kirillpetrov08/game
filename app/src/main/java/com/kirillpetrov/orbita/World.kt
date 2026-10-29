package com.kirillpetrov.orbita

import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Симуляция без отрисовки. Игрок летит по одной из 4 орбит вокруг ядра,
 * касание слева/справа переключает орбиту внутрь/наружу.
 */
class World {
    interface Events {
        fun onSwitch()
        fun onCrystal(x: Float, y: Float, gain: Int, mult: Int)
        fun onShieldGet(x: Float, y: Float)
        fun onShieldBreak(x: Float, y: Float)
        fun onDeath(x: Float, y: Float)
        fun onLevel(level: Int)
        fun onMeteorEnd(x: Float, y: Float)
    }

    class Barrier(val lane: Int, var angle: Float, val sweep: Float, val vel: Float, val life: Float) {
        var age = 0f
        var armed = false
    }

    class Meteor(val angle: Float, val speed: Float, var r: Float) {
        var age = 0f
        var dead = false
    }

    class Pickup(val lane: Int, val angle: Float, val life: Float) {
        var age = 0f
    }

    companion object {
        const val WARN = 1.0f
        const val FADE = 0.35f
        const val METEOR_WARN = 0.9f
        const val LANES = 4
    }

    var ev: Events? = null
    private val rnd = Random()

    // геометрия (задаёт View)
    var cx = 0f
    var cy = 0f
    var unit = 1f

    fun laneR(f: Float): Float = unit * (0.18f + 0.085f * f)
    val bandW: Float get() = unit * 0.05f
    val pRad: Float get() = unit * 0.024f
    val coreR: Float get() = unit * 0.075f

    // состояние забега
    var time = 0f
    var level = 1
    var score = 0f
    var gems = 0
    var combo = 0
    var comboT = 0f
    var shield = false
    var invuln = 0f
    var dead = false
    var omega = 1.3f

    var pAngle = -PI_F / 2f
    var pLane = 2
    var pLaneF = 2f

    val barriers = ArrayList<Barrier>()
    val meteors = ArrayList<Meteor>()
    val crystals = ArrayList<Pickup>()
    val shields = ArrayList<Pickup>()

    private var spawnT = 0f
    private var crystalT = 0f
    private var shieldT = 0f

    fun reset() {
        time = 0f; level = 1; score = 0f; gems = 0; combo = 0; comboT = 0f
        shield = false; invuln = 0f; dead = false; omega = 1.3f
        pAngle = -PI_F / 2f; pLane = 2; pLaneF = 2f
        barriers.clear(); meteors.clear(); crystals.clear(); shields.clear()
        spawnT = 2.6f; crystalT = 0.9f; shieldT = 24f
    }

    fun revive() {
        dead = false
        invuln = 2.5f
        shield = false
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
        score += dt * (6f + level * 1.5f)
        if (comboT > 0f) {
            comboT -= dt
            if (comboT <= 0f) combo = 0
        }

        spawning(dt)
        updateBarriers(dt)
        updateMeteors(dt)
        updatePickups(dt)
    }

    // ---------- спавн ----------

    private fun spawning(dt: Float) {
        if (time > 2.4f) {
            spawnT -= dt
            if (spawnT <= 0f) {
                spawnHazard()
                spawnT = maxOf(0.6f, 1.75f - 0.07f * level) * (0.8f + 0.4f * rnd.nextFloat())
            }
        }
        crystalT -= dt
        if (crystalT <= 0f) {
            if (crystals.size < 3) spawnCrystal()
            crystalT = 1.6f + rnd.nextFloat() * 1.0f
        }
        shieldT -= dt
        if (shieldT <= 0f) {
            if (!shield && shields.isEmpty()) spawnShield()
            shieldT = 28f + rnd.nextFloat() * 12f
        }
    }

    private fun spawnHazard() {
        val roll = rnd.nextFloat()
        if (level >= 2 && roll < 0.28f && meteors.size < 2) spawnMeteor() else spawnBarrier()
        if (level >= 5 && rnd.nextFloat() < 0.25f) spawnBarrier()
    }

    private fun spawnBarrier() {
        if (barriers.size >= min(9, 3 + level)) return
        val sweepMax = min(1.15f, 0.75f + level * 0.05f)
        for (attempt in 0 until 8) {
            val lane = rnd.nextInt(LANES)
            val sweep = 0.5f + rnd.nextFloat() * (sweepMax - 0.5f)
            val ahead = 1.9f + rnd.nextFloat() * (TAU - 3.8f)
            val angle = wrapAngle(pAngle + ahead)
            if (stacked(angle, sweep, lane)) continue
            val vel = if (rnd.nextFloat() < 0.5f) 0f else (0.25f + rnd.nextFloat() * 0.35f) * (if (rnd.nextBoolean()) 1f else -1f)
            val life = 5f + rnd.nextFloat() * 3f
            barriers.add(Barrier(lane, angle, sweep, vel, life))
            return
        }
    }

    private fun stacked(angle: Float, sweep: Float, lane: Int): Boolean {
        var mask = 0
        for (o in barriers) {
            val span = (o.sweep + sweep) / 2f + 0.35f
            if (angleDiff(o.angle, angle) < span) {
                if (o.lane == lane) return true
                mask = mask or (1 shl o.lane)
            }
        }
        val maxStack = if (level < 4) 1 else 2
        return Integer.bitCount(mask) >= maxStack
    }

    private fun spawnMeteor() {
        val a = rnd.nextFloat() * TAU
        val speed = unit * (0.55f + 0.04f * level)
        meteors.add(Meteor(a, speed, unit * 0.62f))
    }

    private fun spawnCrystal() {
        for (attempt in 0 until 6) {
            val lane = rnd.nextInt(LANES)
            val angle = wrapAngle(pAngle + 0.7f + rnd.nextFloat() * (TAU - 1.4f))
            var ok = true
            for (b in barriers) {
                if (b.lane == lane && angleDiff(b.angle, angle) < b.sweep / 2f + 0.35f) { ok = false; break }
            }
            if (ok) {
                crystals.add(Pickup(lane, angle, 9f))
                return
            }
        }
    }

    private fun spawnShield() {
        val lane = rnd.nextInt(LANES)
        val angle = wrapAngle(pAngle + 1.2f + rnd.nextFloat() * (TAU - 2.4f))
        shields.add(Pickup(lane, angle, 11f))
    }

    // ---------- обновление объектов ----------

    private fun barrierHit(b: Barrier, extra: Float, radialExtra: Float): Boolean {
        val lr = laneR(b.lane.toFloat())
        val rad = bandW * 0.5f + pRad * radialExtra
        if (abs(pr() - lr) > rad) return false
        val half = b.sweep / 2f + (bandW * 0.5f + pRad * radialExtra) / lr + extra
        return angleDiff(pAngle, b.angle) < half
    }

    private fun updateBarriers(dt: Float) {
        for (b in barriers) {
            b.age += dt
            b.angle = wrapAngle(b.angle + b.vel * dt)
            val lethalEnd = WARN + b.life
            if (!b.armed && b.age >= WARN && b.age < lethalEnd) {
                // не включаем барьер, пока игрок внутри его зоны — это честно
                if (!barrierHit(b, 0.25f, 1.0f)) b.armed = true
            }
            if (b.armed && b.age < lethalEnd && invuln <= 0f && !dead) {
                if (barrierHit(b, 0f, 0.6f)) takeHit(b, null)
            }
        }
        barriers.removeAll { it.age > WARN + it.life + FADE }
    }

    private fun updateMeteors(dt: Float) {
        for (m in meteors) {
            m.age += dt
            if (m.age < METEOR_WARN) continue
            m.r -= m.speed * dt
            if (m.r < coreR * 0.8f) {
                m.dead = true
                ev?.onMeteorEnd(cx + cos(m.angle) * coreR, cy + sin(m.angle) * coreR)
                continue
            }
            if (invuln <= 0f && !dead) {
                val mx = cx + cos(m.angle) * m.r
                val my = cy + sin(m.angle) * m.r
                val dx = mx - px()
                val dy = my - py()
                val rr = pRad * 0.8f + unit * 0.02f
                if (dx * dx + dy * dy < rr * rr) takeHit(null, m)
            }
        }
        meteors.removeAll { it.dead }
    }

    private fun updatePickups(dt: Float) {
        val it = crystals.iterator()
        while (it.hasNext()) {
            val c = it.next()
            c.age += dt
            if (c.age > c.life) { it.remove(); continue }
            if (!dead && near(c, 1.15f)) {
                it.remove()
                collectCrystal(c)
            }
        }
        val it2 = shields.iterator()
        while (it2.hasNext()) {
            val s = it2.next()
            s.age += dt
            if (s.age > s.life) { it2.remove(); continue }
            if (!dead && near(s, 1.25f)) {
                it2.remove()
                shield = true
                ev?.onShieldGet(pickupX(s), pickupY(s))
            }
        }
    }

    fun pickupX(p: Pickup): Float = cx + laneR(p.lane.toFloat()) * cos(p.angle)
    fun pickupY(p: Pickup): Float = cy + laneR(p.lane.toFloat()) * sin(p.angle)

    private fun near(p: Pickup, k: Float): Boolean {
        val dx = pickupX(p) - px()
        val dy = pickupY(p) - py()
        val rr = (pRad + unit * 0.026f) * k
        return dx * dx + dy * dy < rr * rr
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
            if (b != null) b.age = maxOf(b.age, WARN + b.life)
            if (m != null) m.dead = true
            ev?.onShieldBreak(px(), py())
        } else {
            dead = true
            ev?.onDeath(px(), py())
        }
    }
}
