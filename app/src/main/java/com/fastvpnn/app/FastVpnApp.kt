package com.fastvpnn.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.fastvpnn.app.ads.AdsManager
import com.fastvpnn.app.data.AppSettings

class FastVpnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        applyThemeMode()
        // Ads must not load before the user has accepted the consent screen (ConsentActivity). Returning
        // users get the ad SDK started here; first-run users get it from ConsentActivity's "I Agree" button.
        // init() itself also waits for the user's "Allow ads" answer.
        if (AppSettings(this).hasAcceptedTerms) AdsManager.init(this)
    }

    private fun applyThemeMode() {
        val mode = when (AppSettings(this).themeMode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
