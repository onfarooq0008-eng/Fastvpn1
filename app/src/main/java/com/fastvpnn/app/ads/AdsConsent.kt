package com.fastvpnn.app.ads

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.TelephonyManager
import com.fastvpnn.app.data.AppSettings
import java.util.Locale

/**
 * Ad gate. FastVPN is free because of ads, so the app asks once whether ads are allowed:
 *   Allow       -> ads (Meta Audience Network + Unity Ads) start and the app can be used.
 *   Don't allow -> a second dialog explains the app cannot be used without ads: "Allow ads" or "Exit app".
 * Nothing in the app runs behind the dialog (it cannot be cancelled), and it is shown again on every launch
 * until the user has allowed ads.
 */
object AdsConsent {

    enum class Decision { UNSET, PERSONALIZED, LIMITED, DISABLED }

    private val CONSENT_REGIONS = setOf(
        // EU
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT",
        "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE",
        // EEA + UK + Switzerland
        "IS", "LI", "NO", "GB", "CH"
    )

    fun requiresConsentRegion(context: Context): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val country = listOf(tm?.networkCountryIso, tm?.simCountryIso, Locale.getDefault().country)
            .firstOrNull { !it.isNullOrBlank() }
            ?.uppercase(Locale.US)
        return country == null || country in CONSENT_REGIONS
    }

    /** Only two states exist now: not answered yet, or ads allowed. */
    fun decision(context: Context): Decision =
        if (AppSettings(context).adsAccepted) Decision.PERSONALIZED else Decision.UNSET

    fun needsPrompt(context: Context) = decision(context) == Decision.UNSET

    /** Shows the ad dialog. [onDone] runs after the user has allowed ads. */
    fun showDialog(activity: Activity, onDone: () -> Unit = {}) {
        if (activity.isFinishing || activity.isDestroyed) return
        val message = buildString {
            append("FastVPN is free and supported by ads from Meta Audience Network and Unity Ads. Ads are required to use the app.\n\n")
            append("Allow ads? These partners may use your device's advertising ID to show ads that fit your interests.\n\n")
            append("If you choose \u201cDon\u2019t allow\u201d, you will not be able to use the app.")
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Allow ads")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Allow") { _, _ -> accept(activity, onDone) }
            .setNegativeButton("Don\u2019t allow") { _, _ -> showBlocked(activity, onDone) }
            .setNeutralButton("Privacy Policy", null)
            .create()
        dialog.show()
        // Open the policy without dismissing the dialog.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val url = AppSettings(activity).backendApiUrl.trimEnd('/') + "/privacy.html"
            try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
        }
    }

    private fun showBlocked(activity: Activity, onDone: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle("Ads are required")
            .setMessage("You cannot use FastVPN without ads. Ads are what keep the app free.\n\nAllow ads to continue, or exit the app.")
            .setCancelable(false)
            .setPositiveButton("Allow ads") { _, _ -> accept(activity, onDone) }
            .setNegativeButton("Exit app") { _, _ -> activity.finishAffinity() }
            .show()
    }

    private fun accept(activity: Activity, onDone: () -> Unit) {
        AppSettings(activity).adsAccepted = true
        AdsManager.onConsentChanged(activity.application)
        AdsManager.init(activity.application)
        onDone()
    }

    /** Short label for the Settings row (the row is hidden now that ads are required). */
    fun summary(context: Context): String =
        if (decision(context) == Decision.PERSONALIZED) "Ads allowed" else "Ads not allowed yet"
}
