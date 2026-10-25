package com.fastvpnn.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.fastvpnn.app.data.AppSettings
import com.fastvpnn.app.data.ServerCache

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hasAcceptedTerms = AppSettings(this).hasAcceptedTerms
        val next = if (hasAcceptedTerms) {
            // Kick off the server fetch right now, while the logo is on screen,
            // so MainActivity opens with a warm (or already-loading) list instead
            // of starting the network call after the user is looking at the home
            // screen. Only do this once terms are accepted -- no network calls
            // before consent.
            ServerCache.warm(this)
            MainActivity::class.java
        } else {
            ConsentActivity::class.java
        }
        startActivity(Intent(this, next))
        finish()
    }
}
