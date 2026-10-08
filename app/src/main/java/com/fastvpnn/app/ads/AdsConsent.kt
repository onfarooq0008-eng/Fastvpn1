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
 * Ad privacy choice.
 *
 * What the ad SDKs officially offer (and what this class drives):
 *  - Meta Audience Network (Android): AdSettings.setDataProcessingOptions(...) -- "LDU" = Limited Data Use.
 *    Meta has no GDPR consent flag on Android: the publisher must obtain consent first and simply not
 *    request/serve personalised Meta ads without it.
 *  - Unity Ads: UnityAds.userConsent / userOptOut / nonBehavioral.
 *
 * Flow: outside the EEA/UK/CH personalised ads are on by default with a switch in Settings > Personalised ads;
 * inside them a first-party dialog is asked once (changeable with the same switch):
 *   Allow     -> personalised ads
 *   Don't allow -> non-personalised ads (Meta LDU + Unity non-behavioural);
 *                  in the EEA/UK/Switzerland (or when the country can't be determined) NO ad SDK is started at all.
 *
 * Limits: region detection is a heuristic (SIM / network country, then device locale), and this is not an
 * IAB TCF certified CMP. If you need TCF strings or a legal sign-off, use a certified CMP and feed its result
 * into AdsConsent -- see UPGRADE_NOTES.md.
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

    /**
     * Outside the EEA/UK/Switzerland, personalised ads are ON by default (no dialog) and the user can switch them
     * off in Settings. Inside those regions (or when the country can't be detected) consent is legally required first,
     * so the choice dialog is still shown and nothing runs until the user has answered.
     */
    fun decision(context: Context): Decision = when (AppSettings(context).adConsent) {
        "personalized" -> Decision.PERSONALIZED
        "declined" -> if (requiresConsentRegion(context)) Decision.DISABLED else Decision.LIMITED
        else -> if (requiresConsentRegion(context)) Decision.UNSET else Decision.PERSONALIZED
    }

    /** Settings switch. true = personalised ads allowed. */
    fun setPersonalized(activity: Activity, allowed: Boolean) {
        AppSettings(activity).adConsent = if (allowed) "personalized" else "declined"
        AdsManager.onConsentChanged(activity.application)
        AdsManager.init(activity.application)
    }

    fun needsPrompt(context: Context) = decision(context) == Decision.UNSET

    /** Shows the choice dialog. [onDone] runs after the user has chosen. */
    fun showDialog(activity: Activity, onDone: () -> Unit = {}) {
        if (activity.isFinishing || activity.isDestroyed) return
        val settings = AppSettings(activity)
        val consentRegion = requiresConsentRegion(activity)
        val message = buildString {
            append("FastVPN is free and supported by ads from Meta Audience Network and Unity Ads.\n\n")
            append("Allow personalised ads? These partners may use your device's advertising ID to show ads that fit your interests.\n\n")
            append("If you choose \u201cDon\u2019t allow\u201d, ")
            append(if (consentRegion) "no ads will be requested in your region." else "you will only see non-personalised ads.")
            append("\nYou can change this any time in Settings \u203a Ad privacy choices.")
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Your ad choices")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Allow") { _, _ -> finish(activity, settings, "personalized", onDone) }
            .setNegativeButton("Don\u2019t allow") { _, _ -> finish(activity, settings, "declined", onDone) }
            .setNeutralButton("Privacy Policy", null)
            .create()
        dialog.show()
        // Open the policy without dismissing the dialog.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val url = AppSettings(activity).backendApiUrl.trimEnd('/') + "/privacy.html"
            try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
        }
    }

    private fun finish(activity: Activity, settings: AppSettings, value: String, onDone: () -> Unit) {
        settings.adConsent = value
        AdsManager.onConsentChanged(activity.application)
        AdsManager.init(activity.application)
        onDone()
    }

    /** Short label for the Settings row. */
    fun summary(context: Context): String = when (decision(context)) {
        Decision.PERSONALIZED -> "On: ads can use your advertising ID to be more relevant"
        Decision.LIMITED -> "Off: you only see non-personalised ads"
        Decision.DISABLED -> "Off: ads are not requested in your region"
        Decision.UNSET -> "Not chosen yet"
    }
}
