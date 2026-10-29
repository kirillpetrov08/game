package com.kirillpetrov.orbita

import android.app.Application

class App : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var appOpen: AppOpenManager
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        prefs.launches = prefs.launches + 1
        AdsManager.init(this)
        appOpen = AppOpenManager(this, prefs)
        appOpen.start()
    }
}
