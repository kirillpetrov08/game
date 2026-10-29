package com.kirillpetrov.orbita

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool

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

    init {
        val all = intArrayOf(
            R.raw.sfx_switch, R.raw.sfx_crystal, R.raw.sfx_shield, R.raw.sfx_hit,
            R.raw.sfx_over, R.raw.sfx_click, R.raw.sfx_levelup, R.raw.sfx_revive
        )
        for (r in all) ids[r] = pool.load(context, r, 1)
    }

    fun play(res: Int, vol: Float = 1f, rate: Float = 1f) {
        if (!prefs.sound) return
        val id = ids[res] ?: return
        pool.play(id, vol, vol, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun startMusic() {
        if (!prefs.music) return
        try {
            if (music == null) {
                music = MediaPlayer.create(context, R.raw.music)?.apply {
                    isLooping = true
                    setVolume(0.38f, 0.38f)
                }
            }
            val m = music
            if (m != null && !m.isPlaying) m.start()
        } catch (e: Exception) {
            music = null
        }
    }

    fun pauseMusic() {
        try {
            val m = music
            if (m != null && m.isPlaying) m.pause()
        } catch (e: Exception) {
            // игнорируем
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
    }
}
