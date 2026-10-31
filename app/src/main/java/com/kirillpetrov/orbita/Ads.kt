package com.kirillpetrov.orbita

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.yandex.mobile.ads.appopenad.AppOpenAd
import com.yandex.mobile.ads.appopenad.AppOpenAdEventListener
import com.yandex.mobile.ads.appopenad.AppOpenAdLoadListener
import com.yandex.mobile.ads.appopenad.AppOpenAdLoader
import com.yandex.mobile.ads.banner.BannerAdEventListener
import com.yandex.mobile.ads.banner.BannerAdSize
import com.yandex.mobile.ads.banner.BannerAdView
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.common.YandexAds
import com.yandex.mobile.ads.rewarded.Reward
import com.yandex.mobile.ads.rewarded.RewardedAd
import com.yandex.mobile.ads.rewarded.RewardedAdEventListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoadListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoader
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** ID рекламных блоков из кабинета РСЯ (приложение 20302673). В debug-сборке — демо-блоки. */
object AdIds {
    val BANNER: String = if (BuildConfig.DEBUG) "demo-banner-yandex" else "R-M-20302673-1"
    val REWARDED: String = if (BuildConfig.DEBUG) "demo-rewarded-yandex" else "R-M-20302673-2"
    val APP_OPEN: String = if (BuildConfig.DEBUG) "demo-appopenad-yandex" else "R-M-20302673-3"
}

object AdsManager {
    private var initialized = false
    private val waiting = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun init(app: Application) {
        YandexAds.initialize(app) {
            main.post {
                initialized = true
                val list = ArrayList(waiting)
                waiting.clear()
                for (r in list) r()
            }
        }
    }

    fun whenReady(block: () -> Unit) {
        if (initialized) block() else waiting.add(block)
    }
}

/**
 * Адаптивный sticky-баннер внизу экрана.
 * Высота контейнера фиксирована (запоминаем с прошлого запуска), а менять её можно только вне забега —
 * иначе игровое поле «прыгало» бы, когда баннер догрузится в середине игры.
 */
class BannerHelper(
    private val activity: Activity,
    private val container: FrameLayout,
    private val prefs: Prefs,
    private val canResize: () -> Boolean
) {
    private var banner: BannerAdView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var retry = 0
    private var pendingH = 0
    private var currentH = 0
    private var destroyed = false

    init {
        val dm = activity.resources.displayMetrics
        setHeight(if (prefs.bannerH > 0) prefs.bannerH else (100f * dm.density).toInt())
    }

    private fun setHeight(px: Int) {
        currentH = px
        val lp = container.layoutParams
        lp.height = px
        container.layoutParams = lp
    }

    fun start() {
        AdsManager.whenReady { container.post { load() } }
    }

    private fun load() {
        if (destroyed || activity.isDestroyed || banner != null) return
        val dm = activity.resources.displayMetrics
        var widthPx = container.width
        if (widthPx <= 0) widthPx = dm.widthPixels
        val widthDp = (widthPx / dm.density).roundToInt()
        val view = BannerAdView(activity)
        view.setAdSize(BannerAdSize.sticky(activity, widthDp))
        view.setBannerAdEventListener(object : BannerAdEventListener {
            override fun onAdLoaded() {
                retry = 0
                view.post { measureAndApply(view.height) }
            }

            override fun onAdFailedToLoad(adRequestError: AdRequestError) {
                scheduleRetry()
            }

            override fun onAdClicked() {}

            override fun onImpression(impressionData: ImpressionData?) {}
        })
        container.addView(
            view,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        banner = view
        view.loadAd(AdRequest.Builder(AdIds.BANNER).build())
    }

    private fun measureAndApply(h: Int) {
        if (destroyed || h <= 0) return
        prefs.bannerH = h
        if (h == currentH) return
        if (canResize()) {
            setHeight(h)
            pendingH = 0
        } else {
            pendingH = h
        }
    }

    /** Вызывается игрой при входе/выходе из забега. */
    fun onRunStateChanged(inRun: Boolean) {
        if (!inRun && pendingH > 0) {
            setHeight(pendingH)
            pendingH = 0
        }
    }

    private fun scheduleRetry() {
        if (destroyed) return
        val delay = minOf(120_000L, 15_000L shl minOf(retry, 3))
        retry++
        handler.postDelayed({ reload() }, delay)
    }

    private fun reload() {
        val v = banner ?: return
        if (destroyed || activity.isDestroyed) return
        v.loadAd(AdRequest.Builder(AdIds.BANNER).build())
    }

    fun destroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        banner?.destroy()
        banner = null
    }
}

/** Видео с наградой (воскрешение, бонусные кристаллы). */
class RewardedHelper(private val activity: Activity) {
    private var loader: RewardedAdLoader? = null
    private var ad: RewardedAd? = null
    private var loading = false
    private var failedAt = 0L

    val isReady: Boolean get() = ad != null

    fun preload() {
        if (ad != null || loading) return
        if (failedAt != 0L && SystemClock.elapsedRealtime() - failedAt < 20_000L) return
        AdsManager.whenReady { load() }
    }

