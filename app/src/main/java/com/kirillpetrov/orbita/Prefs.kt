package com.kirillpetrov.orbita

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("orbita_prefs", Context.MODE_PRIVATE)

    var best: Int
        get() = sp.getInt("best", 0)
        set(v) { sp.edit().putInt("best", v).apply() }

    var gems: Int
        get() = sp.getInt("gems", 0)
        set(v) { sp.edit().putInt("gems", v).apply() }

    var skin: Int
        get() = sp.getInt("skin", 0)
        set(v) { sp.edit().putInt("skin", v).apply() }

    var owned: Int
        get() = sp.getInt("owned", 1)
        set(v) { sp.edit().putInt("owned", v).apply() }

    var sound: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) { sp.edit().putBoolean("sound", v).apply() }

    var music: Boolean
        get() = sp.getBoolean("music", true)
        set(v) { sp.edit().putBoolean("music", v).apply() }

    var launches: Int
        get() = sp.getInt("launches", 0)
        set(v) { sp.edit().putInt("launches", v).apply() }

    var runs: Int
        get() = sp.getInt("runs", 0)
        set(v) { sp.edit().putInt("runs", v).apply() }

    var vibration: Boolean
        get() = sp.getBoolean("vibration", true)
        set(v) { sp.edit().putBoolean("vibration", v).apply() }

    var howtoSeen: Boolean
        get() = sp.getBoolean("howto_seen", false)
        set(v) { sp.edit().putBoolean("howto_seen", v).apply() }

    /** Высота баннера в пикселях с прошлого запуска — резервируем место заранее. */
    var bannerH: Int
        get() = sp.getInt("banner_h", 0)
        set(v) { sp.edit().putInt("banner_h", v).apply() }

    private var lastDaily: Int
        get() = sp.getInt("last_daily", 0)
        set(v) { sp.edit().putInt("last_daily", v).apply() }

    var streak: Int
        get() = sp.getInt("streak", 0)
        private set(v) { sp.edit().putInt("streak", v).apply() }

    private fun today(): Int {
        val now = System.currentTimeMillis()
        return ((now + java.util.TimeZone.getDefault().getOffset(now)) / 86_400_000L).toInt()
    }

    fun dailyAvailable(): Boolean = lastDaily != today()

    /** Забирает ежедневный подарок. Возвращает число кристаллов или -1, если уже забрано. */
    fun claimDaily(): Int {
        val t = today()
        if (lastDaily == t) return -1
        streak = if (lastDaily == t - 1) minOf(streak + 1, 7) else 1
        lastDaily = t
        val reward = 20 + 10 * (streak - 1)
        gems = gems + reward
        return reward
    }

    fun owns(i: Int): Boolean = i == 0 || ((owned shr i) and 1) == 1

    fun own(i: Int) {
        owned = owned or (1 shl i)
    }
}

class Sfx(private val context: Context, private val prefs: Prefs) {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val ids = HashMap<Int, Int>()
    private var music: MediaPlayer? = null
    private var musicReady = false
    private var wantMusic = false
    private var musicTried = false
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        val all = intArrayOf(
            R.raw.sfx_switch, R.raw.sfx_crystal, R.raw.sfx_shield, R.raw.sfx_hit,
            R.raw.sfx_over, R.raw.sfx_click, R.raw.sfx_levelup, R.raw.sfx_revive, R.raw.sfx_graze
        )
        for (r in all) ids[r] = pool.load(context, r, 1)
    }

    fun play(res: Int, vol: Float = 1f, rate: Float = 1f) {
        if (!prefs.sound) return
        val id = ids[res] ?: return
        pool.play(id, vol, vol, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    /** Музыка готовится асинхронно (без фриза UI). Если уже играет чужая музыка — не вмешиваемся. */
    fun startMusic() {
        if (!prefs.music) return
        wantMusic = true
        try {
            val m = music
            if (m == null) {
                if (!musicTried && audio.isMusicActive) return
                musicTried = true
                val player = MediaPlayer()
                context.resources.openRawResourceFd(R.raw.music).use {
                    player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                player.isLooping = true
                player.setVolume(0.38f, 0.38f)
                player.setOnPreparedListener {
                    musicReady = true
                    if (wantMusic && prefs.music) it.start()
                }
                player.prepareAsync()
                music = player
            } else if (musicReady && !m.isPlaying) {
                m.start()
            }
        } catch (e: Exception) {
            music = null
            musicReady = false
        }
    }

    fun pauseMusic() {
        wantMusic = false
        try {
            val m = music
            if (m != null && musicReady && m.isPlaying) m.pause()
        } catch (e: Exception) {
            // игнорируем
        }
    }

    /** С ростом уровня музыка слегка ускоряется (без смены тона). */
    fun setIntensity(level: Int) {
        try {
            val m = music ?: return
            if (!musicReady || !m.isPlaying || Build.VERSION.SDK_INT < 23) return
            val speed = (1f + 0.02f * (level - 1)).coerceAtMost(1.18f)
            m.playbackParams = m.playbackParams.setSpeed(speed)
        } catch (e: Exception) {
            // некоторые устройства не поддерживают смену скорости
        }
    }

    fun release() {
        try {
            pool.release()
            music?.release()
        } catch (e: Exception) {
            // игнорируем
        }
        music = null
        musicReady = false
    }
}
