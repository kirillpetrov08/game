package com.kirillpetrov.orbita

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val RAD2DEG = 57.29578f

/**
 * Весь интерфейс и игра рисуются на одном Canvas: никаких XML-макетов,
 * поэтому нет расползания вёрстки на разных экранах.
 */
class GameView(
    context: Context,
    private val prefs: Prefs,
    private val sfx: Sfx,
    private val host: Host
) : View(context), Choreographer.FrameCallback, World.Events {

    interface Host {
        fun rewardedReady(): Boolean
        fun requestRewarded(onResult: (Boolean) -> Unit)
        fun appOpenWanted(): Boolean
        fun appOpenReady(): Boolean
        fun showAppOpen(onDone: () -> Unit)
        fun haptic(strong: Boolean)
        fun musicChanged(on: Boolean)
    }

    private enum class Scr { SPLASH, MENU, PLAY, PAUSE, OVER, SKINS }

    private class Btn(val id: Int) {
        val r = RectF()
        var pressed = false
        var enabled = true
        var shader: Shader? = null
    }

    private class FText(val s: String, val x: Float, val y: Float, val color: Int, val size: Float) {
        var age = 0f
    }

    companion object {
        const val B_PLAY = 1
        const val B_SKINS = 2
        const val B_VIDEO = 3
        const val B_SOUND = 4
        const val B_MUSIC = 5
        const val B_AGAIN = 6
        const val B_REVIVE = 7
        const val B_MENU = 8
        const val B_RESUME = 9
        const val B_RESTART = 10
        const val B_BACK = 11
        const val B_PAUSE = 12
        const val B_SKIN0 = 100

        const val K_PRIMARY = 0
        const val K_SEC = 1
        const val K_GOLD = 2

        const val TRAIL = 30
        const val BONUS_GEMS = 40
    }

    private val g = Gfx(context)
    private val world = World()
    private val parts = Particles(420)
    private val rnd = java.util.Random()

    private var W = 0f
    private var H = 0f
    private var scr = Scr.SPLASH
    private var scrAge = 0f
    private var clock = 0f
    private var running = false
    private var lastNs = 0L

    private var accent = Pal.accents[0]
    private var shake = 0f
    private var flash = 0f
    private var corePulse = 0f
    private var deadT = 0f
    private var countdown = 0f
    private var runT = 0f
    private var revived = false
    private var gemsBanked = 0
    private var newBest = false
    private var overScore = 0
    private var overGems = 0
    private var overLevel = 1
    private var overAnim = 0f
    private var levelPopT = 0f
    private var levelPopN = 1
    private var toastT = 0f
    private var toastS = ""
    private var splashAdStarted = false
    private var splashWait = 0f
    private var scoreStr = "0"
    private var lastScore = -1

    // ---- кисти и переиспользуемые объекты ----
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val vigPaint = Paint()
    private val oval = RectF()
    private val rr = RectF()

    // ---- фон ----
    private val starCount = 90
    private val starA = FloatArray(starCount)
    private val starR = FloatArray(starCount)
    private val starS = FloatArray(starCount)
    private val starP = FloatArray(starCount)

    // ---- хвост кометы ----
    private val trx = FloatArray(TRAIL)
    private val try_ = FloatArray(TRAIL)
    private var trN = 0
    private var trAcc = 0f

    private val ftexts = ArrayList<FText>()

    // ---- кнопки ----
    private val menuPlay = Btn(B_PLAY)
    private val menuSkins = Btn(B_SKINS)
    private val menuVideo = Btn(B_VIDEO)
    private val menuSound = Btn(B_SOUND)
    private val menuMusic = Btn(B_MUSIC)
    private val overRevive = Btn(B_REVIVE)
    private val overAgain = Btn(B_AGAIN)
    private val overMenu = Btn(B_MENU)
    private val pauseResume = Btn(B_RESUME)
    private val pauseRestart = Btn(B_RESTART)
    private val pauseMenu = Btn(B_MENU)
    private val skinBack = Btn(B_BACK)
    private val hudPause = Btn(B_PAUSE)
    private val skinCards = Array(Skins.all.size) { Btn(B_SKIN0 + it) }

    private val menuBtns = listOf(menuPlay, menuSkins, menuVideo, menuSound, menuMusic)
    private val overBtns = listOf(overRevive, overAgain, overMenu)
    private val pauseBtns = listOf(pauseResume, pauseRestart, pauseMenu)
    private val skinBtns: List<Btn> = skinCards.toList() + skinBack

    private val panel = RectF()
    private val pausePanel = RectF()
    private var pressedBtn: Btn? = null

    private val dimColor = Color.parseColor("#02030F")
    private val ink = Color.parseColor("#04061A")
    private val blueEnd = Color.parseColor("#4D8BFF")
    private val goldEnd = Color.parseColor("#FF9A1F")

    init {
        world.ev = this
        world.reset()
        isClickable = true
    }

    // ======================================================================
    // Жизненный цикл
    // ======================================================================

    fun onHostResume() {
        if (running) return
        running = true
        lastNs = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun onHostPause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        pressedBtn?.pressed = false
        pressedBtn = null
        if (scr == Scr.PLAY && !world.dead) pauseGame()
    }

    /** true — нажатие «Назад» обработано игрой. */
    fun onBack(): Boolean {
        return when (scr) {
            Scr.PLAY -> { if (!world.dead) pauseGame(); true }
            Scr.PAUSE -> { resumeFromPause(); true }
            Scr.SKINS -> { go(Scr.MENU); true }
            Scr.OVER -> { toMenu(); true }
            Scr.SPLASH -> true
            Scr.MENU -> false
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        var dt = if (lastNs == 0L) 0.016f else (frameTimeNanos - lastNs) / 1_000_000_000f
        lastNs = frameTimeNanos
        dt = dt.coerceIn(0.001f, 0.05f)
        step(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    // ======================================================================
    // Геометрия и раскладка
    // ======================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        W = w.toFloat()
        H = h.toFloat()
        world.unit = min(W, H * 0.56f)
        world.cx = W / 2f
        world.cy = H * 0.47f
        bgPaint.shader = LinearGradient(0f, 0f, 0f, H, Pal.bgTop, Pal.bgBottom, Shader.TileMode.CLAMP)
        val vr = hypot(W, H) * 0.62f
        vigPaint.shader = RadialGradient(
            W / 2f, H / 2f, vr,
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
        layoutUi()
    }

    private fun mkShader(r: RectF, c1: Int, c2: Int): Shader =
        LinearGradient(r.left, r.top, r.right, r.bottom, c1, c2, Shader.TileMode.CLAMP)

    private fun layoutUi() {
        val u = world.unit
        val m = W * 0.06f
        val gap = W * 0.03f

        // ---- меню ----
        val belowY = world.cy + u * 0.50f
        val rem = H - belowY
        val bh = min(W * 0.15f, rem * 0.30f)
        val pw = W * 0.64f
        val py0 = belowY + rem * 0.08f
        menuPlay.r.set(W / 2f - pw / 2f, py0, W / 2f + pw / 2f, py0 + bh)
        menuPlay.shader = mkShader(menuPlay.r, Pal.accents[0], blueEnd)
        val rowH = min(W * 0.12f, rem * 0.22f)
        val rowY = menuPlay.r.bottom + rem * 0.06f
        val totalW = W - 2f * m
        val skinsW = (totalW - 2f * rowH - 3f * gap) * 0.5f
        val videoW = totalW - 2f * rowH - 3f * gap - skinsW
        var x = m
        menuSkins.r.set(x, rowY, x + skinsW, rowY + rowH); x += skinsW + gap
        menuVideo.r.set(x, rowY, x + videoW, rowY + rowH); x += videoW + gap
        menuSound.r.set(x, rowY, x + rowH, rowY + rowH); x += rowH + gap
        menuMusic.r.set(x, rowY, x + rowH, rowY + rowH)

        // ---- конец игры ----
        val pm = W * 0.07f
        panel.set(pm, H * 0.13f, W - pm, H * 0.87f)
        val ph = panel.height()
        val pwid = panel.width()
        val bb = min(pwid * 0.17f, ph * 0.105f)
        val pad = ph * 0.045f
        val bg = ph * 0.022f
        overMenu.r.set(panel.left + pad, panel.bottom - pad - bb, panel.right - pad, panel.bottom - pad)
        overAgain.r.set(panel.left + pad, overMenu.r.top - bg - bb, panel.right - pad, overMenu.r.top - bg)
        overRevive.r.set(panel.left + pad, overAgain.r.top - bg - bb, panel.right - pad, overAgain.r.top - bg)
        overAgain.shader = mkShader(overAgain.r, Pal.accents[0], blueEnd)
        overRevive.shader = mkShader(overRevive.r, Pal.gold, goldEnd)

        // ---- пауза ----
        pausePanel.set(pm, H * 0.27f, W - pm, H * 0.73f)
        val ph2 = pausePanel.height()
        val bb2 = min(pausePanel.width() * 0.17f, ph2 * 0.18f)
        val pad2 = ph2 * 0.06f
        val bg2 = ph2 * 0.04f
        pauseMenu.r.set(pausePanel.left + pad2, pausePanel.bottom - pad2 - bb2, pausePanel.right - pad2, pausePanel.bottom - pad2)
        pauseRestart.r.set(pausePanel.left + pad2, pauseMenu.r.top - bg2 - bb2, pausePanel.right - pad2, pauseMenu.r.top - bg2)
        pauseResume.r.set(pausePanel.left + pad2, pauseRestart.r.top - bg2 - bb2, pausePanel.right - pad2, pauseRestart.r.top - bg2)
        pauseResume.shader = mkShader(pauseResume.r, Pal.accents[0], blueEnd)

        // ---- кометы ----
        val cardW = (W - 2f * m - gap) / 2f
        val top = H * 0.21f
        val areaH = H * 0.64f
        val cardH = (areaH - 2f * gap) / 3f
        for (i in skinCards.indices) {
            val col = i % 2
            val row = i / 2
            val l = m + col * (cardW + gap)
            val t = top + row * (cardH + gap)
            skinCards[i].r.set(l, t, l + cardW, t + cardH)
        }
        val backH = min(W * 0.13f, H * 0.07f)
        skinBack.r.set(W * 0.28f, H * 0.88f, W * 0.72f, H * 0.88f + backH)
        skinBack.shader = mkShader(skinBack.r, Pal.accents[0], blueEnd)

        // ---- HUD ----
        val pr = W * 0.05f
        val pcx = W - W * 0.09f
        val pcy = H * 0.062f
        hudPause.r.set(pcx - pr * 1.4f, pcy - pr * 1.4f, pcx + pr * 1.4f, pcy + pr * 1.4f)
        if (BuildConfig.DEBUG) post { logButtons() }
    }

    /** Только для debug: координаты кнопок на экране — по ним CI-скрипт делает тапы. */
    private fun logButtons() {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        fun l(name: String, b: Btn) {
            android.util.Log.d("OrbitaUI", "btn:$name ${(loc[0] + b.r.centerX()).toInt()} ${(loc[1] + b.r.centerY()).toInt()}")
        }
        l("PLAY", menuPlay); l("SKINS", menuSkins); l("VIDEO", menuVideo); l("BACK", skinBack)
        l("PAUSE", hudPause); l("RESUME", pauseResume); l("AGAIN", overAgain); l("MENU", overMenu); l("REVIVE", overRevive)
    }

    // ======================================================================
    // Переходы между экранами и действия
    // ======================================================================

    private fun go(s: Scr) {
        if (BuildConfig.DEBUG) android.util.Log.d("OrbitaUI", "state:$s")
        scr = s
        scrAge = 0f
        pressedBtn?.pressed = false
        pressedBtn = null
    }

    private fun toast(s: String) {
        toastS = s
        toastT = 2.2f
    }

    private fun toMenu() {
        world.reset()
        parts.clear()
        ftexts.clear()
        trN = 0
        countdown = 0f
        lastScore = -1
        go(Scr.MENU)
    }

    private fun startRun() {
        world.reset()
        parts.clear()
        ftexts.clear()
        trN = 0
        deadT = 0f
        runT = 0f
        revived = false
        gemsBanked = 0
        countdown = 0f
        lastScore = -1
        prefs.runs = prefs.runs + 1
        go(Scr.PLAY)
        sfx.play(R.raw.sfx_switch, 0.7f, 1.2f)
    }

    private fun pauseGame() {
        if (scr != Scr.PLAY || world.dead) return
        go(Scr.PAUSE)
    }

    private fun resumeFromPause() {
        go(Scr.PLAY)
        countdown = 2.4f
    }

    private fun toOver() {
        val sc = world.scoreInt()
        newBest = sc > prefs.best
        if (newBest) prefs.best = sc
        prefs.gems = prefs.gems + (world.gems - gemsBanked)
        gemsBanked = world.gems
        overScore = sc
        overGems = world.gems
        overLevel = world.level
        overAnim = 0f
        go(Scr.OVER)
        sfx.play(R.raw.sfx_over, 0.8f)
    }

    private fun doRevive() {
        world.revive()
        revived = true
        deadT = 0f
        trN = 0
        parts.clear()
        go(Scr.PLAY)
        countdown = 2.4f
        sfx.play(R.raw.sfx_revive)
    }

    private fun reviveRequest() {
        if (revived) return
        if (!host.rewardedReady()) {
            toast(Txt.t("Видео загружается, попробуй чуть позже", "Video is loading, try again soon"))
            return
        }
        host.requestRewarded { ok ->
            if (ok) doRevive() else toast(Txt.t("Награда не получена", "No reward received"))
        }
    }

    private fun bonusVideo() {
        if (!host.rewardedReady()) {
            toast(Txt.t("Видео загружается, попробуй чуть позже", "Video is loading, try again soon"))
            return
        }
        host.requestRewarded { ok ->
            if (ok) {
                prefs.gems = prefs.gems + BONUS_GEMS
                sfx.play(R.raw.sfx_crystal)
                toast(Txt.t("+$BONUS_GEMS кристаллов!", "+$BONUS_GEMS crystals!"))
            } else {
                toast(Txt.t("Награда не получена", "No reward received"))
            }
        }
    }

    private fun skinTap(i: Int) {
        val s = Skins.all[i]
        if (prefs.owns(i)) {
            prefs.skin = i
        } else if (prefs.gems >= s.cost) {
            prefs.gems = prefs.gems - s.cost
            prefs.own(i)
            prefs.skin = i
            sfx.play(R.raw.sfx_levelup)
            toast(Txt.t("Комета «${s.name()}» открыта!", "Comet \"${s.name()}\" unlocked!"))
        } else {
            toast(Txt.t("Не хватает кристаллов", "Not enough crystals"))
        }
    }

    private fun act(id: Int) {
        sfx.play(R.raw.sfx_click, 0.8f)
        when (id) {
            B_PLAY, B_AGAIN, B_RESTART -> startRun()
            B_SKINS -> go(Scr.SKINS)
            B_VIDEO -> bonusVideo()
            B_SOUND -> prefs.sound = !prefs.sound
            B_MUSIC -> {
                prefs.music = !prefs.music
                host.musicChanged(prefs.music)
            }
            B_REVIVE -> reviveRequest()
            B_MENU -> toMenu()
            B_RESUME -> resumeFromPause()
            B_BACK -> go(Scr.MENU)
            B_PAUSE -> pauseGame()
            else -> if (id >= B_SKIN0 && id < B_SKIN0 + Skins.all.size) skinTap(id - B_SKIN0)
        }
    }

    // ======================================================================
    // События мира
    // ======================================================================

    private fun skin() = Skins.all[prefs.skin.coerceIn(0, Skins.all.size - 1)]

    private fun headColor(s: Skin): Int =
        if (s.rainbow) Pal.rainbow[(clock * 22f).toInt() % Pal.rainbow.size] else s.head

    private fun trailColor(s: Skin): Int =
        if (s.rainbow) Pal.rainbow[((clock * 22f).toInt() + 12) % Pal.rainbow.size] else s.trail

    private fun floatText(s: String, x: Float, y: Float, color: Int, size: Float) {
        if (ftexts.size > 10) ftexts.removeAt(0)
        ftexts.add(FText(s, x, y, color, size))
    }

    override fun onSwitch() {
        sfx.play(R.raw.sfx_switch, 0.55f)
        parts.emit(world.px(), world.py(), headColor(skin()), 7, g.dp(110f), g.dp(3.5f), 0.35f)
    }

    override fun onCrystal(x: Float, y: Float, gain: Int, mult: Int) {
        parts.emit(x, y, Pal.gold, 16, g.dp(190f), g.dp(4f), 0.6f)
        floatText("+$gain", x, y - g.dp(18f), Pal.gold, W * 0.05f)
        if (mult > 1 && world.combo % 3 == 0) floatText("x$mult", x, y - g.dp(58f), Pal.white, W * 0.065f)
        sfx.play(R.raw.sfx_crystal, 0.9f, 1f + 0.05f * mult)
        host.haptic(false)
        corePulse = 0.6f
    }

    override fun onShieldGet(x: Float, y: Float) {
        parts.emit(x, y, Pal.shield, 20, g.dp(200f), g.dp(4f), 0.7f)
        floatText(Txt.t("ЩИТ", "SHIELD"), x, y - g.dp(20f), Pal.shield, W * 0.055f)
        sfx.play(R.raw.sfx_shield)
        host.haptic(false)
    }

    override fun onShieldBreak(x: Float, y: Float) {
        parts.emit(x, y, Pal.shield, 36, g.dp(320f), g.dp(4.5f), 0.7f)
        parts.emit(x, y, Pal.white, 12, g.dp(220f), g.dp(3f), 0.5f)
        shake = max(shake, 0.55f)
        flash = max(flash, 0.25f)
        sfx.play(R.raw.sfx_hit, 0.8f, 1.2f)
        host.haptic(true)
    }

    override fun onDeath(x: Float, y: Float) {
        val s = skin()
        parts.emit(x, y, headColor(s), 60, g.dp(420f), g.dp(5f), 1.0f)
        parts.emit(x, y, trailColor(s), 40, g.dp(260f), g.dp(4f), 1.1f)
        parts.emit(x, y, Pal.white, 18, g.dp(300f), g.dp(3f), 0.7f)
        shake = 1f
        flash = 0.7f
        deadT = 0f
        sfx.play(R.raw.sfx_hit)
        host.haptic(true)
    }

    override fun onLevel(level: Int) {
        levelPopT = 1.9f
        levelPopN = level
        flash = max(flash, 0.12f)
        sfx.play(R.raw.sfx_levelup, 0.8f)
    }

    override fun onMeteorEnd(x: Float, y: Float) {
        parts.emit(x, y, Pal.hazard, 8, g.dp(140f), g.dp(3f), 0.5f)
        corePulse = max(corePulse, 0.4f)
    }

    // ======================================================================
    // Обновление
    // ======================================================================

    private fun step(dt: Float) {
        clock += dt
        scrAge += dt

        val target = Pal.accents[(world.level - 1) % Pal.accents.size]
        accent = lerpColor(accent, target, min(1f, dt * 1.2f))
        shake = max(0f, shake - dt * 2.2f)
        flash = max(0f, flash - dt * 2.5f)
        corePulse = max(0f, corePulse - dt * 2.2f)
        if (toastT > 0f) toastT -= dt
        if (levelPopT > 0f) levelPopT -= dt
        for (t in ftexts) t.age += dt
        ftexts.removeAll { it.age > 1.0f }

        when (scr) {
            Scr.SPLASH -> stepSplash(dt)
            Scr.MENU, Scr.SKINS -> world.update(dt, false)
            Scr.PLAY -> stepPlay(dt)
            Scr.PAUSE -> {}
            Scr.OVER -> overAnim += dt
        }
        parts.update(dt)
        if (scr != Scr.PAUSE && scr != Scr.OVER && !world.dead) recordTrail(dt)
    }

    private fun stepSplash(dt: Float) {
        world.update(dt, false)
        if (splashAdStarted) {
            splashWait += dt
            if (splashWait > 4f) toMenu()
            return
        }
        val minDone = scrAge >= 1.7f
        val waited = scrAge >= 3.4f
        if (minDone && (!host.appOpenWanted() || host.appOpenReady() || waited)) {
            splashAdStarted = true
            if (host.appOpenWanted() && host.appOpenReady()) host.showAppOpen { toMenu() } else toMenu()
        }
    }

    private fun stepPlay(dt: Float) {
        runT += dt
        if (countdown > 0f) {
            countdown -= dt
            return
        }
        world.update(dt, true)
        if (world.dead) {
            deadT += dt
            if (deadT > 0.95f) toOver()
        }
        val sc = world.scoreInt()
        if (sc != lastScore) {
            lastScore = sc
            scoreStr = sc.toString()
        }
    }

    private fun recordTrail(dt: Float) {
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

    // ======================================================================
    // Ввод
    // ======================================================================

    private fun activeButtons(): List<Btn>? = when (scr) {
        Scr.MENU -> menuBtns
        Scr.OVER -> overBtns
        Scr.PAUSE -> pauseBtns
        Scr.SKINS -> skinBtns
        else -> null
    }

    private fun hit(list: List<Btn>, x: Float, y: Float): Btn? {
        for (b in list) if (b.r.contains(x, y)) return b
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                onDown(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                val b = pressedBtn
                if (b != null) b.pressed = b.r.contains(e.x, e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                onUp(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedBtn?.pressed = false
                pressedBtn = null
            }
        }
        return true
    }

    private fun onDown(x: Float, y: Float) {
        if (scr == Scr.PLAY) {
            if (hudPause.r.contains(x, y)) {
                hudPause.pressed = true
                pressedBtn = hudPause
                return
            }
            if (countdown <= 0f && !world.dead) {
                world.moveLane(if (x < W / 2f) -1 else 1)
            }
            return
        }
        if (scr == Scr.OVER && scrAge < 0.5f) return
        val list = activeButtons() ?: return
        val b = hit(list, x, y)
        if (b != null && b.enabled) {
            b.pressed = true
            pressedBtn = b
        }
    }

    private fun onUp(x: Float, y: Float) {
        val b = pressedBtn ?: return
        val fire = b.pressed && b.r.contains(x, y)
        b.pressed = false
        pressedBtn = null
        if (fire) act(b.id)
    }

    // ======================================================================
    // Отрисовка
    // ======================================================================

    override fun onDraw(canvas: Canvas) {
        if (W <= 0f) return
        canvas.drawRect(0f, 0f, W, H, bgPaint)
        drawNebula(canvas)
        drawStars(canvas)

        canvas.save()
        if (shake > 0f) {
            val s = shake * shake * g.dp(14f)
            canvas.translate((rnd.nextFloat() - 0.5f) * 2f * s, (rnd.nextFloat() - 0.5f) * 2f * s)
        }
        drawRings(canvas)
        drawCore(canvas)
        for (k in world.crystals) drawCrystal(canvas, k)
        for (k in world.shields) drawShieldPickup(canvas, k)
        for (b in world.barriers) drawBarrier(canvas, b)
        for (m in world.meteors) drawMeteor(canvas, m)
        if (scr != Scr.OVER && !world.dead) drawPlayer(canvas)
        parts.draw(canvas, g)
        drawFloatTexts(canvas)
        canvas.restore()

        canvas.drawRect(0f, 0f, W, H, vigPaint)

        val a = easeOut(scrAge / 0.3f)
        when (scr) {
            Scr.SPLASH -> drawSplash(canvas)
            Scr.MENU -> drawMenu(canvas, a)
            Scr.PLAY -> drawHud(canvas)
            Scr.PAUSE -> { drawHud(canvas); drawPause(canvas, a) }
            Scr.OVER -> drawOver(canvas, a)
            Scr.SKINS -> drawSkins(canvas, a)
        }

        if (flash > 0f) {
            p.style = Paint.Style.FILL
            p.shader = null
            p.color = withAlpha(Pal.white, flash * 0.6f)
            canvas.drawRect(0f, 0f, W, H, p)
        }
        drawToast(canvas)
    }

    private fun drawNebula(c: Canvas) {
        val c2 = lerpColor(accent, Color.parseColor("#7A3DFF"), 0.6f)
        g.glow(c, W * 0.25f + sin(clock * 0.1f) * W * 0.08f, H * 0.2f, W * 0.95f, accent, 0.13f)
        g.glow(c, W * 0.85f + cos(clock * 0.08f) * W * 0.06f, H * 0.78f, W * 0.95f, c2, 0.14f)
        g.glow(c, W * 0.2f, H * 0.9f + sin(clock * 0.07f) * H * 0.02f, W * 0.7f, accent, 0.07f)
    }

    private fun drawStars(c: Canvas) {
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

    private fun drawRings(c: Canvas) {
        val cx = world.cx
        val cy = world.cy
        val u = world.unit
        p.style = Paint.Style.STROKE
        p.shader = null
        p.strokeCap = Paint.Cap.BUTT
        for (i in 0 until World.LANES) {
            val r = world.laneR(i.toFloat())
            p.strokeWidth = world.bandW * 1.15f
            p.color = withAlpha(accent, 0.045f)
            c.drawCircle(cx, cy, r, p)
            val active = scr == Scr.PLAY && world.pLane == i && !world.dead
            p.strokeWidth = g.dp(1.3f)
            p.color = withAlpha(accent, if (active) 0.6f else 0.24f)
            c.drawCircle(cx, cy, r, p)
        }
        val rt = world.laneR(3f) + world.bandW * 0.95f
        p.strokeWidth = g.dp(1.2f)
        p.color = withAlpha(accent, 0.28f)
        val n = 72
        val rot = clock * 0.06f
        for (i in 0 until n) {
            val a = rot + i * TAU / n
            val l = if (i % 6 == 0) 0.02f else 0.01f
            val ca = cos(a)
            val sa = sin(a)
            c.drawLine(cx + ca * rt, cy + sa * rt, cx + ca * (rt + u * l), cy + sa * (rt + u * l), p)
        }
    }

    private fun drawCore(c: Canvas) {
        val cx = world.cx
        val cy = world.cy
        val cr = world.coreR * (1f + 0.06f * sin(clock * 3f) + 0.25f * corePulse)
        g.glow(c, cx, cy, cr * 3.8f, accent, 0.55f + 0.2f * corePulse)
        p.style = Paint.Style.STROKE
        p.shader = null
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = g.dp(2.2f)
        p.color = withAlpha(lerpColor(accent, Pal.white, 0.5f), 0.55f)
        for (i in 0 until 12) {
            val a = clock * 0.4f + i * TAU / 12f
            val l0 = cr * 1.2f
            val l1 = cr * (1.6f + 0.3f * sin(clock * 2f + i))
            c.drawLine(cx + cos(a) * l0, cy + sin(a) * l0, cx + cos(a) * l1, cy + sin(a) * l1, p)
        }
        p.style = Paint.Style.FILL
        p.color = lerpColor(accent, Pal.white, 0.45f)
        c.drawCircle(cx, cy, cr * 0.85f, p)
        p.color = Pal.white
        c.drawCircle(cx, cy, cr * 0.5f, p)
    }

    private fun drawBarrier(c: Canvas, b: World.Barrier) {
        val cx = world.cx
        val cy = world.cy
        val r = world.laneR(b.lane.toFloat())
        oval.set(cx - r, cy - r, cx + r, cy + r)
        val start = (b.angle - b.sweep / 2f) * RAD2DEG
        val sweep = b.sweep * RAD2DEG
        val lethalEnd = World.WARN + b.life
        p.style = Paint.Style.STROKE
        p.shader = null
        p.strokeCap = Paint.Cap.ROUND
        if (!b.armed && b.age >= lethalEnd) return
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
        val fade = if (b.age > lethalEnd) (1f - (b.age - lethalEnd) / World.FADE).coerceIn(0f, 1f) else 1f
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
            p.color = withAlpha(Pal.hazard, 0.75f * (1f - t0))
            c.drawLine(hx + ca * len * t0, hy + sa * len * t0, hx + ca * len * t1, hy + sa * len * t1, p)
        }
        g.glow(c, hx, hy, u * 0.09f, Pal.hazard, 0.85f)
        p.style = Paint.Style.FILL
        p.color = Pal.hazardHot
        c.drawCircle(hx, hy, u * 0.02f, p)
        p.color = Pal.white
        c.drawCircle(hx, hy, u * 0.01f, p)
    }

    private fun drawCrystal(c: Canvas, k: World.Pickup) {
        val x = world.pickupX(k)
        val y = world.pickupY(k)
        val remain = k.life - k.age
        var a = 1f
        if (remain < 1.6f) a = if (((remain * 8f).toInt() % 2) == 0) 1f else 0.35f
        val appear = easeOut(k.age / 0.3f)
        val s = world.unit * 0.026f * appear * (1f + 0.08f * sin(clock * 6f + k.angle * 3f))
        g.glow(c, x, y, s * 3.6f, Pal.gold, 0.55f * a)
        g.gem(c, x, y, s, Pal.gold, sin(clock * 2.5f + k.angle) * 14f, a)
    }

    private fun drawShieldPickup(c: Canvas, k: World.Pickup) {
        val x = world.pickupX(k)
        val y = world.pickupY(k)
        val remain = k.life - k.age
        var a = 1f
        if (remain < 2f) a = if (((remain * 8f).toInt() % 2) == 0) 1f else 0.35f
        val appear = easeOut(k.age / 0.3f)
        val s = world.unit * 0.03f * appear * (1f + 0.07f * sin(clock * 5f))
        g.glow(c, x, y, s * 3.8f, Pal.shield, 0.6f * a)
        g.shieldIcon(c, x, y, s, Pal.shield, a)
    }

    private fun drawPlayer(c: Canvas) {
        val s = skin()
        val head = headColor(s)
        val trail = trailColor(s)
        val alpha = if (world.invuln > 0f) 0.55f + 0.45f * sin(clock * 28f) else 1f
        val x = world.px()
        val y = world.py()
        val pr = world.pRad

        p.style = Paint.Style.STROKE
        p.shader = null
        p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until trN - 1) {
            val t = (i + 1) / trN.toFloat()
            p.strokeWidth = pr * 3.4f * t
            p.color = withAlpha(trail, 0.12f * t * alpha)
            c.drawLine(trx[i], try_[i], trx[i + 1], try_[i + 1], p)
            p.strokeWidth = pr * 1.7f * t
            p.color = withAlpha(trail, 0.8f * t * alpha)
            c.drawLine(trx[i], try_[i], trx[i + 1], try_[i + 1], p)
        }
        g.glow(c, x, y, pr * 4.6f, head, 0.65f * alpha)
        p.style = Paint.Style.FILL
        p.color = withAlpha(head, alpha)
        c.drawCircle(x, y, pr * 1.15f, p)
        p.color = withAlpha(Pal.white, alpha)
        c.drawCircle(x, y, pr * 0.62f, p)

        if (world.shield) {
            g.glow(c, x, y, pr * 4.4f, Pal.shield, 0.35f)
            p.style = Paint.Style.STROKE
            p.strokeWidth = g.dp(2.4f)
            p.color = withAlpha(Pal.shield, 0.9f)
            c.drawCircle(x, y, pr * 2.0f, p)
            oval.set(x - pr * 2.0f, y - pr * 2.0f, x + pr * 2.0f, y + pr * 2.0f)
            p.strokeWidth = g.dp(3.6f)
            p.color = withAlpha(Pal.white, 0.85f)
            c.drawArc(oval, clock * 220f, 70f, false, p)
        }
    }

    private fun drawFloatTexts(c: Canvas) {
        for (t in ftexts) {
            val k = t.age / 1.0f
            val a = 1f - k * k
            g.text(c, t.s, t.x, t.y - g.dp(46f) * easeOut(k), t.size, t.color, Paint.Align.CENTER, t.color, a)
        }
    }

    // ---------------------------------------------------------------- HUD

    private fun drawHud(c: Canvas) {
        // счёт
        g.text(c, scoreStr, W / 2f, H * 0.085f, W * 0.115f, Pal.white, Paint.Align.CENTER, accent)

        // кристаллы за забег
        val gx = W * 0.07f
        val gy = H * 0.062f
        g.glow(c, gx, gy, g.dp(18f), Pal.gold, 0.5f)
        g.gem(c, gx, gy, g.dp(8.5f), Pal.gold)
        g.text(c, world.gems.toString(), gx + g.dp(16f), gy + W * 0.02f, W * 0.055f, Pal.text, Paint.Align.LEFT)

        // комбо
        if (world.combo > 0) {
            val m = world.mult()
            g.text(c, "x$m", W / 2f, H * 0.128f, W * 0.055f, Pal.gold, Paint.Align.CENTER, Pal.gold)
            val bw = W * 0.22f
            val by = H * 0.139f
            rr.set(W / 2f - bw / 2f, by, W / 2f + bw / 2f, by + g.dp(4f))
            p.style = Paint.Style.FILL
            p.shader = null
            p.color = withAlpha(Pal.white, 0.15f)
            c.drawRoundRect(rr, g.dp(2f), g.dp(2f), p)
            rr.set(W / 2f - bw / 2f, by, W / 2f - bw / 2f + bw * (world.comboT / 5f).coerceIn(0f, 1f), by + g.dp(4f))
            p.color = Pal.gold
            c.drawRoundRect(rr, g.dp(2f), g.dp(2f), p)
        }

        // щит
        if (world.shield) {
            g.shieldIcon(c, gx, gy + g.dp(34f), g.dp(10f), Pal.shield, 1f)
        }

        // пауза
        val hb = hudPause.r
        val cx = hb.centerX()
        val cy = hb.centerY()
        val r = W * 0.05f * (if (hudPause.pressed) 0.92f else 1f)
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = withAlpha(Pal.panel, 0.75f)
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = g.dp(1.5f)
        p.color = withAlpha(accent, 0.7f)
        c.drawCircle(cx, cy, r, p)
        g.pauseIcon(c, cx, cy, r * 0.42f, Pal.text)

        // всплывашка уровня
        if (levelPopT > 0f) {
            val tt = 1.9f - levelPopT
            val a = min(1f, min(tt / 0.25f, levelPopT / 0.5f))
            g.text(c, Txt.t("УРОВЕНЬ ", "LEVEL ") + levelPopN, W / 2f, H * 0.175f, W * 0.06f, Pal.white, Paint.Align.CENTER, accent, a)
        }

        // подсказки первых забегов
        if (prefs.runs <= 3 && runT < 8f && scr == Scr.PLAY && countdown <= 0f) {
            val a = min(1f, (8f - runT) / 1f) * 0.85f
            val y = H * 0.9f
            val s = W * 0.028f
            g.chevron(c, W * 0.12f, y, s, -1, withAlpha(Pal.text, a), g.dp(3f))
            g.text(c, Txt.t("ВНУТРЬ", "INWARD"), W * 0.2f, y + W * 0.02f, W * 0.05f, Pal.text, Paint.Align.LEFT, 0, a)
            g.chevron(c, W * 0.88f, y, s, 1, withAlpha(Pal.text, a), g.dp(3f))
            g.text(c, Txt.t("НАРУЖУ", "OUTWARD"), W * 0.8f, y + W * 0.02f, W * 0.05f, Pal.text, Paint.Align.RIGHT, 0, a)
            p.style = Paint.Style.STROKE
            p.strokeWidth = g.dp(1f)
            p.color = withAlpha(Pal.text, 0.12f * a)
            c.drawLine(W / 2f, H * 0.84f, W / 2f, H * 0.96f, p)
        }

        // обратный отсчёт
        if (scr == Scr.PLAY && countdown > 0f) {
            val n = ceil(countdown).toInt()
            val frac = countdown - (n - 1)
            val sc = 0.8f + 0.4f * frac
            g.text(c, n.toString(), world.cx, world.cy + world.unit * 0.62f + W * 0.14f, W * 0.26f * sc, Pal.white, Paint.Align.CENTER, accent, frac.coerceIn(0.55f, 1f))
        }
    }

    // ---------------------------------------------------------------- кнопки

    private fun drawBtn(c: Canvas, b: Btn, label: String, kind: Int, a: Float) {
        val r = b.r
        val rad = r.height() * 0.5f
        val al = a * (if (b.enabled) 1f else 0.45f)
        val gc = when (kind) {
            K_GOLD -> Pal.gold
            K_PRIMARY -> Pal.accents[0]
            else -> accent
        }
        c.save()
        val s = if (b.pressed) 0.95f else 1f
        c.scale(s, s, r.centerX(), r.centerY())
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = g.dp(10f)
        p.color = withAlpha(gc, 0.10f * al)
        c.drawRoundRect(r, rad, rad, p)
        p.strokeWidth = g.dp(5f)
        p.color = withAlpha(gc, 0.18f * al)
        c.drawRoundRect(r, rad, rad, p)
        p.style = Paint.Style.FILL
        if (kind == K_SEC) {
            p.color = withAlpha(Pal.panel, 0.88f * al)
        } else {
            p.shader = b.shader
            p.color = Color.WHITE
            p.alpha = (255f * al).toInt()
        }
        c.drawRoundRect(r, rad, rad, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = g.dp(1.6f)
        p.color = withAlpha(gc, 0.9f * al)
        c.drawRoundRect(r, rad, rad, p)
        if (label.isNotEmpty()) {
            val tc = if (kind == K_SEC) Pal.text else ink
            val size = g.fit(label, r.width() * 0.82f, r.height() * 0.42f)
            g.text(c, label, r.centerX(), r.centerY() + size * 0.36f, size, tc, Paint.Align.CENTER, 0, al)
        }
        c.restore()
    }

    private fun drawPanel(c: Canvas, r: RectF, a: Float) {
        val rad = W * 0.05f
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = g.dp(12f)
        p.color = withAlpha(accent, 0.07f * a)
        c.drawRoundRect(r, rad, rad, p)
        p.strokeWidth = g.dp(5f)
        p.color = withAlpha(accent, 0.14f * a)
        c.drawRoundRect(r, rad, rad, p)
        p.style = Paint.Style.FILL
        p.color = withAlpha(Pal.panel, 0.94f * a)
        c.drawRoundRect(r, rad, rad, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = g.dp(1.6f)
        p.color = withAlpha(accent, 0.85f * a)
        c.drawRoundRect(r, rad, rad, p)
    }

    private fun dim(c: Canvas, k: Float) {
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = withAlpha(dimColor, k)
        c.drawRect(0f, 0f, W, H, p)
    }

    // ---------------------------------------------------------------- экраны

    private fun drawSplash(c: Canvas) {
        val a = easeOut(scrAge / 0.9f)
        val ts = min(W * 0.18f, H * 0.1f) * (0.85f + 0.15f * a)
        g.text(c, Txt.t("ОРБИТА", "ORBITA"), W / 2f, H * 0.2f, ts, Pal.white, Paint.Align.CENTER, accent, a)
        g.text(c, Txt.t("неоновый забег по орбитам", "neon orbit runner"), W / 2f, H * 0.2f + ts * 0.5f, ts * 0.27f, Pal.dim, Paint.Align.CENTER, 0, a)
        // индикатор загрузки
        val r = W * 0.035f
        val y = H * 0.86f
        oval.set(W / 2f - r, y - r, W / 2f + r, y + r)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = g.dp(3f)
        p.color = withAlpha(accent, 0.25f * a)
        c.drawCircle(W / 2f, y, r, p)
        p.color = withAlpha(accent, a)
        c.drawArc(oval, clock * 360f, 90f, false, p)
    }

    private fun drawMenu(c: Canvas, a: Float) {
        val ts = min(W * 0.15f, H * 0.085f)
        val ty = H * 0.075f + ts * 0.35f
        g.text(c, Txt.t("ОРБИТА", "ORBITA"), W / 2f, ty, ts, Pal.white, Paint.Align.CENTER, accent, a)
        g.text(c, Txt.t("неоновый забег по орбитам", "neon orbit runner"), W / 2f, ty + ts * 0.5f, ts * 0.27f, Pal.dim, Paint.Align.CENTER, 0, a)

        val ly = H * 0.178f
        val fs = W * 0.042f
        g.text(c, Txt.t("РЕКОРД ", "BEST ") + prefs.best, W * 0.07f, ly, fs, Pal.text, Paint.Align.LEFT, 0, a)
        val gs = prefs.gems.toString()
        val gw = g.measure(gs, fs)
        g.text(c, gs, W * 0.93f, ly, fs, Pal.text, Paint.Align.RIGHT, 0, a)
        g.glow(c, W * 0.93f - gw - g.dp(18f), ly - fs * 0.34f, g.dp(18f), Pal.gold, 0.5f * a)
        g.gem(c, W * 0.93f - gw - g.dp(18f), ly - fs * 0.34f, g.dp(8f), Pal.gold, 0f, a)

        drawBtn(c, menuPlay, Txt.t("ИГРАТЬ", "PLAY"), K_PRIMARY, a)
        drawBtn(c, menuSkins, Txt.t("КОМЕТЫ", "COMETS"), K_SEC, a)

        menuVideo.enabled = true
        val ready = host.rewardedReady()
        drawBtn(c, menuVideo, "", K_SEC, a * (if (ready) 1f else 0.55f))
        run {
            val r = menuVideo.r
            val size = r.height() * 0.40f
            val txt = "+$BONUS_GEMS"
            val tw = g.measure(txt, size)
            val total = r.height() * 0.5f + tw + r.height() * 0.5f
            var x = r.centerX() - total / 2f
            val al = a * (if (ready) 1f else 0.55f)
            g.playIcon(c, x + r.height() * 0.2f, r.centerY(), r.height() * 0.2f, withAlpha(Pal.text, al))
            x += r.height() * 0.5f
            g.text(c, txt, x, r.centerY() + size * 0.36f, size, Pal.gold, Paint.Align.LEFT, 0, al)
            g.gem(c, x + tw + r.height() * 0.28f, r.centerY(), r.height() * 0.16f, Pal.gold, 0f, al)
        }

        drawBtn(c, menuSound, "", K_SEC, a)
        g.speakerIcon(c, menuSound.r.centerX() - menuSound.r.height() * 0.05f, menuSound.r.centerY(), menuSound.r.height() * 0.26f,
            withAlpha(if (prefs.sound) Pal.text else Pal.dim, a), prefs.sound)
        drawBtn(c, menuMusic, "", K_SEC, a)
        g.noteIcon(c, menuMusic.r.centerX(), menuMusic.r.centerY(), menuMusic.r.height() * 0.26f,
            withAlpha(if (prefs.music) Pal.text else Pal.dim, a), prefs.music)
    }

    private fun drawOver(c: Canvas, a: Float) {
        dim(c, 0.72f * a)
        drawPanel(c, panel, a)
        val ph = panel.height()
        val cx = panel.centerX()
        val top = panel.top

        g.text(c, Txt.t("КОНЕЦ ПОЛЁТА", "GAME OVER"), cx, top + ph * 0.095f, g.fit(Txt.t("КОНЕЦ ПОЛЁТА", "GAME OVER"), panel.width() * 0.8f, W * 0.075f), Pal.white, Paint.Align.CENTER, Pal.hazard, a)
        g.text(c, Txt.t("СЧЁТ", "SCORE"), cx, top + ph * 0.18f, W * 0.04f, Pal.dim, Paint.Align.CENTER, 0, a)
        val shown = (overScore * easeOut(overAnim / 0.9f)).toInt()
        g.text(c, shown.toString(), cx, top + ph * 0.30f, W * 0.17f, Pal.white, Paint.Align.CENTER, accent, a)

        if (newBest) {
            val pulse = 0.75f + 0.25f * sin(clock * 6f)
            g.text(c, Txt.t("НОВЫЙ РЕКОРД!", "NEW BEST!"), cx, top + ph * 0.375f, W * 0.05f, Pal.gold, Paint.Align.CENTER, Pal.gold, a * pulse)
        } else {
            g.text(c, Txt.t("РЕКОРД ", "BEST ") + prefs.best, cx, top + ph * 0.375f, W * 0.045f, Pal.dim, Paint.Align.CENTER, 0, a)
        }

        val sy = top + ph * 0.46f
        val fs = W * 0.05f
        val gs = overGems.toString()
        val gx = cx - panel.width() * 0.2f
        g.glow(c, gx - g.dp(24f), sy - fs * 0.34f, g.dp(20f), Pal.gold, 0.5f * a)
        g.gem(c, gx - g.dp(24f), sy - fs * 0.34f, g.dp(9f), Pal.gold, 0f, a)
        g.text(c, gs, gx, sy, fs, Pal.text, Paint.Align.LEFT, 0, a)
        g.text(c, Txt.t("УР. ", "LVL ") + overLevel, cx + panel.width() * 0.2f, sy, fs, Pal.text, Paint.Align.CENTER, 0, a)

        // воскрешение
        val canRevive = !revived
        overRevive.enabled = canRevive
        val ready = host.rewardedReady()
        drawBtn(c, overRevive, "", K_GOLD, a * (if (canRevive && !ready) 0.6f else 1f))
        run {
            val r = overRevive.r
            val label = if (!canRevive) Txt.t("УЖЕ ИСПОЛЬЗОВАНО", "ALREADY USED")
            else if (ready) Txt.t("ПРОДОЛЖИТЬ", "CONTINUE") else Txt.t("ЗАГРУЗКА…", "LOADING…")
            val size = g.fit(label, r.width() * 0.6f, r.height() * 0.4f)
            val tw = g.measure(label, size)
            val total = r.height() * 0.6f + tw
            val x = r.centerX() - total / 2f
            val al = a * (if (canRevive && ready) 1f else 0.5f)
            if (canRevive) g.playIcon(c, x + r.height() * 0.15f, r.centerY(), r.height() * 0.2f, withAlpha(ink, al))
            g.text(c, label, x + r.height() * 0.6f, r.centerY() + size * 0.36f, size, ink, Paint.Align.LEFT, 0, al)
        }
        drawBtn(c, overAgain, Txt.t("ЕЩЁ РАЗ", "AGAIN"), K_PRIMARY, a)
        drawBtn(c, overMenu, Txt.t("МЕНЮ", "MENU"), K_SEC, a)
    }

    private fun drawPause(c: Canvas, a: Float) {
        dim(c, 0.6f * a)
        drawPanel(c, pausePanel, a)
        g.text(c, Txt.t("ПАУЗА", "PAUSED"), pausePanel.centerX(), pausePanel.top + pausePanel.height() * 0.15f, W * 0.08f, Pal.white, Paint.Align.CENTER, accent, a)
        drawBtn(c, pauseResume, Txt.t("ПРОДОЛЖИТЬ", "RESUME"), K_PRIMARY, a)
        drawBtn(c, pauseRestart, Txt.t("ЗАНОВО", "RESTART"), K_SEC, a)
        drawBtn(c, pauseMenu, Txt.t("МЕНЮ", "MENU"), K_SEC, a)
    }

    private fun drawSkins(c: Canvas, a: Float) {
        dim(c, 0.82f * a)
        g.text(c, Txt.t("КОМЕТЫ", "COMETS"), W / 2f, H * 0.095f, W * 0.09f, Pal.white, Paint.Align.CENTER, accent, a)
        val fs = W * 0.05f
        val gs = prefs.gems.toString()
        val gw = g.measure(gs, fs)
        val total = g.dp(30f) + gw
        val x0 = W / 2f - total / 2f
        g.glow(c, x0 + g.dp(10f), H * 0.157f - fs * 0.34f, g.dp(20f), Pal.gold, 0.5f * a)
        g.gem(c, x0 + g.dp(10f), H * 0.157f - fs * 0.34f, g.dp(9f), Pal.gold, 0f, a)
        g.text(c, gs, x0 + g.dp(30f), H * 0.157f, fs, Pal.text, Paint.Align.LEFT, 0, a)
        for (i in skinCards.indices) drawSkinCard(c, i, a)
        drawBtn(c, skinBack, Txt.t("НАЗАД", "BACK"), K_PRIMARY, a)
    }

    private fun drawSkinCard(c: Canvas, i: Int, a: Float) {
        val b = skinCards[i]
        val s = Skins.all[i]
        val r = b.r
        val owned = prefs.owns(i)
        val sel = prefs.skin == i
        val head = if (s.rainbow) Pal.rainbow[((clock * 22f).toInt() + i * 3) % Pal.rainbow.size] else s.head
        val trail = if (s.rainbow) Pal.rainbow[((clock * 22f).toInt() + 15 + i * 3) % Pal.rainbow.size] else s.trail
        val rad = W * 0.04f

        c.save()
        val sc = if (b.pressed) 0.96f else 1f
        c.scale(sc, sc, r.centerX(), r.centerY())
        p.shader = null
        if (sel) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = g.dp(10f)
            p.color = withAlpha(head, 0.16f * a)
            c.drawRoundRect(r, rad, rad, p)
        }
        p.style = Paint.Style.FILL
        p.color = withAlpha(Pal.panel, 0.92f * a)
        c.drawRoundRect(r, rad, rad, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = g.dp(if (sel) 2.4f else 1.4f)
        p.color = if (sel) withAlpha(head, a) else withAlpha(if (owned) accent else Pal.dim, (if (owned) 0.6f else 0.35f) * a)
        c.drawRoundRect(r, rad, rad, p)

        // превью: орбита с кометой
        val pcx = r.centerX()
        val pcy = r.top + r.height() * 0.36f
        val orR = r.height() * 0.19f
        val la = if (owned) 1f else 0.6f
        p.strokeWidth = g.dp(1.2f)
        p.color = withAlpha(Pal.text, 0.18f * a)
        c.drawCircle(pcx, pcy, orR, p)
        val ha = clock * 1.4f + i * 0.9f
        oval.set(pcx - orR, pcy - orR, pcx + orR, pcy + orR)
        p.strokeCap = Paint.Cap.ROUND
        for (k in 0 until 3) {
            p.strokeWidth = r.height() * (0.06f - 0.014f * k)
            p.color = withAlpha(trail, (0.22f + 0.2f * k) * a * la)
            c.drawArc(oval, (ha - 1.0f + 0.25f * k) * RAD2DEG, 1.0f * RAD2DEG - 0.25f * k * RAD2DEG, false, p)
        }
        val hx = pcx + cos(ha) * orR
        val hy = pcy + sin(ha) * orR
        g.glow(c, hx, hy, r.height() * 0.17f, head, 0.7f * a * la)
        p.style = Paint.Style.FILL
        p.color = withAlpha(head, a * la)
        c.drawCircle(hx, hy, r.height() * 0.045f, p)
        p.color = withAlpha(Pal.white, a * la)
        c.drawCircle(hx, hy, r.height() * 0.025f, p)

        // название
        val nm = s.name()
        val ns = g.fit(nm, r.width() * 0.82f, r.height() * 0.11f)
        g.text(c, nm, r.centerX(), r.top + r.height() * 0.71f, ns, Pal.text, Paint.Align.CENTER, 0, a)

        // статус
        val sy = r.top + r.height() * 0.895f
        val ss = r.height() * 0.085f
        if (sel) {
            g.text(c, Txt.t("ВЫБРАНО", "SELECTED"), r.centerX(), sy, ss, head, Paint.Align.CENTER, 0, a)
        } else if (owned) {
            g.text(c, Txt.t("ВЫБРАТЬ", "SELECT"), r.centerX(), sy, ss, Pal.dim, Paint.Align.CENTER, 0, a)
        } else {
            val can = prefs.gems >= s.cost
            val col = if (can) Pal.gold else Pal.dim
            val cs = s.cost.toString()
            val cw = g.measure(cs, ss)
            val tot = ss * 1.5f + cw
            val x = r.centerX() - tot / 2f
            g.gem(c, x + ss * 0.45f, sy - ss * 0.34f, ss * 0.5f, col, 0f, a)
            g.text(c, cs, x + ss * 1.5f, sy, ss, col, Paint.Align.LEFT, 0, a)
        }
        c.restore()
    }

    private fun drawToast(c: Canvas) {
        if (toastT <= 0f) return
        val a = min(1f, toastT / 0.3f)
        val size = g.fit(toastS, W * 0.8f, W * 0.04f)
        val tw = g.measure(toastS, size)
        val h = size * 2.2f
        rr.set(W / 2f - tw / 2f - size, H * 0.93f - h / 2f, W / 2f + tw / 2f + size, H * 0.93f + h / 2f)
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = withAlpha(Pal.panel, 0.95f * a)
        c.drawRoundRect(rr, h / 2f, h / 2f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = g.dp(1.3f)
        p.color = withAlpha(accent, 0.8f * a)
        c.drawRoundRect(rr, h / 2f, h / 2f, p)
        g.text(c, toastS, W / 2f, H * 0.93f + size * 0.36f, size, Pal.text, Paint.Align.CENTER, 0, a)
    }
}
