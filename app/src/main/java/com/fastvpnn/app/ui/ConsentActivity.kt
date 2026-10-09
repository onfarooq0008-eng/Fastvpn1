package com.fastvpnn.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.fastvpnn.app.data.AppSettings
import com.fastvpnn.app.data.ServerCache
import com.fastvpnn.app.databinding.ActivityConsentBinding
import com.fastvpnn.app.util.applyEdgeToEdgeInsets

/**
 * Shown once, before MainActivity is ever reached, on first launch. Gates
 * access behind explicit acceptance of the Privacy Policy / Terms -- required
 * for Play Store review of any VPN app (BIND_VPN_SERVICE is a sensitive
 * permission) and for ads (Meta Audience Network). See backend/api/public/privacy.html and
 * terms.html for the actual policy text -- served from AppSettings.backendApiUrl,
 * the same configurable backend host SettingsActivity's legal links use, rather
 * than a hardcoded domain that would silently go stale if that host ever changes.
 *
 * The close (X) button declines -- since using the app at all requires
 * accepting how it handles your traffic, declining just closes the app
 * rather than pretending there's a way to use it without agreeing.
 */
class ConsentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityConsentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdgeInsets(binding.root)

        val siteBaseUrl = AppSettings(this).backendApiUrl.trimEnd('/')
        binding.linkPrivacy.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$siteBaseUrl/privacy.html")))
        }
        binding.linkTerms.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$siteBaseUrl/terms.html")))
        }

        binding.buttonClose.setOnClickListener {
            finishAffinity()
        }

        binding.buttonAgree.setOnClickListener {
            AppSettings(this).hasAcceptedTerms = true
            com.fastvpnn.app.ads.AdsManager.init(application)
            // Ads start from MainActivity once the user has made their ad-privacy choice.
            // This is the one path to MainActivity that doesn't go through
            // SplashActivity, so it's the only other place that needs to start
            // the server prefetch -- otherwise a brand-new user's very first
            // launch would be the one time it's skipped.
            ServerCache.warm(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