    private fun load() {
        if (ad != null || loading || activity.isDestroyed) return
        val l = loader ?: RewardedAdLoader(activity).also { loader = it }
        loading = true
        l.loadAd(AdRequest.Builder(AdIds.REWARDED).build(), object : RewardedAdLoadListener {
            override fun onAdLoaded(rewardedAd: RewardedAd) {
                ad = rewardedAd
                loading = false
                failedAt = 0L
            }

            override fun onAdFailedToLoad(adRequestError: AdRequestError) {
                ad = null
                loading = false
                failedAt = SystemClock.elapsedRealtime()
            }
        })
    }

    /** onResult(true) — пользователь досмотрел и получил награду. */
    fun show(onResult: (Boolean) -> Unit) {
        val a = ad
        if (a == null) {
            preload()
            onResult(false)
            return
        }
        ad = null
        var rewarded = false
        a.setAdEventListener(object : RewardedAdEventListener {
            override fun onAdShown() {}

            override fun onAdFailedToShow(adError: AdError) {
                a.setAdEventListener(null)
                preload()
                onResult(false)
            }

            override fun onAdDismissed() {
                a.setAdEventListener(null)
                preload()
                onResult(rewarded)
            }

            override fun onAdClicked() {}

            override fun onAdImpression(impressionData: ImpressionData?) {}

            override fun onRewarded(reward: Reward) {
                rewarded = true
            }
        })
        a.show(activity)
    }

    fun destroy() {
        ad?.setAdEventListener(null)
        ad = null
        loader = null
    }
}

/**
 * Реклама при открытии: холодный старт (после заставки) и возврат из фона.
 * Показываем не чаще раза в 2 минуты, не новичкам и только после ≥30 с в фоне.
 */
class AppOpenManager(private val app: Application, private val prefs: Prefs) :
    DefaultLifecycleObserver, Application.ActivityLifecycleCallbacks {

    private var loader: AppOpenAdLoader? = null
    private var ad: AppOpenAd? = null
    private var loading = false
    private var loadedAt = 0L
    private var failedAt = 0L
    private var lastShown = 0L
    private var stoppedAt = 0L
    private var firstStartSeen = false
    private var showing = false
    private var activityRef: WeakReference<Activity>? = null

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        AdsManager.whenReady { preload() }
    }

    val isReady: Boolean
        get() = ad != null && SystemClock.elapsedRealtime() - loadedAt < 3L * 3600_000L

    /** Показывать ли рекламу вообще (новичкам — нет). */
    fun wanted(): Boolean = prefs.launches >= 2

    private fun cooldownOk(): Boolean =
        lastShown == 0L || SystemClock.elapsedRealtime() - lastShown > 120_000L

    fun preload() {
        if (ad != null || loading) return
        if (failedAt != 0L && SystemClock.elapsedRealtime() - failedAt < 30_000L) return
        val l = loader ?: AppOpenAdLoader(app).also { loader = it }
        loading = true
        l.loadAd(AdRequest.Builder(AdIds.APP_OPEN).build(), object : AppOpenAdLoadListener {
            override fun onAdLoaded(appOpenAd: AppOpenAd) {
                ad = appOpenAd
                loading = false
                loadedAt = SystemClock.elapsedRealtime()
                failedAt = 0L
            }

            override fun onAdFailedToLoad(adRequestError: AdRequestError) {
                ad = null
                loading = false
                failedAt = SystemClock.elapsedRealtime()
            }
        })
    }

    /** Холодный старт: вызывается после заставки. onDone вызывается всегда. */
    fun showCold(activity: Activity, onDone: () -> Unit) {
        if (!wanted() || !isReady || !cooldownOk()) {
            onDone()
            return
        }
        show(activity, onDone)
    }

    private fun show(activity: Activity, onDone: () -> Unit) {
        val a = ad
        if (a == null || showing) {
            onDone()
            return
        }
        showing = true
        lastShown = SystemClock.elapsedRealtime()
        a.setAdEventListener(object : AppOpenAdEventListener {
            override fun onAdShown() {}

            override fun onAdFailedToShow(adError: AdError) {
                finish(onDone)
            }

            override fun onAdDismissed() {
                finish(onDone)
            }

            override fun onAdClicked() {}

            override fun onAdImpression(impressionData: ImpressionData?) {}
        })
        a.show(activity)
    }

    private fun finish(onDone: () -> Unit) {
        ad?.setAdEventListener(null)
        ad = null
        showing = false
        preload()
        onDone()
    }

    // возврат из фона
    override fun onStart(owner: LifecycleOwner) {
        if (!firstStartSeen) {
            firstStartSeen = true
            return
        }
        val act = activityRef?.get() ?: return
        if (!wanted() || !isReady || !cooldownOk() || showing) return
        if (SystemClock.elapsedRealtime() - stoppedAt < 30_000L) return
        show(act) {}
    }

    override fun onStop(owner: LifecycleOwner) {
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity is MainActivity) activityRef = WeakReference(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}
