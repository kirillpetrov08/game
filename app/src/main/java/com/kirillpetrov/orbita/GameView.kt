package com.kirillpetrov.orbita

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val RAD2DEG = 57.29578f

/**
 * Игровой экран. Весь интерфейс рисуется на одном Canvas (никаких XML-макетов),
 * поэтому вёрстка не «расползается» на разных экранах. Сцена рисуется в [Painter],
 * здесь — состояние, ввод, раскладка и экраны интерфейса.
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
        fun musicLevel(level: Int)
        fun runState(inRun: Boolean)
        fun warmUp()
    }

    private enum class Scr { SPLASH, MENU, PLAY, PAUSE, OVER, SKINS, SETTINGS, HOWTO }

    private class Btn(val id: Int, val label: String = "") {
        val r = RectF()
        var pressed = false
        var shader: Shader? = null
        var labelSize = 0f
    }

    private class FText(val s: String, val x: Float, val y: Float, val color: Int, val size: Float) {
        var age = 0f
    }

    companion object {
        const val B_PLAY = 1
        const val B_SKINS = 2
        const val B_GIFT = 3
        const val B_VIDEO = 4
        const val B_GEAR = 5
        const val B_AGAIN = 6
        const val B_REVIVE = 7
        const val B_DOUBLE = 8
        const val B_MENU = 9
        const val B_RESUME = 10
        const val B_RESTART = 11
        const val B_BACK = 12
        const val B_PAUSE = 13
        const val B_SOUND = 14
        const val B_MUSIC = 15
        const val B_VIB = 16
        const val B_HOWTO = 17
        const val B_GOTIT = 18
        const val B_SKIN0 = 100

        const val K_PRIMARY = 0
        const val K_SEC = 1
        const val K_GOLD = 2

        const val BONUS_GEMS = 40
    }

    // ---- строки (из ресурсов, один раз) ----
    private val sTitle = context.getString(R.string.title)
    private val sTagline = context.getString(R.string.tagline)
    private val sScore = context.getString(R.string.score)
    private val sGameOver = context.getString(R.string.game_over)
    private val sNewBest = context.getString(R.string.new_best)
    private val sContinue = context.getString(R.string.continue_)
    private val sPaused = context.getString(R.string.paused)
    private val sInward = context.getString(R.string.inward)
    private val sOutward = context.getString(R.string.outward)
    private val sLoading = context.getString(R.string.loading)
    private val sUsed = context.getString(R.string.already_used)
    private val sSelected = context.getString(R.string.selected)
    private val sSelect = context.getString(R.string.select)
    private val sGift = context.getString(R.string.gift)
    private val sGiftWait = context.getString(R.string.gift_wait)
    private val sSettings = context.getString(R.string.settings)
    private val sSound = context.getString(R.string.sound)
    private val sMusic = context.getString(R.string.music)
    private val sVib = context.getString(R.string.vibration)
    private val sHowto = context.getString(R.string.howto)
    private val sComets = context.getString(R.string.comets)
    private val sGraze = context.getString(R.string.graze)
    private val sPowerNames = arrayOf(
        context.getString(R.string.power_shield),
        context.getString(R.string.power_magnet),
        context.getString(R.string.power_slow)
    )
    private val howTexts = arrayOf(
        context.getString(R.string.howto_1), context.getString(R.string.howto_2),
        context.getString(R.string.howto_3), context.getString(R.string.howto_4)
    )
    private val sVersion = context.getString(R.string.version_fmt, BuildConfig.VERSION_NAME)

    private val g = Gfx(context)
    private val world = World()
    private val parts = Particles(420)
    private val waves = Shockwaves(16)
    private val painter = Painter(g, world, parts, waves)
    private val scene = SceneState()
    private val rnd = java.util.Random()

    private var W = 0f
    private var H = 0f
    private var U = 1f
    private var scr = Scr.SPLASH
    private var scrAge = 0f
    private var clock = 0f
    private var running = false
    private var lastNs = 0L

    private var accent = Pal.accents[0]
    private var shake = 0f
    private var flash = 0f
    private var flashColor = Pal.white
    private var corePulse = 0f
    private var scorePop = 0f
    private var deadT = 0f
    private var countdown = 0f
    private var runT = 0f
    private var revived = false
    private var doubled = false
    private var gemsBanked = 0
    private var newBest = false
    private var overScore = 0
    private var overGems = 0
    private var overLevel = 1
    private var overAnim = 0f
    private var levelPopT = 0f
    private var levelPopStr = ""
    private var toastT = 0f
    private var toastS = ""
    private var splashAdStarted = false
    private var splashWait = 0f
    private var warmT = 0f
    private var pendingStart = false
    private var howBack = Scr.MENU

    // отладка (debug-сборка): заморозка мира для скриншотов
    private var freezeIn = -1f
    private var frozen = false

    // ---- кэш строк и настроек (не аллоцируем в onDraw) ----
    private var scoreStr = "0"
    private var lastScore = -1
    private var runGemsStr = "0"
    private var lastRunGems = -1
    private var comboStr = ""
    private var lastMult = 0
    private var bestStr = ""
    private var gemsStr = "0"
    private var gemsW = 0f
    private var statSize = 0f
    private var soundOn = true
    private var musicOn = true
    private var vibOn = true
    private var giftAvail = false
    private var skinIdx = 0

    private val ftexts = ArrayList<FText>()

    // ---- кнопки ----
    private val menuPlay = Btn(B_PLAY, context.getString(R.string.play))
    private val menuSkins = Btn(B_SKINS, sComets)
    private val menuGift = Btn(B_GIFT)
    private val menuVideo = Btn(B_VIDEO)
    private val menuGear = Btn(B_GEAR)
    private val overRevive = Btn(B_REVIVE)
    private val overDouble = Btn(B_DOUBLE)
    private val overAgain = Btn(B_AGAIN, context.getString(R.string.again))
    private val overMenu = Btn(B_MENU, context.getString(R.string.menu))
    private val pauseResume = Btn(B_RESUME, context.getString(R.string.resume))
    private val pauseRestart = Btn(B_RESTART, context.getString(R.string.restart))
    private val pauseMenu = Btn(B_MENU, context.getString(R.string.menu))
    private val setSound = Btn(B_SOUND)
    private val setMusic = Btn(B_MUSIC)
    private val setVib = Btn(B_VIB)
    private val setHow = Btn(B_HOWTO, sHowto)
    private val setBack = Btn(B_BACK, context.getString(R.string.back))
    private val howOk = Btn(B_GOTIT, context.getString(R.string.got_it))
    private val skinBack = Btn(B_BACK, context.getString(R.string.back))
    private val hudPause = Btn(B_PAUSE)
    private val skinCards = Array(Skins.all.size) { Btn(B_SKIN0 + it) }

    private val menuBtns = listOf(menuPlay, menuSkins, menuGift, menuVideo, menuGear)
    private val overBtns = listOf(overRevive, overDouble, overAgain, overMenu)
    private val pauseBtns = listOf(pauseResume, pauseRestart, pauseMenu)
    private val setBtns = listOf(setSound, setMusic, setVib, setHow, setBack)
    private val howBtns = listOf(howOk)
    private val skinBtns: List<Btn> = skinCards.toList() + skinBack

    private val panel = RectF()
    private val pausePanel = RectF()
    private val setPanel = RectF()
    private val howPanel = RectF()
    private var howLines: Array<List<String>> = emptyArray()
    private var howSize = 0f
    private var howIconR = 0f
    private var howTextX = 0f
    private var howRowTop = 0f
    private var howRowH = 0f
    private var giftSize = 0f
    private val reviveSizes = FloatArray(3)
    private var pressedBtn: Btn? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rr = RectF()
    private val oval = RectF()

    init {
        world.ev = this
        world.reset()
        isSoundEffectsEnabled = false
        contentDescription = context.getString(R.string.app_name)
        syncPrefs()
    }

    // ======================================================================
    // Жизненный цикл
    // ======================================================================

    fun isInRun(): Boolean = inRun(scr)

    private fun inRun(s: Scr) = s == Scr.PLAY || s == Scr.PAUSE

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

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // шторка уведомлений, звонок, мультиоконность — ставим игру на паузу
        if (!hasWindowFocus && scr == Scr.PLAY && !world.dead) pauseGame()
    }

    /** true — нажатие «Назад» обработано игрой. */
    fun onBack(): Boolean {
        return when (scr) {
            Scr.PLAY -> { if (!world.dead) pauseGame(); true }
            Scr.PAUSE -> { resumeFromPause(); true }
            Scr.SKINS -> { go(Scr.MENU); true }
            Scr.OVER -> { toMenu(); true }
            Scr.SETTINGS -> { go(Scr.MENU); true }
            Scr.HOWTO -> { closeHowto(); true }
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
    // Раскладка
    // ======================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        W = w.toFloat()
        H = h.toFloat()
        U = min(W, H * 0.56f)
        world.unit = U
        world.cx = W / 2f
        world.cy = H * 0.47f
        painter.resize(W, H)
        layoutUi()
        syncPrefs()
    }

    private fun mkShader(r: RectF, c1: Int, c2: Int): Shader =
        LinearGradient(r.left, r.top, r.right, r.bottom, c1, c2, Shader.TileMode.CLAMP)

    private fun fitBtn(b: Btn) {
        if (b.label.isNotEmpty()) b.labelSize = g.fit(b.label, b.r.width() * 0.82f, b.r.height() * 0.42f)
    }

    private fun wrapLines(text: String, maxW: Float, size: Float): List<String> {
        val out = ArrayList<String>()
        var line = StringBuilder()
        for (word in text.split(' ')) {
            val test = if (line.isEmpty()) word else "$line $word"
            if (g.measure(test, size) <= maxW || line.isEmpty()) {
                line = StringBuilder(test)
            } else {
                out.add(line.toString())
                line = StringBuilder(word)
            }
        }
        if (line.isNotEmpty()) out.add(line.toString())
        return out
    }

    private fun layoutUi() {
        val u = U
        val m = u * 0.06f
        val gap = u * 0.03f
        statSize = u * 0.042f

        // ---- меню ----
        val belowY = world.cy + u * 0.50f
        val rem = H - belowY
        val bh = min(u * 0.15f, rem * 0.30f)
        val pw = min(W - 2f * m, u * 0.64f)
        val py0 = belowY + rem * 0.08f
        menuPlay.r.set(W / 2f - pw / 2f, py0, W / 2f + pw / 2f, py0 + bh)
        menuPlay.shader = mkShader(menuPlay.r, Pal.accents[0], Pal.blueEnd)
        val rowH = min(u * 0.12f, rem * 0.22f)
        val rowY = menuPlay.r.bottom + rem * 0.06f
        val rowW = min(W - 2f * m, u * 0.92f)
        val bw = (rowW - 2f * gap) / 3f
        var x = W / 2f - rowW / 2f
        menuSkins.r.set(x, rowY, x + bw, rowY + rowH); x += bw + gap
        menuGift.r.set(x, rowY, x + bw, rowY + rowH); x += bw + gap
        menuVideo.r.set(x, rowY, x + bw, rowY + rowH)
        val gr = u * 0.055f
        menuGear.r.set(m * 0.5f, H * 0.05f - gr * 1.4f, m * 0.5f + gr * 2.8f, H * 0.05f + gr * 1.4f)

        // ---- конец игры ----
        val pm = u * 0.07f
        val pwid = min(W - 2f * pm, u * 0.9f)
        panel.set(W / 2f - pwid / 2f, H * 0.12f, W / 2f + pwid / 2f, H * 0.88f)
        val ph = panel.height()
        val bb = min(pwid * 0.17f, ph * 0.105f)
        val pad = ph * 0.045f
        val bg = ph * 0.022f
        overMenu.r.set(panel.left + pad, panel.bottom - pad - bb, panel.right - pad, panel.bottom - pad)
        overAgain.r.set(panel.left + pad, overMenu.r.top - bg - bb, panel.right - pad, overMenu.r.top - bg)
        overRevive.r.set(panel.left + pad, overAgain.r.top - bg - bb, panel.right - pad, overAgain.r.top - bg)
        overAgain.shader = mkShader(overAgain.r, Pal.accents[0], Pal.blueEnd)
        overRevive.shader = mkShader(overRevive.r, Pal.gold, Pal.goldEnd)
        val chipW = u * 0.2f
        val chipH = u * 0.085f
        val sy = panel.top + ph * 0.46f
        overDouble.r.set(panel.right - pad - chipW, sy - chipH * 0.68f, panel.right - pad, sy + chipH * 0.32f)
        overDouble.shader = mkShader(overDouble.r, Pal.gold, Pal.goldEnd)

        // ---- пауза ----
        pausePanel.set(W / 2f - pwid / 2f, H * 0.27f, W / 2f + pwid / 2f, H * 0.73f)
        val ph2 = pausePanel.height()
        val bb2 = min(pausePanel.width() * 0.17f, ph2 * 0.18f)
        val pad2 = ph2 * 0.06f
        val bg2 = ph2 * 0.04f
        pauseMenu.r.set(pausePanel.left + pad2, pausePanel.bottom - pad2 - bb2, pausePanel.right - pad2, pausePanel.bottom - pad2)
        pauseRestart.r.set(pausePanel.left + pad2, pauseMenu.r.top - bg2 - bb2, pausePanel.right - pad2, pauseMenu.r.top - bg2)
        pauseResume.r.set(pausePanel.left + pad2, pauseRestart.r.top - bg2 - bb2, pausePanel.right - pad2, pauseRestart.r.top - bg2)
        pauseResume.shader = mkShader(pauseResume.r, Pal.accents[0], Pal.blueEnd)

        // ---- настройки ----
        setPanel.set(W / 2f - pwid / 2f, H * 0.14f, W / 2f + pwid / 2f, H * 0.86f)
        val sph = setPanel.height()
        val sp = sph * 0.05f
        val rh = min(pwid * 0.14f, sph * 0.095f)
        var ry = setPanel.top + sph * 0.17f
        for (b in arrayOf(setSound, setMusic, setVib)) {
            b.r.set(setPanel.left + sp, ry, setPanel.right - sp, ry + rh)
            ry += rh + sph * 0.025f
        }
        val sb = min(pwid * 0.15f, sph * 0.09f)
        setBack.r.set(setPanel.left + sp, setPanel.bottom - sp - sb, setPanel.right - sp, setPanel.bottom - sp)
        setHow.r.set(setPanel.left + sp, setBack.r.top - sph * 0.025f - sb, setPanel.right - sp, setBack.r.top - sph * 0.025f)
        setBack.shader = mkShader(setBack.r, Pal.accents[0], Pal.blueEnd)

        // ---- как играть ----
        howPanel.set(W / 2f - pwid / 2f, H * 0.09f, W / 2f + pwid / 2f, H * 0.91f)
        val hph = howPanel.height()
        val hp = hph * 0.045f
        val hb = min(pwid * 0.15f, hph * 0.085f)
        howOk.r.set(howPanel.left + hp, howPanel.bottom - hp - hb, howPanel.right - hp, howPanel.bottom - hp)
        howOk.shader = mkShader(howOk.r, Pal.accents[0], Pal.blueEnd)
        howRowTop = howPanel.top + hph * 0.16f
        val rowsBottom = howOk.r.top - hph * 0.03f
        howRowH = (rowsBottom - howRowTop) / 4f
        howIconR = min(howRowH * 0.30f, u * 0.055f)
        howTextX = howPanel.left + hp + howIconR * 2.7f
        val textW = howPanel.right - hp - howTextX
        var size = u * 0.045f
        var lines: Array<List<String>> = Array(4) { emptyList<String>() }
        while (true) {
            lines = Array(4) { wrapLines(howTexts[it], textW, size) }
            var worst = 0
            for (l in lines) worst = max(worst, l.size)
            if (worst * size * 1.28f <= howRowH * 0.92f || size < u * 0.026f) break
            size *= 0.93f
        }
        howSize = size
        howLines = lines

        // ---- кометы ----
        val listW = min(W - 2f * m, u * 0.95f)
        val cardW = (listW - gap) / 2f
        val top = H * 0.2f
        val areaH = H * 0.66f
        val cardH = (areaH - 3f * gap) / 4f
        for (i in skinCards.indices) {
            val col = i % 2
            val row = i / 2
            val l = W / 2f - listW / 2f + col * (cardW + gap)
            val t = top + row * (cardH + gap)
            skinCards[i].r.set(l, t, l + cardW, t + cardH)
        }
        val backH = min(u * 0.13f, H * 0.07f)
        skinBack.r.set(W / 2f - u * 0.22f, H * 0.89f, W / 2f + u * 0.22f, H * 0.89f + backH)
        skinBack.shader = mkShader(skinBack.r, Pal.accents[0], Pal.blueEnd)

        // ---- HUD ----
        val pr = u * 0.05f
        val pcx = W - m - pr
        val pcy = H * 0.062f
        hudPause.r.set(pcx - pr * 1.4f, pcy - pr * 1.4f, pcx + pr * 1.4f, pcy + pr * 1.4f)

        // размеры подписей считаем один раз
        for (b in arrayOf(menuPlay, menuSkins, overAgain, overMenu, pauseResume, pauseRestart, pauseMenu, setHow, setBack, howOk, skinBack)) fitBtn(b)
        giftSize = min(g.fit(sGift, menuGift.r.width() * 0.5f, rowH * 0.34f), g.fit(sGiftWait, menuGift.r.width() * 0.5f, rowH * 0.34f))
        val rw = overRevive.r.width() * 0.6f
        val rhh = overRevive.r.height() * 0.4f
        reviveSizes[0] = g.fit(sContinue, rw, rhh)
        reviveSizes[1] = g.fit(sLoading, rw, rhh)
        reviveSizes[2] = g.fit(sUsed, rw, rhh)

        if (BuildConfig.DEBUG) post { logButtons() }
    }

    /** Только debug: координаты кнопок на экране — по ним CI-скрипт делает тапы. */
    private fun logButtons() {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        fun l(name: String, b: Btn) {
            android.util.Log.d("OrbitaUI", "btn:$name ${(loc[0] + b.r.centerX()).toInt()} ${(loc[1] + b.r.centerY()).toInt()}")
        }
        l("PLAY", menuPlay); l("SKINS", menuSkins); l("GIFT", menuGift); l("VIDEO", menuVideo); l("GEAR", menuGear)
        l("PAUSE", hudPause); l("RESUME", pauseResume); l("AGAIN", overAgain); l("MENU", overMenu)
        l("REVIVE", overRevive); l("DOUBLE", overDouble); l("HOWTO", setHow); l("GOTIT", howOk); l("BACK", skinBack)
        l("SBACK", setBack)
    }

    private fun syncPrefs() {
        bestStr = context.getString(R.string.best_fmt, prefs.best)
        gemsStr = prefs.gems.toString()
        soundOn = prefs.sound
        musicOn = prefs.music
        vibOn = prefs.vibration
        giftAvail = prefs.dailyAvailable()
        skinIdx = prefs.skin.coerceIn(0, Skins.all.size - 1)
        scene.skin = Skins.all[skinIdx]
        if (statSize > 0f) gemsW = g.measure(gemsStr, statSize)
    }

    // ======================================================================
    // Переходы и действия
    // ======================================================================

    private fun go(s: Scr) {
        if (BuildConfig.DEBUG) android.util.Log.d("OrbitaUI", "state:$s")
        val was = inRun(scr)
        scr = s
        scrAge = 0f
        pressedBtn?.pressed = false
        pressedBtn = null
        if (was != inRun(s)) host.runState(inRun(s))
        syncPrefs()
    }

    private fun toast(s: String) {
        toastS = s
        toastT = 2.2f
    }

    private fun clearFx() {
        parts.clear()
        waves.clear()
        ftexts.clear()
        painter.resetTrail()
    }

    private fun toMenu() {
        world.reset()
        clearFx()
        countdown = 0f
        lastScore = -1
        frozen = false
        host.musicLevel(1)
        go(Scr.MENU)
    }

    private fun requestStart() {
        if (!prefs.howtoSeen) {
            prefs.howtoSeen = true
            pendingStart = true
            howBack = Scr.MENU
            go(Scr.HOWTO)
        } else {
            startRun()
        }
    }

    private fun startRun() {
        world.reset()
        clearFx()
        deadT = 0f
        runT = 0f
        revived = false
        doubled = false
        gemsBanked = 0
        countdown = 0f
        lastScore = -1
        lastRunGems = -1
        lastMult = 0
        frozen = false
        prefs.runs = prefs.runs + 1
        host.musicLevel(1)
        go(Scr.PLAY)
        sfx.play(R.raw.sfx_switch, 0.7f, 1.2f)
    }

    private fun closeHowto() {
        if (pendingStart) {
            pendingStart = false
            startRun()
        } else {
            go(howBack)
        }
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
        if (newBest && sc > 0) {
            parts.emit(W / 2f, H * 0.3f, Pal.gold, 40, g.dp(300f), g.dp(4f), 1.2f, 1.2f, -PI_F / 2f, PI_F)
            sfx.play(R.raw.sfx_levelup, 0.7f)
        }
    }

    private fun doRevive() {
        world.revive()
        revived = true
        deadT = 0f
        painter.resetTrail()
        parts.clear()
        go(Scr.PLAY)
        countdown = 2.4f
        sfx.play(R.raw.sfx_revive)
    }

    private fun reviveRequest() {
        if (revived) return
        if (!host.rewardedReady()) {
            toast(context.getString(R.string.toast_video_loading))
            return
        }
        host.requestRewarded { ok ->
            if (ok) doRevive() else toast(context.getString(R.string.toast_no_reward))
        }
    }

    private fun doubleRequest() {
        if (doubled || overGems <= 0) return
        if (!host.rewardedReady()) {
            toast(context.getString(R.string.toast_video_loading))
            return
        }
        host.requestRewarded { ok ->
            if (ok) {
                prefs.gems = prefs.gems + overGems
                doubled = true
                sfx.play(R.raw.sfx_crystal)
                toast(context.getString(R.string.toast_doubled))
                syncPrefs()
            } else {
                toast(context.getString(R.string.toast_no_reward))
            }
        }
    }

    private fun bonusVideo() {
        if (!host.rewardedReady()) {
            toast(context.getString(R.string.toast_video_loading))
            return
        }
        host.requestRewarded { ok ->
            if (ok) {
                prefs.gems = prefs.gems + BONUS_GEMS
                sfx.play(R.raw.sfx_crystal)
                toast(context.getString(R.string.toast_gems_fmt, BONUS_GEMS))
                syncPrefs()
            } else {
                toast(context.getString(R.string.toast_no_reward))
            }
        }
    }

    private fun claimGift() {
        val reward = prefs.claimDaily()
        if (reward < 0) return
        syncPrefs()
        val r = menuGift.r
        parts.emit(r.centerX(), r.centerY(), Pal.gold, 34, g.dp(280f), g.dp(4f), 0.95f)
        waves.emit(r.centerX(), r.centerY(), U * 0.03f, U * 0.35f, 0.6f, g.dp(3f), Pal.gold)
        sfx.play(R.raw.sfx_levelup)
        toast(context.getString(R.string.toast_gift_fmt, reward, prefs.streak))
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
            toast(context.getString(R.string.toast_unlocked_fmt, context.getString(s.nameRes)))
        } else {
            toast(context.getString(R.string.toast_not_enough))
        }
        syncPrefs()
    }

    private fun act(id: Int) {
        sfx.play(R.raw.sfx_click, 0.8f)
        when (id) {
            B_PLAY -> requestStart()
            B_AGAIN, B_RESTART -> startRun()
            B_SKINS -> go(Scr.SKINS)
            B_GIFT -> claimGift()
            B_VIDEO -> bonusVideo()
            B_GEAR -> go(Scr.SETTINGS)
            B_SOUND -> prefs.sound = !prefs.sound
            B_MUSIC -> {
                prefs.music = !prefs.music
                host.musicChanged(prefs.music)
            }
            B_VIB -> prefs.vibration = !prefs.vibration
            B_HOWTO -> {
                howBack = Scr.SETTINGS
                pendingStart = false
                go(Scr.HOWTO)
            }
            B_GOTIT -> closeHowto()
            B_REVIVE -> reviveRequest()
            B_DOUBLE -> doubleRequest()
            B_MENU -> toMenu()
            B_RESUME -> resumeFromPause()
            B_BACK -> go(Scr.MENU)
            B_PAUSE -> pauseGame()
            else -> if (id >= B_SKIN0 && id < B_SKIN0 + Skins.all.size) skinTap(id - B_SKIN0)
        }
        syncPrefs()
    }

    // ======================================================================
    // События мира
    // ======================================================================

    private fun haptic(strong: Boolean) {
        if (vibOn) host.haptic(strong)
    }

    private fun floatText(s: String, x: Float, y: Float, color: Int, size: Float) {
        if (ftexts.size > 10) ftexts.removeAt(0)
        ftexts.add(FText(s, x, y, color, size))
    }

    private fun headNow(): Int = Skins.headColor(scene.skin, clock)

    override fun onSwitch() {
        sfx.play(R.raw.sfx_switch, 0.55f)
        parts.emit(world.px(), world.py(), headNow(), 7, g.dp(110f), g.dp(3.5f), 0.35f)
    }

    override fun onCrystal(x: Float, y: Float, gain: Int, mult: Int) {
        parts.emit(x, y, Pal.gold, 16, g.dp(190f), g.dp(4f), 0.6f)
        waves.emit(x, y, world.pRad, world.pRad * 3.2f, 0.32f, g.dp(2f), Pal.gold)
        floatText("+$gain", x, y - g.dp(18f), Pal.gold, U * 0.05f)
        if (mult > 1 && world.combo % 3 == 0) floatText("x$mult", x, y - g.dp(58f), Pal.white, U * 0.065f)
        sfx.play(R.raw.sfx_crystal, 0.9f, 1f + 0.05f * mult)
        haptic(false)
        corePulse = 0.6f
        scorePop = 1f
    }

    override fun onPower(type: Int, x: Float, y: Float) {
        val col = Pal.power[type]
        parts.emit(x, y, col, 22, g.dp(210f), g.dp(4f), 0.7f)
        waves.emit(x, y, world.pRad, world.unit * 0.22f, 0.5f, g.dp(3f), col)
        floatText(sPowerNames[type], x, y - g.dp(24f), col, U * 0.052f)
        sfx.play(R.raw.sfx_shield)
        haptic(false)
    }

    override fun onGraze(x: Float, y: Float, points: Int) {
        parts.emit(x, y, Pal.white, 8, g.dp(150f), g.dp(2.6f), 0.4f)
        floatText("$sGraze +$points", world.cx, world.cy - U * 0.56f, Pal.hazardHot, U * 0.05f)
        sfx.play(R.raw.sfx_graze, 0.8f)
        scorePop = 0.7f
    }

    override fun onShieldBreak(x: Float, y: Float) {
        val col = Pal.power[World.P_SHIELD]
        parts.emit(x, y, col, 36, g.dp(320f), g.dp(4.5f), 0.7f)
        parts.emit(x, y, Pal.white, 12, g.dp(220f), g.dp(3f), 0.5f)
        waves.emit(x, y, world.pRad, world.unit * 0.3f, 0.5f, g.dp(4f), col)
        shake = max(shake, 0.55f)
        flash = max(flash, 0.25f)
        flashColor = Pal.white
        sfx.play(R.raw.sfx_hit, 0.8f, 1.2f)
        haptic(true)
    }

    override fun onDeath(x: Float, y: Float) {
        val s = scene.skin
        parts.emit(x, y, Skins.headColor(s, clock), 60, g.dp(420f), g.dp(5f), 1.0f)
        parts.emit(x, y, Skins.trailColor(s, clock), 40, g.dp(260f), g.dp(4f), 1.1f)
        parts.emit(x, y, Pal.white, 18, g.dp(300f), g.dp(3f), 0.7f)
        waves.emit(x, y, world.pRad, world.unit * 0.5f, 0.75f, g.dp(5f), Pal.hazard)
        shake = 1f
        flash = 0.7f
        flashColor = Pal.hazardHot
        deadT = 0f
        sfx.play(R.raw.sfx_hit)
        haptic(true)
    }

    override fun onLevel(level: Int) {
        levelPopT = 1.9f
        levelPopStr = context.getString(R.string.level_fmt, level)
        flash = max(flash, 0.12f)
        flashColor = Pal.white
        waves.emit(world.cx, world.cy, world.coreR, world.unit * 0.62f, 0.9f, g.dp(4f), accent)
        sfx.play(R.raw.sfx_levelup, 0.8f)
        host.musicLevel(level)
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
        scene.clock = clock

        val target = Pal.accents[(world.level - 1) % Pal.accents.size]
        accent = lerpColor(accent, target, min(1f, dt * 1.2f))
        scene.accent = accent
        scene.corePulse = corePulse
        scene.inPlay = inRun(scr)
        scene.showPlayer = scr != Scr.OVER

        shake = max(0f, shake - dt * 2.2f)
        flash = max(0f, flash - dt * 2.5f)
        corePulse = max(0f, corePulse - dt * 2.2f)
        scorePop = max(0f, scorePop - dt * 4f)
        if (toastT > 0f) toastT -= dt
        if (levelPopT > 0f) levelPopT -= dt
        for (i in 0 until ftexts.size) ftexts[i].age += dt
        ftexts.removeAll { it.age > 1.0f }

        warmT -= dt
        if (warmT <= 0f) {
            warmT = 4f
            host.warmUp()
            if (scr == Scr.MENU) giftAvail = prefs.dailyAvailable()
        }

        when (scr) {
            Scr.SPLASH -> stepSplash(dt)
            Scr.MENU, Scr.SKINS, Scr.SETTINGS, Scr.HOWTO -> world.update(dt, false)
            Scr.PLAY -> stepPlay(dt)
            Scr.PAUSE -> {}
            Scr.OVER -> overAnim += dt
        }
        parts.update(dt)
        waves.update(dt)
        if (scr != Scr.PAUSE && scr != Scr.OVER && !world.dead && !frozen) {
            painter.recordTrail(dt)
            painter.emitSkinParticles(scene, dt)
        }
    }

    private fun stepSplash(dt: Float) {
        world.update(dt, false)
        if (splashAdStarted) {
            splashWait += dt
            if (splashWait > 4f) toMenu()
            return
        }
        val minDone = scrAge >= 1.5f
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
        if (freezeIn >= 0f) {
            freezeIn -= dt
            if (freezeIn < 0f) frozen = true
        }
        if (frozen) return
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
        if (world.gems != lastRunGems) {
            lastRunGems = world.gems
            runGemsStr = world.gems.toString()
        }
        val mu = if (world.combo > 0) world.mult() else 0
        if (mu != lastMult) {
            lastMult = mu
            comboStr = "x$mu"
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
        Scr.SETTINGS -> setBtns
        Scr.HOWTO -> howBtns
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
                if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedBtn?.pressed = false
                pressedBtn = null
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun onDown(x: Float, y: Float) {
        if (scr == Scr.PLAY) {
            if (hudPause.r.contains(x, y)) {
                hudPause.pressed = true
                pressedBtn = hudPause
                return
            }
            if (countdown <= 0f && !world.dead && !frozen) {
                world.moveLane(if (x < W / 2f) -1 else 1)
            }
            return
        }
        if (scr == Scr.OVER && scrAge < 0.5f) return
        val list = activeButtons() ?: return
        val b = hit(list, x, y)
        if (b != null) {
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
        painter.drawBackground(canvas, scene)

        canvas.save()
        if (shake > 0f) {
            val s = shake * shake * g.dp(14f)
            canvas.translate((rnd.nextFloat() - 0.5f) * 2f * s, (rnd.nextFloat() - 0.5f) * 2f * s)
        }
        painter.drawScene(canvas, scene)
        drawFloatTexts(canvas)
        canvas.restore()

        painter.drawOverlay(canvas)

        val a = easeOut(scrAge / 0.3f)
        when (scr) {
            Scr.SPLASH -> drawSplash(canvas)
            Scr.MENU -> drawMenu(canvas, a)
            Scr.PLAY -> drawHud(canvas)
            Scr.PAUSE -> { drawHud(canvas); drawPause(canvas, a) }
            Scr.OVER -> drawOver(canvas, a)
            Scr.SKINS -> drawSkins(canvas, a)
            Scr.SETTINGS -> drawSettings(canvas, a)
            Scr.HOWTO -> drawHowto(canvas, a)
        }

        if (flash > 0f) {
            p.style = Paint.Style.FILL
            p.shader = null
            p.color = withAlpha(flashColor, flash * 0.6f)
            canvas.drawRect(0f, 0f, W, H, p)
        }
        drawToast(canvas)
    }

    private fun drawFloatTexts(c: Canvas) {
        for (i in 0 until ftexts.size) {
            val t = ftexts[i]
            val k = t.age / 1.0f
            val a = 1f - k * k
            g.text(c, t.s, t.x, t.y - g.dp(46f) * easeOut(k), t.size, t.color, Paint.Align.CENTER, t.color, a)
        }
    }

    // ---------------------------------------------------------------- HUD

    private fun drawPill(c: Canvas, x: Float, y: Float, type: Int, frac: Float) {
        val w = U * 0.23f
        val h = U * 0.055f
        val col = Pal.power[type]
        rr.set(x, y, x + w, y + h)
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = withAlpha(Pal.panel, 0.8f)
        c.drawRoundRect(rr, h / 2f, h / 2f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = g.dp(1.3f)
        p.color = withAlpha(col, 0.85f)
        c.drawRoundRect(rr, h / 2f, h / 2f, p)
        g.powerIcon(c, type, x + h * 0.55f, y + h / 2f, h * 0.3f, 1f, clock * 2f)
        val bx = x + h * 1.05f
        val bw = w - h * 1.3f
        rr.set(bx, y + h * 0.38f, bx + bw, y + h * 0.62f)
        p.style = Paint.Style.FILL
        p.color = withAlpha(Pal.white, 0.14f)
        c.drawRoundRect(rr, h * 0.12f, h * 0.12f, p)
        rr.set(bx, y + h * 0.38f, bx + bw * clamp01(frac), y + h * 0.62f)
        p.color = col
        c.drawRoundRect(rr, h * 0.12f, h * 0.12f, p)
    }

    private fun drawHud(c: Canvas) {
        val m = U * 0.06f
        g.text(c, scoreStr, W / 2f, H * 0.085f, U * 0.115f * (1f + 0.16f * scorePop), Pal.white, Paint.Align.CENTER, accent)

        val gx = m + g.dp(8f)
        val gy = H * 0.062f
        g.glow(c, gx, gy, g.dp(18f), Pal.gold, 0.5f)
        g.gem(c, gx, gy, g.dp(8.5f), Pal.gold)
        g.text(c, runGemsStr, gx + g.dp(16f), gy + U * 0.02f, U * 0.055f, Pal.text, Paint.Align.LEFT)

        var py = H * 0.1f
        val step = U * 0.065f
        if (world.shield) { drawPill(c, m, py, World.P_SHIELD, 1f); py += step }
        if (world.magnetT > 0f) { drawPill(c, m, py, World.P_MAGNET, world.magnetT / World.MAGNET_TIME); py += step }
        if (world.slowT > 0f) { drawPill(c, m, py, World.P_SLOW, world.slowT / World.SLOW_TIME) }

        if (world.combo > 0) {
            g.text(c, comboStr, W / 2f, H * 0.128f, U * 0.055f, Pal.gold, Paint.Align.CENTER, Pal.gold)
            val bw = U * 0.22f
            val by = H * 0.139f
            rr.set(W / 2f - bw / 2f, by, W / 2f + bw / 2f, by + g.dp(4f))
            p.style = Paint.Style.FILL
            p.shader = null
            p.color = withAlpha(Pal.white, 0.15f)
            c.drawRoundRect(rr, g.dp(2f), g.dp(2f), p)
            rr.set(W / 2f - bw / 2f, by, W / 2f - bw / 2f + bw * clamp01(world.comboT / 5f), by + g.dp(4f))
            p.color = Pal.gold
            c.drawRoundRect(rr, g.dp(2f), g.dp(2f), p)
        }

        // пауза
        val hb = hudPause.r
        val cx = hb.centerX()
        val cy = hb.centerY()
        val r = U * 0.05f * (if (hudPause.pressed) 0.92f else 1f)
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = withAlpha(Pal.panel, 0.75f)
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = g.dp(1.5f)
        p.color = withAlpha(accent, 0.7f)
        c.drawCircle(cx, cy, r, p)
        g.pauseIcon(c, cx, cy, r * 0.42f, Pal.text)

        if (levelPopT > 0f) {
            val tt = 1.9f - levelPopT
            val a = min(1f, min(tt / 0.25f, levelPopT / 0.5f))
            g.text(c, levelPopStr, W / 2f, H * 0.175f, U * 0.06f, Pal.white, Paint.Align.CENTER, accent, a)
        }

        if (prefs.runs <= 3 && runT < 8f && scr == Scr.PLAY && countdown <= 0f) {
            val a = min(1f, (8f - runT) / 1f) * 0.85f
            val y = H * 0.9f
            val s = U * 0.028f
            g.chevron(c, W * 0.12f, y, s, -1, withAlpha(Pal.text, a), g.dp(3f))
            g.text(c, sInward, W * 0.2f, y + U * 0.02f, U * 0.05f, Pal.text, Paint.Align.LEFT, 0, a)
            g.chevron(c, W * 0.88f, y, s, 1, withAlpha(Pal.text, a), g.dp(3f))
            g.text(c, sOutward, W * 0.8f, y + U * 0.02f, U * 0.05f, Pal.text, Paint.Align.RIGHT, 0, a)
            p.style = Paint.Style.STROKE
            p.strokeWidth = g.dp(1f)
            p.color = withAlpha(Pal.text, 0.12f * a)
            c.drawLine(W / 2f, H * 0.84f, W / 2f, H * 0.96f, p)
        }

        if (scr == Scr.PLAY && countdown > 0f) {
            val n = ceil(countdown).toInt()
            val frac = countdown - (n - 1)
            val sc = 0.8f + 0.4f * frac
            g.text(c, n.toString(), world.cx, world.cy + world.unit * 0.62f + U * 0.14f, U * 0.26f * sc, Pal.white, Paint.Align.CENTER, accent, frac.coerceIn(0.55f, 1f))
        }
    }

    // ---------------------------------------------------------------- кнопки и панели

    private fun drawBtn(c: Canvas, b: Btn, kind: Int, a: Float, label: String = b.label, size: Float = b.labelSize, tint: Int = 0) {
        val r = b.r
        val rad = r.height() * 0.5f
        val al = a
        val gc = if (tint != 0) tint else when (kind) {
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
            p.color = android.graphics.Color.WHITE
            p.alpha = (255f * al).toInt()
        }
        c.drawRoundRect(r, rad, rad, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = g.dp(1.6f)
        p.color = withAlpha(gc, 0.9f * al)
        c.drawRoundRect(r, rad, rad, p)
        if (label.isNotEmpty()) {
            val tc = if (kind == K_SEC) Pal.text else Pal.ink
            g.text(c, label, r.centerX(), r.centerY() + size * 0.36f, size, tc, Paint.Align.CENTER, 0, al)
        }
        c.restore()
    }

    private fun drawPanel(c: Canvas, r: RectF, a: Float) {
        val rad = U * 0.05f
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = g.dp(12f)
        p.color = withAlpha(accent, 0.07f * a)
        c.drawRoundRect(r, rad, rad, p)
        p.strokeWidth = g.dp(5f)
        p.color = withAlpha(accent, 0.14f * a)
        c.drawRoundRect(r, rad, rad, p)
        p.style = Paint.Style.FILL
        p.color = withAlpha(Pal.panel, 0.95f * a)
        c.drawRoundRect(r, rad, rad, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = g.dp(1.6f)
        p.color = withAlpha(accent, 0.85f * a)
        c.drawRoundRect(r, rad, rad, p)
    }

    private fun dim(c: Canvas, k: Float) {
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = withAlpha(Pal.dimBg, k)
        c.drawRect(0f, 0f, W, H, p)
    }

    private fun drawToast(c: Canvas) {
        if (toastT <= 0f) return
        val a = min(1f, toastT / 0.3f)
        val size = g.fit(toastS, W * 0.86f - U * 0.1f, U * 0.04f)
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

    // ---------------------------------------------------------------- экраны

    private fun drawSplash(c: Canvas) {
        val a = easeOut(scrAge / 0.9f)
        val ts = min(U * 0.18f, H * 0.1f) * (0.85f + 0.15f * a)
        g.text(c, sTitle, W / 2f, H * 0.2f, ts, Pal.white, Paint.Align.CENTER, accent, a)
        g.text(c, sTagline, W / 2f, H * 0.2f + ts * 0.5f, ts * 0.27f, Pal.dim, Paint.Align.CENTER, 0, a)
        val r = U * 0.035f
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
        val m = U * 0.06f
        val ts = min(U * 0.15f, H * 0.085f)
        val ty = H * 0.075f + ts * 0.35f
        g.text(c, sTitle, W / 2f, ty, ts, Pal.white, Paint.Align.CENTER, accent, a)
        g.text(c, sTagline, W / 2f, ty + ts * 0.5f, ts * 0.27f, Pal.dim, Paint.Align.CENTER, 0, a)

        // шестерёнка
        val gb = menuGear.r
        if (menuGear.pressed) {
            g.glow(c, gb.centerX(), gb.centerY(), U * 0.09f, accent, 0.4f * a)
        }
        g.gearIcon(c, gb.centerX(), gb.centerY(), U * 0.032f, withAlpha(Pal.text, 0.9f * a))

        val ly = H * 0.178f
        g.text(c, bestStr, m, ly, statSize, Pal.text, Paint.Align.LEFT, 0, a)
        g.text(c, gemsStr, W - m, ly, statSize, Pal.text, Paint.Align.RIGHT, 0, a)
        val gx = W - m - gemsW - g.dp(18f)
        g.glow(c, gx, ly - statSize * 0.34f, g.dp(18f), Pal.gold, 0.5f * a)
        g.gem(c, gx, ly - statSize * 0.34f, g.dp(8f), Pal.gold, 0f, a)

        // кнопка «Играть» слегка «дышит»
        val pulse = 1f + 0.015f * sin(clock * 3f)
        c.save()
        c.scale(pulse, pulse, menuPlay.r.centerX(), menuPlay.r.centerY())
        drawBtn(c, menuPlay, K_PRIMARY, a)
        c.restore()
        drawBtn(c, menuSkins, K_SEC, a)

        // подарок
        val gr = menuGift.r
        drawBtn(c, menuGift, K_SEC, a, "", 0f, if (giftAvail) Pal.gold else 0)
        run {
            val label = if (giftAvail) sGift else sGiftWait
            val iconW = gr.height() * 0.5f
            val tw = g.measure(label, giftSize)
            val total = iconW + gr.height() * 0.12f + tw
            val x0 = gr.centerX() - total / 2f
            val col = if (giftAvail) Pal.gold else Pal.dim
            if (giftAvail) g.glow(c, x0 + iconW / 2f, gr.centerY(), gr.height() * 0.6f, Pal.gold, (0.3f + 0.2f * sin(clock * 5f)) * a)
            g.giftIcon(c, x0 + iconW / 2f, gr.centerY() - gr.height() * 0.03f, gr.height() * 0.2f, col, a)
            g.text(c, label, x0 + iconW + gr.height() * 0.12f, gr.centerY() + giftSize * 0.36f, giftSize, if (giftAvail) Pal.gold else Pal.dim, Paint.Align.LEFT, 0, a)
        }

        // видео-бонус
        val ready = host.rewardedReady()
        val va = a * (if (ready) 1f else 0.55f)
        drawBtn(c, menuVideo, K_SEC, va, "", 0f)
        run {
            val r = menuVideo.r
            val size = r.height() * 0.40f
            val txt = "+$BONUS_GEMS"
            val tw = g.measure(txt, size)
            val total = r.height() * 0.5f + tw + r.height() * 0.5f
            var x = r.centerX() - total / 2f
            g.playIcon(c, x + r.height() * 0.2f, r.centerY(), r.height() * 0.2f, withAlpha(Pal.text, va))
            x += r.height() * 0.5f
            g.text(c, txt, x, r.centerY() + size * 0.36f, size, Pal.gold, Paint.Align.LEFT, 0, va)
            g.gem(c, x + tw + r.height() * 0.28f, r.centerY(), r.height() * 0.16f, Pal.gold, 0f, va)
        }
    }

    private fun drawOver(c: Canvas, a: Float) {
        dim(c, 0.72f * a)
        drawPanel(c, panel, a)
        val ph = panel.height()
        val cx = panel.centerX()
        val top = panel.top
        val pad = ph * 0.045f

        g.text(c, sGameOver, cx, top + ph * 0.095f, g.fit(sGameOver, panel.width() * 0.8f, U * 0.075f), Pal.white, Paint.Align.CENTER, Pal.hazard, a)
        g.text(c, sScore, cx, top + ph * 0.18f, U * 0.04f, Pal.dim, Paint.Align.CENTER, 0, a)
        val shown = (overScore * easeOut(overAnim / 0.9f)).toInt()
        g.text(c, shown.toString(), cx, top + ph * 0.30f, U * 0.17f, Pal.white, Paint.Align.CENTER, accent, a)

        if (newBest) {
            val pulse = 0.75f + 0.25f * sin(clock * 6f)
            g.text(c, sNewBest, cx, top + ph * 0.375f, U * 0.05f, Pal.gold, Paint.Align.CENTER, Pal.gold, a * pulse)
        } else {
            g.text(c, bestStr, cx, top + ph * 0.375f, U * 0.045f, Pal.dim, Paint.Align.CENTER, 0, a)
        }

        val sy = top + ph * 0.46f
        val fs = U * 0.05f
        val shownGems = if (doubled) overGems * 2 else overGems
        val gx = panel.left + pad + g.dp(26f)
        g.glow(c, gx, sy - fs * 0.34f, g.dp(20f), Pal.gold, 0.5f * a)
        g.gem(c, gx, sy - fs * 0.34f, g.dp(9f), Pal.gold, 0f, a)
        g.text(c, shownGems.toString(), gx + g.dp(20f), sy, fs, Pal.text, Paint.Align.LEFT, 0, a)
        g.text(c, context.getString(R.string.lvl_short_fmt, overLevel), cx + panel.width() * 0.02f, sy, fs, Pal.text, Paint.Align.CENTER, 0, a)

        // x2 за видео
        if (overGems > 0 && !doubled) {
            val ready = host.rewardedReady()
            val ca = a * (if (ready) 1f else 0.55f)
            drawBtn(c, overDouble, K_GOLD, ca, "", 0f)
            val r = overDouble.r
            g.playIcon(c, r.left + r.height() * 0.5f, r.centerY(), r.height() * 0.2f, withAlpha(Pal.ink, ca))
            val sz = r.height() * 0.46f
            g.text(c, "x2", r.left + r.height() * 0.85f, r.centerY() + sz * 0.36f, sz, Pal.ink, Paint.Align.LEFT, 0, ca)
        }

        // воскрешение
        val canRevive = !revived
        val ready = host.rewardedReady()
        drawBtn(c, overRevive, K_GOLD, a * (if (canRevive && !ready) 0.6f else 1f), "", 0f)
        run {
            val r = overRevive.r
            val idx = if (!canRevive) 2 else if (ready) 0 else 1
            val label = if (idx == 2) sUsed else if (idx == 0) sContinue else sLoading
            val size = reviveSizes[idx]
            val tw = g.measure(label, size)
            val total = r.height() * 0.6f + tw
            val x = r.centerX() - total / 2f
            val al = a * (if (canRevive && ready) 1f else 0.5f)
            if (canRevive) g.playIcon(c, x + r.height() * 0.15f, r.centerY(), r.height() * 0.2f, withAlpha(Pal.ink, al))
            g.text(c, label, x + r.height() * 0.6f, r.centerY() + size * 0.36f, size, Pal.ink, Paint.Align.LEFT, 0, al)
        }
        drawBtn(c, overAgain, K_PRIMARY, a)
        drawBtn(c, overMenu, K_SEC, a)
    }

    private fun drawPause(c: Canvas, a: Float) {
        dim(c, 0.6f * a)
        drawPanel(c, pausePanel, a)
        g.text(c, sPaused, pausePanel.centerX(), pausePanel.top + pausePanel.height() * 0.15f, U * 0.08f, Pal.white, Paint.Align.CENTER, accent, a)
        drawBtn(c, pauseResume, K_PRIMARY, a)
        drawBtn(c, pauseRestart, K_SEC, a)
        drawBtn(c, pauseMenu, K_SEC, a)
    }

    private fun drawToggle(c: Canvas, b: Btn, label: String, on: Boolean, a: Float) {
        drawBtn(c, b, K_SEC, a, "", 0f)
        val r = b.r
        val size = g.fit(label, r.width() * 0.55f, r.height() * 0.4f)
        g.text(c, label, r.left + r.height() * 0.5f, r.centerY() + size * 0.36f, size, Pal.text, Paint.Align.LEFT, 0, a)
        val sw = r.height() * 1.15f
        val sh = r.height() * 0.5f
        val sx = r.right - r.height() * 0.45f - sw
        rr.set(sx, r.centerY() - sh / 2f, sx + sw, r.centerY() + sh / 2f)
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = if (on) withAlpha(Pal.accents[0], 0.9f * a) else withAlpha(Pal.white, 0.16f * a)
        c.drawRoundRect(rr, sh / 2f, sh / 2f, p)
        val kx = if (on) sx + sw - sh / 2f else sx + sh / 2f
        p.color = withAlpha(if (on) Pal.ink else Pal.text, a)
        c.drawCircle(kx, r.centerY(), sh * 0.36f, p)
    }

    private fun drawSettings(c: Canvas, a: Float) {
        dim(c, 0.8f * a)
        drawPanel(c, setPanel, a)
        g.text(c, sSettings, setPanel.centerX(), setPanel.top + setPanel.height() * 0.1f, g.fit(sSettings, setPanel.width() * 0.8f, U * 0.07f), Pal.white, Paint.Align.CENTER, accent, a)
        drawToggle(c, setSound, sSound, soundOn, a)
        drawToggle(c, setMusic, sMusic, musicOn, a)
        drawToggle(c, setVib, sVib, vibOn, a)
        drawBtn(c, setHow, K_SEC, a)
        drawBtn(c, setBack, K_PRIMARY, a)
        g.text(c, sVersion, setPanel.centerX(), setHow.r.top - setPanel.height() * 0.03f, U * 0.032f, Pal.dim, Paint.Align.CENTER, 0, a)
    }

    private fun drawHowIcon(c: Canvas, i: Int, x: Float, y: Float, r: Float, a: Float) {
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = g.dp(1.4f)
        p.color = withAlpha(accent, 0.6f * a)
        c.drawCircle(x, y, r * 1.25f, p)
        when (i) {
            0 -> {
                g.chevron(c, x - r * 0.55f, y, r * 0.42f, -1, withAlpha(Pal.text, a), g.dp(2.6f))
                g.chevron(c, x + r * 0.55f, y, r * 0.42f, 1, withAlpha(Pal.text, a), g.dp(2.6f))
            }
            1 -> {
                g.glow(c, x, y, r * 1.6f, Pal.gold, 0.5f * a)
                g.gem(c, x, y, r * 0.7f, Pal.gold, 0f, a)
            }
            2 -> {
                oval.set(x - r * 0.8f, y - r * 0.8f, x + r * 0.8f, y + r * 0.8f)
                p.strokeCap = Paint.Cap.ROUND
                p.strokeWidth = r * 0.34f
                p.color = withAlpha(Pal.hazard, a)
                c.drawArc(oval, 200f, 120f, false, p)
                p.strokeWidth = r * 0.1f
                p.color = withAlpha(Pal.hazardHot, a)
                c.drawArc(oval, 205f, 110f, false, p)
            }
            else -> {
                g.powerIcon(c, World.P_SHIELD, x - r * 0.62f, y - r * 0.22f, r * 0.34f, a, 0f)
                g.powerIcon(c, World.P_MAGNET, x + r * 0.62f, y - r * 0.22f, r * 0.34f, a, 0f)
                g.powerIcon(c, World.P_SLOW, x, y + r * 0.5f, r * 0.32f, a, clock * 2f)
            }
        }
    }

    private fun drawHowto(c: Canvas, a: Float) {
        dim(c, 0.82f * a)
        drawPanel(c, howPanel, a)
        g.text(c, sHowto, howPanel.centerX(), howPanel.top + howPanel.height() * 0.095f, g.fit(sHowto, howPanel.width() * 0.8f, U * 0.07f), Pal.white, Paint.Align.CENTER, accent, a)
        for (i in 0 until 4) {
            val cy = howRowTop + howRowH * (i + 0.5f)
            drawHowIcon(c, i, howPanel.left + howPanel.height() * 0.045f + howIconR * 1.3f, cy, howIconR, a)
            val lines = howLines[i]
            val lh = howSize * 1.28f
            var y = cy - lh * (lines.size - 1) / 2f + howSize * 0.36f
            for (ln in lines) {
                g.text(c, ln, howTextX, y, howSize, Pal.text, Paint.Align.LEFT, 0, a)
                y += lh
            }
        }
        drawBtn(c, howOk, K_PRIMARY, a)
    }

    private fun drawSkins(c: Canvas, a: Float) {
        dim(c, 0.82f * a)
        g.text(c, sComets, W / 2f, H * 0.095f, U * 0.09f, Pal.white, Paint.Align.CENTER, accent, a)
        val fs = U * 0.05f
        val total = g.dp(30f) + gemsW * fs / max(statSize, 1f)
        val x0 = W / 2f - total / 2f
        g.glow(c, x0 + g.dp(10f), H * 0.157f - fs * 0.34f, g.dp(20f), Pal.gold, 0.5f * a)
        g.gem(c, x0 + g.dp(10f), H * 0.157f - fs * 0.34f, g.dp(9f), Pal.gold, 0f, a)
        g.text(c, gemsStr, x0 + g.dp(30f), H * 0.157f, fs, Pal.text, Paint.Align.LEFT, 0, a)
        for (i in skinCards.indices) drawSkinCard(c, i, a)
        drawBtn(c, skinBack, K_PRIMARY, a)
    }

    private fun drawSkinCard(c: Canvas, i: Int, a: Float) {
        val b = skinCards[i]
        val s = Skins.all[i]
        val r = b.r
        val owned = prefs.owns(i)
        val sel = skinIdx == i
        val head = Skins.headColor(s, clock + i * 0.15f)
        val trail = Skins.trailColor(s, clock + i * 0.15f)
        val rad = U * 0.04f
        val h = r.height()

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

        // превью: орбита с кометой слева
        val pcx = r.left + h * 0.55f
        val pcy = r.centerY()
        val orR = h * 0.27f
        val la = if (owned) 1f else 0.6f
        p.strokeWidth = g.dp(1.2f)
        p.color = withAlpha(Pal.text, 0.18f * a)
        c.drawCircle(pcx, pcy, orR, p)
        val ha = clock * 1.4f + i * 0.9f
        oval.set(pcx - orR, pcy - orR, pcx + orR, pcy + orR)
        p.strokeCap = Paint.Cap.ROUND
        val wide = if (s.style == SkinStyle.RIBBON) 1.6f else 1f
        for (k in 0 until 3) {
            p.strokeWidth = h * (0.06f - 0.014f * k) * wide
            p.color = withAlpha(trail, (0.22f + 0.2f * k) * a * la)
            c.drawArc(oval, (ha - 1.0f + 0.25f * k) * RAD2DEG, (1.0f - 0.25f * k) * RAD2DEG, false, p)
        }
        val hx = pcx + cos(ha) * orR
        val hy = pcy + sin(ha) * orR
        g.glow(c, hx, hy, h * 0.17f, head, 0.7f * a * la)
        p.style = Paint.Style.FILL
        p.color = withAlpha(head, a * la)
        c.drawCircle(hx, hy, h * 0.045f, p)
        p.color = withAlpha(Pal.white, a * la)
        c.drawCircle(hx, hy, h * 0.025f, p)
        if (s.style == SkinStyle.TWIN) {
            for (k in 0 until 2) {
                val ta = clock * 7f + k * PI_F
                p.color = withAlpha(Pal.white, a * la)
                c.drawCircle(hx + cos(ta) * h * 0.1f, hy + sin(ta) * h * 0.1f, h * 0.016f, p)
            }
        }

        val tx = r.left + h * 1.05f
        val tw = r.right - tx - h * 0.1f
        val nm = context.getString(s.nameRes)
        val ns = g.fit(nm, tw, h * 0.2f)
        g.text(c, nm, tx, r.top + h * 0.47f, ns, Pal.text, Paint.Align.LEFT, 0, a)

        val sy = r.top + h * 0.8f
        val ss = min(h * 0.14f, g.fit(sSelected, tw, h * 0.14f))
        if (sel) {
            g.text(c, sSelected, tx, sy, ss, head, Paint.Align.LEFT, 0, a)
        } else if (owned) {
            g.text(c, sSelect, tx, sy, ss, Pal.dim, Paint.Align.LEFT, 0, a)
        } else {
            val can = prefs.gems >= s.cost
            val col = if (can) Pal.gold else Pal.dim
            g.gem(c, tx + ss * 0.4f, sy - ss * 0.34f, ss * 0.5f, col, 0f, a)
            g.text(c, s.cost.toString(), tx + ss * 1.2f, sy, ss, col, Paint.Align.LEFT, 0, a)
        }
        c.restore()
    }

    // ======================================================================
    // Отладка (только debug-сборка): быстрые сцены для скриншотов
    // ======================================================================

    fun debugScene(name: String) {
        if (!BuildConfig.DEBUG) return
        pendingStart = false
        when (name) {
            "menu" -> toMenu()
            "skins" -> { prefs.gems = 700; toMenu(); go(Scr.SKINS) }
            "settings" -> { toMenu(); go(Scr.SETTINGS) }
            "howto" -> { toMenu(); howBack = Scr.MENU; go(Scr.HOWTO) }
            "gate", "ring", "meteors", "powers" -> {
                startRun()
                world.invuln = 999f
                world.time = if (name == "gate") 40f else if (name == "ring") 55f else 85f
                world.update(0.01f, true)
                when (name) {
                    "gate" -> world.forceSpawn(World.K_GATE)
                    "ring" -> { world.forceSpawn(World.K_RING); world.forceSpawn(World.K_BARRIER) }
                    "meteors" -> { world.forceSpawn(World.K_SHOWER); world.forceSpawn(World.K_BARRIER) }
                    else -> {
                        world.shield = true
                        world.magnetT = 5f
                        world.slowT = 4f
                        world.forcePower(World.P_SHIELD)
                        world.forceSpawn(World.K_BARRIER)
                    }
                }
                freezeIn = if (name == "meteors") 1.55f else 1.4f
            }
            "over" -> {
                startRun()
                world.score = 1234f
                world.gems = 9
                world.level = 4
                toOver()
            }
        }
    }
}
