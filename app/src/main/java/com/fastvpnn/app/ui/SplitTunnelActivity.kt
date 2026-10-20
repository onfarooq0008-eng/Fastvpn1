package com.fastvpnn.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fastvpnn.app.data.AppSettings
import com.fastvpnn.app.databinding.ActivitySplitTunnelBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.fastvpnn.app.util.applyEdgeToEdgeInsets

class SplitTunnelActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplitTunnelBinding
    private lateinit var settings: AppSettings
    private lateinit var adapter: AppListAdapter
    // Read from preferences once; the adapter asks about every visible row on every bind.
    private val excluded = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplitTunnelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdgeInsets(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        settings = AppSettings(this)
        excluded.addAll(settings.excludedPackages)
        binding.recyclerApps.layoutManager = LinearLayoutManager(this)
        adapter = AppListAdapter(
            isBypassed = { pkg -> excluded.contains(pkg) },
            loadIcon = { pkg -> runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull() },
            onToggle = { app, checked ->
                if (checked) excluded.add(app.packageName) else excluded.remove(app.packageName)
                settings.excludedPackages = excluded.toSet()
                // The app list is baked into the tunnel when it is established, so a connected
                // tunnel is cycled the next time the main screen is shown (see MainActivity).
                settings.settingsChangePendingReconnect = true
            }
        )
        binding.recyclerApps.adapter = adapter

        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = packageManager
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
                    .mapNotNull { resolveInfo ->
                        val pkg = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                        if (pkg == packageName) return@mapNotNull null // don't list this app itself
                        InstalledApp(
                            packageName = pkg,
                            label = resolveInfo.loadLabel(pm).toString()
                        )
                    }
                    .distinctBy { it.packageName }
                    .sortedBy { it.label.lowercase() }
            }
            adapter.submit(apps)
        }
    }
}
