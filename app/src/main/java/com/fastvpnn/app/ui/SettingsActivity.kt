package com.fastvpnn.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.fastvpnn.app.BuildConfig
import com.fastvpnn.app.R
import com.fastvpnn.app.data.AppSettings
import com.fastvpnn.app.databinding.ActivitySettingsBinding
import com.fastvpnn.app.databinding.ViewSettingRowBinding
import com.fastvpnn.app.ads.AdsConsent
import com.fastvpnn.app.util.ThemeUtil
import com.fastvpnn.app.util.applyEdgeToEdgeInsets

/** Regular-user settings only. Server management is handled entirely by the
 *  backend/VPS, so there's no in-app admin surface here. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdgeInsets(binding.root)
        settings = AppSettings(this)

        binding.buttonSettingsBack.setOnClickListener { finish() }
        ThemeUtil.bind(binding.buttonThemeSettings)
        setUpBottomNav()
        setUpGeneral()
        setUpOther()
    }

    override fun onResume() {
        super.onResume()
        // DNS may have been changed from the dialog; keep the subtitle current.
        binding.rowDns.rowSubtitle.text = dnsLabel()
        binding.rowAdChoices.rowSubtitle.text = AdsConsent.summary(this)
    }

    private fun setUpBottomNav() {
        binding.bottomNav.selectedItemId = R.id.nav_settings
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { openMain(MainActivity.TAB_HOME); true }
                R.id.nav_locations -> { openMain(MainActivity.TAB_LOCATIONS); true }
                else -> true
            }
        }
    }

    private fun openMain(tab: Int) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun row(
        b: ViewSettingRowBinding,
        icon: Int,
        title: String,
        subtitle: String,
        withSwitch: Boolean = false,
        onClick: () -> Unit
    ) {
        b.rowIcon.setImageResource(icon)
        b.rowTitle.text = title
        b.rowSubtitle.text = subtitle
        b.rowSubtitle.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        b.rowSwitch.visibility = if (withSwitch) View.VISIBLE else View.GONE
        b.rowChevron.visibility = if (withSwitch) View.GONE else View.VISIBLE
        b.root.setOnClickListener { onClick() }
    }

    private fun setUpGeneral() {
        row(binding.rowAutoConnect, R.drawable.ic_refresh, "Auto Connect", "Connect to last used location", withSwitch = true) {
            binding.rowAutoConnect.rowSwitch.toggle()
        }
        binding.rowAutoConnect.rowSwitch.isChecked = settings.autoConnectEnabled
        binding.rowAutoConnect.rowSwitch.setOnCheckedChangeListener { _, checked ->
            settings.autoConnectEnabled = checked
        }

        row(binding.rowProtocol, R.drawable.ic_protocol, "VPN Protocol", "Auto (Recommended)") {
            AlertDialog.Builder(this)
                .setTitle("VPN Protocol")
                .setMessage("FastVPN uses WireGuard, a modern, fast and secure protocol, and picks the best settings for you automatically.")
                .setPositiveButton("OK", null)
                .show()
        }

        // Android only lets the *user* (not the app itself) turn on true lockdown mode
        // ("Block connections without VPN"), so a local toggle here would falsely claim
        // protection. This row deep-links straight to the system VPN settings instead.
        row(binding.rowKillSwitch, R.drawable.ic_block, "Kill Switch", "Block internet if VPN disconnects") {
            Toast.makeText(this, "Turn on “Block connections without VPN” for FastVPN", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "Open Android Settings → Network → VPN", Toast.LENGTH_LONG).show()
            }
        }

        row(binding.rowSplit, R.drawable.ic_split, "Split Tunneling", "Choose apps to use VPN") {
            startActivity(Intent(this, SplitTunnelActivity::class.java))
        }

        row(binding.rowDns, R.drawable.ic_dns, "DNS", dnsLabel()) { showDnsDialog() }
    }

    private fun setUpOther() {
        val siteBaseUrl = settings.backendApiUrl.trimEnd('/')

        // Both open the Play Store listing (R.string.play_store_url).
        row(binding.rowRate, R.drawable.ic_star_border, "Rate Us", "If you like our app") {
            openInBrowser(getString(R.string.play_store_url))
        }
        row(binding.rowShare, R.drawable.ic_share, "Share App", "Share with your friends") {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Try FastVPN – fast, secure and private: ${getString(R.string.play_store_url)}")
            }
            startActivity(Intent.createChooser(send, "Share FastVPN"))
        }
        row(binding.rowHelp, R.drawable.ic_help, "Help & Support", "Get help when you need it") { openContactEmail() }
        // No "turn off personalised ads" option for users outside the EEA/UK/Switzerland.
        // Inside them the switch stays: the law requires that consent can be withdrawn as easily as it was given.
        val consentRegion = AdsConsent.requiresConsentRegion(this)
        binding.rowAdChoices.root.visibility = if (consentRegion) View.VISIBLE else View.GONE
        binding.dividerAdChoices.visibility = if (consentRegion) View.VISIBLE else View.GONE
        if (consentRegion) {
            row(binding.rowAdChoices, R.drawable.ic_shield_check, "Personalised ads", AdsConsent.summary(this), withSwitch = true) {
                binding.rowAdChoices.rowSwitch.toggle()
            }
            binding.rowAdChoices.rowSwitch.isChecked = AdsConsent.decision(this) == AdsConsent.Decision.PERSONALIZED
            binding.rowAdChoices.rowSwitch.setOnCheckedChangeListener { _, checked ->
                AdsConsent.setPersonalized(this, checked)
                binding.rowAdChoices.rowSubtitle.text = AdsConsent.summary(this)
            }
        }
        row(binding.rowPrivacy, R.drawable.ic_lock, "Privacy Policy", "") { openInBrowser("$siteBaseUrl/privacy.html") }
        row(binding.rowTerms, R.drawable.ic_doc, "Terms & Conditions", "") { openInBrowser("$siteBaseUrl/terms.html") }
        row(binding.rowAbout, R.drawable.ic_info, "About", "FastVPN v${BuildConfig.VERSION_NAME}") {
            AlertDialog.Builder(this)
                .setTitle("FastVPN")
                .setMessage("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\nContact: ${getString(R.string.contact_email)}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No browser app found to open this link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openContactEmail() {
        val email = getString(R.string.contact_email)
        // ACTION_SENDTO with a mailto: Uri targets only email apps.
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, "FastVPN Support")
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No email app found — reach us at $email", Toast.LENGTH_LONG).show()
        }
    }

    // ---- DNS ----------------------------------------------------------------

    private val dnsModes = listOf("server", "google", "cloudflare", "adblock", "custom")
    private val dnsNames = listOf(
        "Server default", "Google  •  8.8.8.8", "Cloudflare  •  1.1.1.1", "AdGuard  •  Ad blocking", "Custom…"
    )

    private fun dnsLabel(): String = when (settings.dnsMode) {
        "google" -> "Google"
        "cloudflare" -> "Cloudflare"
        "adblock" -> "AdGuard (ad blocking)"
        "custom" -> "Custom"
        else -> "Server default"
    }

    /** See AppSettings.resolveDns / dnsChangePendingReconnect for how this is applied. */
    private fun showDnsDialog() {
        val checked = dnsModes.indexOf(settings.dnsMode).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("DNS resolver")
            .setSingleChoiceItems(dnsNames.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                val mode = dnsModes[which]
                if (mode == "custom") {
                    showCustomDnsDialog()
                } else {
                    settings.dnsMode = mode
                    settings.dnsChangePendingReconnect = true
                    binding.rowDns.rowSubtitle.text = dnsLabel()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomDnsDialog() {
        val input = EditText(this).apply {
            hint = "9.9.9.9, 149.112.112.112"
            setText(settings.customDns)
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Custom DNS")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                settings.customDns = input.text.toString().trim()
                settings.dnsMode = "custom"
                settings.dnsChangePendingReconnect = true
                binding.rowDns.rowSubtitle.text = dnsLabel()
                Toast.makeText(this, "Custom DNS saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
