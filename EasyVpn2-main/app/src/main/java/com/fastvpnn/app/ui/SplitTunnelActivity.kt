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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplitTunnelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdgeInsets(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        settings = AppSettings(this)
        binding.recyclerApps.layoutManager = LinearLayoutManager(this)
        adapter = AppListAdapter(
            isBypassed = { pkg -> settings.excludedPackages.contains(pkg) },
            onToggle = { app, checked ->
                val current = settings.excludedPackages.toMutableSet()
                if (checked) current.add(app.packageName) else current.remove(app.packageName)
                settings.excludedPackages = current
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
                            label = resolveInfo.loadLabel(pm).toString(),
                            icon = runCatching { resolveInfo.loadIcon(pm) }.getOrNull()
                        )
                    }
                    .distinctBy { it.packageName }
                    .sortedBy { it.label.lowercase() }
            }
            adapter.submit(apps)
        }
    }
}
