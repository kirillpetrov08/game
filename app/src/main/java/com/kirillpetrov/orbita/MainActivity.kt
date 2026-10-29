package com.kirillpetrov.orbita

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity(), GameView.Host {
    private lateinit var game: GameView
    private lateinit var root: LinearLayout
    private lateinit var sfx: Sfx
    private lateinit var banner: BannerHelper
    private lateinit var rewarded: RewardedHelper
    private lateinit var appOpen: AppOpenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }

        val app = application as App
        appOpen = app.appOpen
        sfx = Sfx(this, app.prefs)
        game = GameView(this, app.prefs, sfx, this)

        val bannerBox = FrameLayout(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#04061A"))
            addView(game, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bannerBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsetsCompat.CONSUMED
        }
        banner = BannerHelper(this, bannerBox)
        rewarded = RewardedHelper(this)
        banner.start()
        rewarded.preload()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!game.onBack()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        game.onHostResume()
        sfx.startMusic()
        rewarded.preload()
    }

    override fun onPause() {
        game.onHostPause()
        sfx.pauseMusic()
        super.onPause()
    }

    override fun onDestroy() {
        banner.destroy()
        rewarded.destroy()
        sfx.release()
        super.onDestroy()
    }

    // ---- GameView.Host ----
    override fun rewardedReady(): Boolean = rewarded.isReady

    override fun requestRewarded(onResult: (Boolean) -> Unit) {
        rewarded.show(onResult)
    }

    override fun appOpenWanted(): Boolean = appOpen.wanted()

    override fun appOpenReady(): Boolean = appOpen.isReady

    override fun showAppOpen(onDone: () -> Unit) {
        appOpen.showCold(this, onDone)
    }

    override fun haptic(strong: Boolean) {
        game.performHapticFeedback(
            if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    override fun musicChanged(on: Boolean) {
        if (on) sfx.startMusic() else sfx.pauseMusic()
    }
}
