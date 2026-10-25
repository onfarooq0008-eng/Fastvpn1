package com.fastvpnn.app.ads

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.fastvpnn.app.data.AppSettings

/**
 * Ad gate. FastVPN is free because of ads, so the app asks whether ads are allowed:
 *   Allow       -> the Unity banner starts and the app can be used.
 *   Don't allow -> a second dialog explains the app cannot be used without ads: "Allow ads" or "Exit app".
 * Nothing in the app runs behind the dialog (it cannot be cancelled), and it is shown again on every launch
 * until the user has allowed ads.
 */
object AdsConsent {

    fun isAccepted(context: Context): Boolean = AppSettings(context).adsAccepted

    fun needsPrompt(context: Context) = !isAccepted(context)

    /** Shows the ad dialog. [onDone] runs after the user has allowed ads. */
    fun showDialog(activity: Activity, onDone: () -> Unit = {}) {
        if (activity.isFinishing || activity.isDestroyed) return
        val message = "FastVPN is free and supported by a banner ad from Unity Ads. Ads are required to use the app.\n\n" +
            "Allow ads? Unity may use your device's advertising ID to show ads that fit your interests.\n\n" +
            "If you choose \u201cDon\u2019t allow\u201d, you will not be able to use the app."
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
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                // no browser installed: nothing sensible to do
            }
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
        AdsManager.init(activity.application)
        onDone()
    }
}
