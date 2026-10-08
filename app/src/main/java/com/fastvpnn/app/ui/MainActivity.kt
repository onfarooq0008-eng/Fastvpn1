package com.fastvpnn.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.content.res.ColorStateList
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.fastvpnn.app.BuildConfig
import com.fastvpnn.app.R
import com.fastvpnn.app.ads.AdsConsent
import com.fastvpnn.app.ads.AdsManager
import com.fastvpnn.app.data.AppSettings
import com.fastvpnn.app.data.Server
import com.fastvpnn.app.data.ServerCache
import com.fastvpnn.app.data.ServerSource
import com.fastvpnn.app.databinding.ActivityMainBinding
import com.fastvpnn.app.util.NotificationHelper
import com.fastvpnn.app.util.PingUtil
import com.fastvpnn.app.util.ThemeUtil
import com.fastvpnn.app.util.SecureKeyStore
import com.fastvpnn.app.vpn.TunnelState
import com.fastvpnn.app.vpn.VpnTunnelManager
import com.fastvpnn.app.vpn.VpnTunnelManagerHolder
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import com.fastvpnn.app.util.applyEdgeToEdgeInsets

/**
 * Main screen with two tabs behind a bottom navigation bar: Home (power button, current
 * location) and Locations (servers grouped by country; tap a country with 2+ servers to
 * expand it inline). Settings opens as its own screen. Talks to your control API
 * automatically via the built-in Backend API URL -- see ServerSource /
 * BackendApiClient.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var serverSource: ServerSource
    private lateinit var appSettings: AppSettings
    private lateinit var keyStore: SecureKeyStore
    private lateinit var tunnelManager: VpnTunnelManager
    private lateinit var adapter: HomeListAdapter

    private var allServers: List<Server> = emptyList()
    private var searchQuery: String = ""
    private var expandedCountryCodes: MutableSet<String> = mutableSetOf()
    private var pendingChain: List<Server>? = null
    private var connectedServer: Server? = null
    private var statsJob: Job? = null
    private var refreshJob: Job? = null
    private var connectionFlowActive = false
    private var connectingServer: Server? = null

    private enum class Tab { HOME, LOCATIONS }
    private enum class LocationFilter { ALL, STREAMING, FAVORITES }
    private enum class UiState { DISCONNECTED, CONNECTING, CONNECTED }

    private var currentTab = Tab.HOME
    private var locationFilter = LocationFilter.ALL
    private var suppressNav = false
    private lateinit var locationsBackCallback: OnBackPressedCallback

    private val vpnPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingChain?.let { beginConnection(it) }
        } else {
            connectionFlowActive = false
            pendingChain = null
            updateStatusCard()
            updateActionButton()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* fine either way -- notification is a nice-to-have, not required to use the VPN */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdgeInsets(binding.root)

        serverSource = ServerSource(this)
        appSettings = AppSettings(this)
        keyStore = SecureKeyStore(this)
        tunnelManager = VpnTunnelManagerHolder.get(this)

        binding.recyclerServers.layoutManager = LinearLayoutManager(this)
        (binding.recyclerServers.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        adapter = HomeListAdapter(
            onHeaderClick = { group -> onCountryTapped(group) },
            onServerClick = { server -> showTab(Tab.HOME); onServerTapped(server) },
            onFavoriteClick = { group -> onFavoriteTapped(group) }
        )
        binding.recyclerServers.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadAndPing() }
        binding.powerButton.setOnClickListener { onActionButtonTapped() }
        binding.chronometerConnected.setOnChronometerTickListener { c ->
            c.text = formatElapsed(SystemClock.elapsedRealtime() - c.base)
        }

        setUpNavigation()
        setUpPremiumEntryPoints()
        ThemeUtil.bind(binding.buttonTheme)
        ThemeUtil.bind(binding.buttonThemeLocations)
        binding.buttonFastest.setOnClickListener { connectToFastest() }
        binding.buttonFastestLocations.setOnClickListener { showTab(Tab.HOME); connectToFastest() }

        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                renderRows()
            }
        })

        binding.tabAll.setOnClickListener { setFilter(LocationFilter.ALL) }
        binding.tabStreaming.setOnClickListener { setFilter(LocationFilter.STREAMING) }
        binding.tabFavorites.setOnClickListener { setFilter(LocationFilter.FAVORITES) }
        updateTabs()
        // After a theme switch the activity is recreated: restore the tab and don't auto-connect again.
        showTab(
            if (savedInstanceState != null) Tab.values()[savedInstanceState.getInt(STATE_TAB, 0).coerceIn(0, 1)]
            else tabFromIntent(intent)
        )

        requestNotificationPermissionIfNeeded()
        AdsManager.showBanner(this, binding.adContainer)
        // First launch: ask for the ad-privacy choice once; ads only start after it.
        if (savedInstanceState == null && AdsConsent.needsPrompt(this)) {
            AdsConsent.showDialog(this)
        }

        // Pick up the fetch SplashActivity already started while its logo was
        // showing, so this first load doesn't start the network call from zero.
        loadAndPing(warmPrefetch = ServerCache.take(), onDone = {
            lifecycleScope.launch {
                // Always reconcile the real WireGuard state before deciding whether to
                // auto-connect. This prevents an Activity-startup race from launching
                // a second connection while an existing tunnel is already active.
                tunnelManager.syncStateFromBackend()
                if (tunnelManager.state == TunnelState.DOWN) {
                    cleanupStaleRegistrationLeases()
                }
                restoreConnectedServerFromSettings()
                updateStatusCard()
                updateActionButton()

                if (savedInstanceState == null && appSettings.autoConnectEnabled && tunnelManager.state == TunnelState.DOWN && !connectionFlowActive) {
                    appSettings.lastConnectedServerId?.let { id ->
                        allServers.find { it.id == id }?.let { onServerTapped(it) }
                    }
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab.ordinal)
    }

    private fun tabFromIntent(intent: Intent?): Tab =
        if (intent?.getIntExtra(EXTRA_TAB, TAB_HOME) == TAB_LOCATIONS) Tab.LOCATIONS else Tab.HOME

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showTab(tabFromIntent(intent))
    }

    private fun setUpNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (suppressNav) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.nav_home -> { showTab(Tab.HOME); true }
                R.id.nav_locations -> { showTab(Tab.LOCATIONS); true }
                else -> { openSettings(); false } // keep the current tab highlighted
            }
        }
        binding.buttonMenu.setOnClickListener { openSettings() }
        binding.buttonLocationsBack.setOnClickListener { showTab(Tab.HOME) }
        binding.cardCurrentLocation.setOnClickListener { showTab(Tab.LOCATIONS) }
        binding.cardProtection.setOnClickListener { showTab(Tab.LOCATIONS) }

        locationsBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = showTab(Tab.HOME)
        }
        onBackPressedDispatcher.addCallback(this, locationsBackCallback)
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    /** The crown + "Go Premium" banner exist in the design, but the app has no paid tier
     *  yet, so they stay hidden until BuildConfig.SHOW_PREMIUM (app/build.gradle) is true. */
    private fun setUpPremiumEntryPoints() {
        if (!BuildConfig.SHOW_PREMIUM) return
        binding.buttonCrown.visibility = View.VISIBLE
        binding.bannerPremium.visibility = View.VISIBLE
        val comingSoon = View.OnClickListener {
            Toast.makeText(this, "Premium is coming soon", Toast.LENGTH_SHORT).show()
        }
        binding.buttonCrown.setOnClickListener(comingSoon)
        binding.bannerPremium.setOnClickListener(comingSoon)
    }

    private fun showTab(tab: Tab) {
        currentTab = tab
        val home = tab == Tab.HOME
        binding.paneHome.visibility = if (home) View.VISIBLE else View.GONE
        binding.paneLocations.visibility = if (home) View.GONE else View.VISIBLE
        locationsBackCallback.isEnabled = !home
        suppressNav = true
        binding.bottomNav.selectedItemId = if (home) R.id.nav_home else R.id.nav_locations
        suppressNav = false
        currentFocus?.let {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private fun setFilter(filter: LocationFilter) {
        locationFilter = filter
        updateTabs()
        renderRows()
    }

    private fun updateTabs() {
        binding.tabAll.isSelected = locationFilter == LocationFilter.ALL
        binding.tabStreaming.isSelected = locationFilter == LocationFilter.STREAMING
        binding.tabFavorites.isSelected = locationFilter == LocationFilter.FAVORITES
    }

    private fun onFavoriteTapped(group: CountryGroup) {
        val set = appSettings.favoriteCountries.toMutableSet()
        if (!set.add(group.countryCode)) set.remove(group.countryCode)
        appSettings.favoriteCountries = set
        renderRows()
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter.destroyAds()
        AdsManager.hideBanner(binding.adContainer)
    }

    override fun onPause() {
        super.onPause()
        // Stop polling backend.getStatistics() and the server-list auto-refresh
        // while backgrounded -- lifecycleScope only cancels these on onDestroy,
        // not onStop/onPause, so without this the loops would keep running (and
        // draining battery) the entire time the app sits in the background but
        // isn't actually killed.
        statsJob?.cancel()
        statsJob = null
        refreshJob?.cancel()
        refreshJob = null
    }

    override fun onResume() {
        super.onResume()
        // Have an interstitial ready before the user taps Connect (no-op if one is already loaded/loading).
        if (tunnelManager.state != TunnelState.UP) AdsManager.preloadInterstitial()

        // The backend tunnel state is authoritative for FastVPN. Android's generic
        // VPN transport flag is useful as a secondary signal, but it cannot tell us
        // which VPN belongs to this app. Most importantly, never let a missing
        // Activity-level connectedServer turn an actually-running tunnel into a
        // disconnected UI state.
        val wasConnected = tunnelManager.state == TunnelState.UP
        tunnelManager.syncStateFromBackend()
        if (tunnelManager.state == TunnelState.UP) {
            restoreConnectedServerFromSettings()
            if (!wasConnected) {
                startConnectionStats()
            } else if (statsJob == null) {
                // Was already connected before this pause -- just resume polling
                // where it left off instead of resetting the elapsed-time display.
                startStatsPolling()
            }
            reconnectIfDnsSettingChanged()
        } else if (wasConnected) {
            connectedServer = null
            stopConnectionStats()
        }
        updateStatusCard()
        updateActionButton()

        startServerAutoRefresh()
    }

    /** Settings -> DNS is applied on the WireGuard interface at connect time, so
     *  changing it while already connected has no effect on the running tunnel
     *  until the connection is cycled -- see AppSettings.dnsChangePendingReconnect.
     *  Runs a real reconnect (fresh registration + tunnel, same as switching
     *  servers) rather than just toggling the flag, so the new resolver is
     *  actually live afterward instead of only "will apply next time". */
    private fun reconnectIfDnsSettingChanged() {
        if (!appSettings.dnsChangePendingReconnect) return
        appSettings.dnsChangePendingReconnect = false
        val server = connectedServer ?: return
        if (connectionFlowActive) return
        android.widget.Toast.makeText(this, "Reconnecting to apply your new DNS setting…", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = tunnelManager.disconnect()
            if (result.isFailure) {
                tunnelManager.syncStateFromBackend()
                updateStatusCard()
                updateActionButton()
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Could not reconnect to apply DNS: ${result.exceptionOrNull()?.message ?: "disconnect failed"}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            releaseActiveRegistrationLease()
            onDisconnected()
            connectionFlowActive = true
            beginConnection(buildFailoverChain(server))
        }
    }

    /** Keeps the server list (and its ping times) current the whole time the app is
     *  in the foreground, instead of only refreshing once on open. Fires immediately,
     *  then every [SERVER_REFRESH_INTERVAL_MS] after that; onPause() cancels this job
     *  so it never keeps polling while backgrounded. */
    private fun startServerAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (true) {
                refreshServers(showSpinner = false)
                delay(SERVER_REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun loadAndPing(warmPrefetch: Deferred<List<Server>>? = null, onDone: (() -> Unit)? = null) {
        lifecycleScope.launch {
            refreshServers(showSpinner = true, warmPrefetch = warmPrefetch)
            onDone?.invoke()
        }
    }

    /** Fetches the server list, re-pings every server, and re-renders. Used for the
     *  initial load, pull-to-refresh, and the every-20s background auto-refresh.
     *
     *  On failure (backend timeout, no connection, etc.) this deliberately leaves
     *  [allServers] as-is instead of clearing it -- previously a failed fetch set
     *  it to an empty list, which wiped the whole screen to "No locations found"
     *  on any transient hiccup. [showSpinner] also gates whether a failure shows a
     *  toast: on for the visible pull-to-refresh/initial load, off for the silent
     *  background tick so a flaky connection doesn't nag the user every 20 seconds. */
    private suspend fun refreshServers(showSpinner: Boolean, warmPrefetch: Deferred<List<Server>>? = null) {
        if (showSpinner) binding.swipeRefresh.isRefreshing = true
        try {
            // If Splash already started (or finished) fetching, await that instead
            // of firing a second, redundant network call.
            val servers = warmPrefetch?.await() ?: serverSource.getServers()
            // Carry over ping times we already measured so already-known servers
            // don't flash back to "Checking..." on every refresh.
            servers.forEach { s -> allServers.find { it.id == s.id }?.let { s.pingMs = it.pingMs } }
            allServers = servers
            renderRows()
            val targets = servers.map { Triple(it.id, it.endpointHost, 22) }
            val results = PingUtil.pingAll(targets)
            servers.forEach { it.pingMs = results[it.id] ?: -2 }
            allServers = servers
            renderRows()
        } catch (e: Exception) {
            if (showSpinner) {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Couldn't refresh servers -- showing your last list.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } finally {
            if (showSpinner) binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun renderRows() {
        val hasStreaming = allServers.any { it.streaming }
        binding.tabStreaming.visibility = if (hasStreaming) View.VISIBLE else View.GONE
        if (!hasStreaming && locationFilter == LocationFilter.STREAMING) {
            locationFilter = LocationFilter.ALL
            updateTabs()
        }

        val favorites = appSettings.favoriteCountries
        val byTab = when (locationFilter) {
            LocationFilter.ALL -> allServers
            LocationFilter.STREAMING -> allServers.filter { it.streaming }
            LocationFilter.FAVORITES -> allServers.filter { favorites.contains(it.countryCode) }
        }
        val query = searchQuery.trim().lowercase()
        val filtered = if (query.isEmpty()) {
            byTab
        } else {
            byTab.filter {
                it.name.lowercase().contains(query) ||
                    it.countryName.lowercase().contains(query) ||
                    it.city.lowercase().contains(query)
            }
        }
        val connectedId = connectedServer?.id
        // The country you're connected to floats to the top (sort is stable otherwise).
        val groups = CountryGroup.groupByCountry(filtered)
            .sortedByDescending { g -> connectedId != null && g.servers.any { it.id == connectedId } }
        val rows = mutableListOf<HomeRow>()
        var nativeAdSlot = 0
        groups.forEachIndexed { index, group ->
            val expanded = expandedCountryCodes.contains(group.countryCode)
            rows.add(HomeRow.Header(group, expanded))
            if (expanded) {
                group.servers.forEach { rows.add(HomeRow.ServerRow(it)) }
            }
            // One native ad every 4 country rows -- frequent enough to monetize a long
            // server list, spaced out enough to not feel like it's crowding real results.
            if ((index + 1) % 4 == 0 && index != groups.lastIndex) {
                rows.add(HomeRow.NativeAdRow(nativeAdSlot++))
            }
        }
        adapter.submit(rows, connectedId, favorites)

        when {
            groups.isNotEmpty() -> binding.emptyState.visibility = View.GONE
            else -> {
                binding.emptyState.visibility = View.VISIBLE
                when {
                    query.isEmpty() && locationFilter == LocationFilter.FAVORITES -> {
                        binding.textEmptyTitle.text = "No favorites yet"
                        binding.textEmptyBody.text = "Tap the star next to a country to add it here."
                    }
                    query.isEmpty() && locationFilter == LocationFilter.STREAMING -> {
                        binding.textEmptyTitle.text = "No streaming locations"
                        binding.textEmptyBody.text = "Pull down to refresh your server list."
                    }
                    else -> {
                        binding.textEmptyTitle.text = "No locations found"
                        binding.textEmptyBody.text = "Try another search or pull down to refresh your server list."
                    }
                }
            }
        }
        updateCurrentLocation()
    }

    private fun onCountryTapped(group: CountryGroup) {
        if (group.servers.size == 1) {
            onServerTapped(group.servers.first())
            return
        }
        if (expandedCountryCodes.contains(group.countryCode)) {
            expandedCountryCodes.remove(group.countryCode)
        } else {
            expandedCountryCodes.add(group.countryCode)
        }
        renderRows()
    }

    private fun onActionButtonTapped() {
        if (tunnelManager.state == TunnelState.UP) {
            // VPN is connected: never show an interstitial here, just disconnect.
            disconnectFromHome()
        } else {
            connectToPreferred()
        }
    }

    private fun disconnectFromHome() {
        run {
            // Do not require connectedServer here. That field belongs to the Activity
            // and is lost when the Activity is recreated, while the WireGuard tunnel
            // can still be running. The notification can disconnect successfully in
            // exactly this situation, so the home button must do the same.
            lifecycleScope.launch {
                val result = tunnelManager.disconnect()
                result.onSuccess {
                    releaseActiveRegistrationLease()
                    onDisconnected()
                }.onFailure {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Could not disconnect VPN: ${it.message ?: "unknown error"}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    // Re-read the manager state so the button never lies about the
                    // actual tunnel state after a failed disconnect attempt.
                    tunnelManager.syncStateFromBackend()
                    updateStatusCard()
                    updateActionButton()
                }
            }
        }
    }

    /** Power button while disconnected: reconnect to the last used location if there is
     *  one, otherwise fall back to a reachable server. */
    private fun connectToPreferred() {
        val last = appSettings.lastConnectedServerId?.let { id ->
            allServers.find { it.id == id && it.enabled && it.pingMs != -2 }
        }
        if (last != null) onServerTapped(last) else connectToFastest()
    }

    /** "Fastest Server" button: connects to a random reachable server (spreads load instead of
     *  everyone piling onto the lowest-ping box). While connected it hops to a different one. */
    private fun connectToFastest() {
        if (tunnelManager.state == TunnelState.CONNECTING || connectionFlowActive) return
        val currentId = if (tunnelManager.state == TunnelState.UP) connectedServer?.id else null
        val usable = allServers.filter { it.enabled && it.id != currentId }
        // Prefer servers that already answered a ping; fall back to ones not tested yet.
        val candidates = usable.filter { it.pingMs >= 0 }.ifEmpty { usable.filter { it.pingMs == -1 } }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "No reachable servers yet -- pull to refresh and try again", Toast.LENGTH_SHORT).show()
            return
        }
        onServerTapped(candidates.random())
    }

    /** Up to 2 other reachable servers (by ping) to try automatically if the
     *  tapped one turns out not to actually pass traffic -- see doConnect(). */
    private fun buildFailoverChain(primary: Server): List<Server> {
        val fallbacks = allServers
            .filter { it.enabled && it.id != primary.id && it.pingMs >= 0 }
            .sortedBy { it.pingMs }
            .take(2)
        return listOf(primary) + fallbacks
    }

    private fun onServerTapped(server: Server) {
        if (tunnelManager.state == TunnelState.CONNECTING || connectionFlowActive) return
        if (tunnelManager.state == TunnelState.UP) {
            if (connectedServer?.id == server.id) {
                lifecycleScope.launch {
                    val result = tunnelManager.disconnect()
                    if (result.isSuccess) {
                        releaseActiveRegistrationLease()
                        onDisconnected()
                    } else {
                        tunnelManager.syncStateFromBackend()
                        updateStatusCard()
                        updateActionButton()
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Could not disconnect VPN: ${result.exceptionOrNull()?.message ?: "unknown error"}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else {
                lifecycleScope.launch {
                    val result = tunnelManager.disconnect()
                    if (result.isFailure) {
                        tunnelManager.syncStateFromBackend()
                        updateStatusCard()
                        updateActionButton()
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Could not switch server: ${result.exceptionOrNull()?.message ?: "disconnect failed"}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                    releaseActiveRegistrationLease()
                    onDisconnected()
                    connectionFlowActive = true
                    // Switching servers while already connected: no interstitial.
                    beginConnection(buildFailoverChain(server), showAd = false)
                }
            }
            return
        }
        val chain = buildFailoverChain(server)
        pendingChain = chain
        connectionFlowActive = true
        updateActionButton()
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            // Use the local val, not the pendingChain property: it's the same list right
            // now, but reading it back through the mutable property would require a
            // non-null assertion since the compiler can't prove another callback hasn't
            // cleared it in between.
            beginConnection(chain)
        }
    }

    /** Attempts chain[attemptIndex]; on failure (interface never came up, OR it came
     *  up but couldn't actually reach the internet -- see ConnectivityCheckUtil),
     *  automatically tries the next candidate instead of just failing outright. */
    private fun beginConnection(chain: List<Server>, attemptIndex: Int = 0, showAd: Boolean = true) {
        if (attemptIndex >= chain.size) {
            connectionFlowActive = false
            pendingChain = null
            connectedServer = null
            updateStatusCard()
            updateActionButton()
            android.widget.Toast.makeText(
                this,
                "Couldn't establish a working connection through any nearby server. Check your internet connection or try again shortly.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        if (attemptIndex == 0) {
            if (showAd) {
                // Fresh connect (VPN currently off): if an interstitial is already loaded, show it first, then
                // connect. If none is ready, the connection starts immediately -- it never waits for an ad.
                AdsManager.showConnectInterstitial(this) { doConnect(chain, attemptIndex) }
            } else {
                doConnect(chain, attemptIndex)
            }
        } else {
            android.widget.Toast.makeText(
                this, "${chain[attemptIndex - 1].name} didn't work, trying another server…", android.widget.Toast.LENGTH_SHORT
            ).show()
            doConnect(chain, attemptIndex)
        }
    }

    private fun doConnect(chain: List<Server>, attemptIndex: Int) {
        val server = chain[attemptIndex]
        connectingServer = server
        updateStatusCard()
        lifecycleScope.launch {
            val privateKey = keyStore.clientPrivateKeyBase64()

            val registration = try {
                registerWithServer(server)
            } catch (e: Exception) {
                tryNextOrFail(chain, attemptIndex, "Registration failed: ${e.message}")
                return@launch
            }

            // Apply the user's DNS preference (Settings -> DNS) on top of whatever
            // the server itself specifies -- "Server default" leaves connectServer.dns
            // untouched; any other mode substitutes the chosen resolver.
            val connectServer = registration.server.copy(dns = appSettings.resolveDns(registration.server.dns))

            val result = tunnelManager.connect(
                connectServer,
                privateKey,
                excludedPackages = appSettings.excludedPackages,
                assignedAddressCidr = registration.assignedAddressCidr
            )
            result.onSuccess {
                // The interface coming up doesn't prove it actually works -- verify
                // real traffic flows through it before declaring success to the user.
                val working = com.fastvpnn.app.util.ConnectivityCheckUtil.verifyInternetThroughVpnWithRetries(this@MainActivity)
                if (working) {
                    connectionFlowActive = false
                    pendingChain = null
                    connectedServer = server
                    appSettings.lastConnectedServerId = server.id
                    // This connect already used the current DNS setting (see
                    // resolveDns() above), so any earlier pending-reconnect flag
                    // is now moot -- clear it to avoid an unnecessary follow-up
                    // reconnect next time onResume runs.
                    appSettings.dnsChangePendingReconnect = false
                    if (registration.token.isNotBlank()) {
                        keyStore.promotePendingToActive(registration.serverId, registration.token)
                    }
                    updateStatusCard()
                    updateActionButton()
                    startConnectionStats()
                    renderRows()
                    NotificationHelper.showConnected(this@MainActivity, "${server.flagEmoji()} ${server.name}")
                } else {
                    tunnelManager.disconnect()
                    releaseRegistrationLease(registration.serverId, registration.token)
                    tryNextOrFail(chain, attemptIndex, null)
                }
            }
            result.onFailure {
                releaseRegistrationLease(registration.serverId, registration.token)
                tryNextOrFail(chain, attemptIndex, "Connection failed: ${it.message}")
            }
        }
    }

    private suspend fun releaseRegistrationLease(serverId: String?, token: String?) {
        if (serverId.isNullOrBlank() || token.isNullOrBlank()) return
        val success = try {
            serverSource.unregister(keyStore.clientPublicKeyBase64(), serverId, token)
            true
        } catch (_: Exception) {
            false
        }
        if (success) {
            keyStore.removePendingRegistration(serverId, token)
            if (keyStore.activeRegistration()?.serverId == serverId && keyStore.activeRegistration()?.token == token) {
                keyStore.clearActiveRegistration()
            }
        }
    }

    private suspend fun releaseActiveRegistrationLease() {
        val lease = keyStore.activeRegistration() ?: return
        releaseRegistrationLease(lease.serverId, lease.token)
    }

    private suspend fun cleanupStaleRegistrationLeases() {
        // If the VPN is down, no registration lease should remain active.
        keyStore.activeRegistration()?.let { releaseRegistrationLease(it.serverId, it.token) }
        keyStore.pendingRegistrations().forEach { releaseRegistrationLease(it.serverId, it.token) }
    }

    private fun restoreConnectedServerFromSettings() {
        if (tunnelManager.state != TunnelState.UP || connectedServer != null) return
        appSettings.lastConnectedServerId?.let { id ->
            allServers.find { it.id == id }?.let {
                connectedServer = it
                startConnectionStats()
            }
        }
    }

    private fun tryNextOrFail(chain: List<Server>, attemptIndex: Int, errorIfLast: String?) {
        val nextIndex = attemptIndex + 1
        if (nextIndex < chain.size) {
            beginConnection(chain, nextIndex)
        } else {
            connectionFlowActive = false
            pendingChain = null
            connectedServer = null
            updateStatusCard()
            updateActionButton()
            val message = errorIfLast ?: "Couldn't establish a working internet connection through any nearby server."
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun onDisconnected() {
        connectedServer = null
        updateStatusCard()
        updateActionButton()
        stopConnectionStats()
        renderRows()
        NotificationHelper.clear(this)
    }

    private fun uiState(): UiState = when {
        tunnelManager.state == TunnelState.CONNECTING || connectionFlowActive -> UiState.CONNECTING
        tunnelManager.state == TunnelState.UP -> UiState.CONNECTED
        else -> UiState.DISCONNECTED
    }

    private fun updateStatusCard() {
        if (!connectionFlowActive && tunnelManager.state != TunnelState.CONNECTING) connectingServer = null
        val green = ContextCompat.getColor(this, R.color.statusOnline)
        val red = ContextCompat.getColor(this, R.color.statusOffline)
        val gray = ContextCompat.getColor(this, R.color.gray)
        val ringMuted = ContextCompat.getColor(this, R.color.ringMuted)
        val white = ContextCompat.getColor(this, R.color.white)
        val blue = ContextCompat.getColor(this, R.color.primary)
        when (uiState()) {
            UiState.CONNECTED -> {
                binding.ringView.ringState = PowerRingView.State.CONNECTED
                binding.textRingStatus.text = "CONNECTED"
                binding.textRingStatus.setTextColor(green)
                binding.imagePower.imageTintList = ColorStateList.valueOf(white)
                binding.textProtected.text = "You're Protected"
                binding.textProtected.setTextColor(green)
                binding.imageProtected.setImageResource(R.drawable.ic_shield_check)
                binding.imageProtected.imageTintList = ColorStateList.valueOf(green)
            }
            UiState.CONNECTING -> {
                binding.ringView.ringState = PowerRingView.State.CONNECTING
                binding.textRingStatus.text = "CONNECTING…"
                binding.textRingStatus.setTextColor(blue)
                binding.imagePower.imageTintList = ColorStateList.valueOf(blue)
                binding.textProtected.text = "Securing your connection…"
                binding.textProtected.setTextColor(gray)
                binding.imageProtected.setImageResource(R.drawable.ic_shield_off)
                binding.imageProtected.imageTintList = ColorStateList.valueOf(gray)
            }
            UiState.DISCONNECTED -> {
                binding.ringView.ringState = PowerRingView.State.DISCONNECTED
                binding.textRingStatus.text = "TAP TO CONNECT"
                binding.textRingStatus.setTextColor(ringMuted)
                binding.imagePower.imageTintList = ColorStateList.valueOf(ringMuted)
                binding.textProtected.text = "You're Not Protected"
                binding.textProtected.setTextColor(red)
                binding.imageProtected.setImageResource(R.drawable.ic_shield_off)
                binding.imageProtected.imageTintList = ColorStateList.valueOf(red)
            }
        }
        updateCurrentLocation()
    }

    private fun currentServer(): Server? =
        connectedServer ?: connectingServer
            ?: appSettings.lastConnectedServerId?.let { id -> allServers.find { it.id == id } }

    private fun updateCurrentLocation() {
        val server = currentServer()
        if (server == null) {
            binding.textCurrentFlag.text = "🌐"
            binding.textCurrentCountry.text = "Fastest server"
            binding.textCurrentCity.text = "Automatic"
            binding.signalCurrent.level = 0
        } else {
            binding.textCurrentFlag.text = server.flagEmoji()
            binding.textCurrentCountry.text = server.countryName.ifBlank { server.name }
            binding.textCurrentCity.text = server.city.ifBlank { server.name }
            binding.signalCurrent.level = SignalBarsView.levelForPing(server.pingMs)
        }
    }

    private fun updateActionButton() {
        val busy = uiState() == UiState.CONNECTING
        binding.powerButton.isEnabled = !busy
        binding.buttonFastest.isEnabled = !busy
        binding.buttonFastestLocations.isEnabled = !busy
        binding.powerButton.contentDescription =
            if (tunnelManager.state == TunnelState.UP) "Disconnect" else "Connect"
        updateStatusCard()
    }

    private fun startConnectionStats() {
        binding.layoutConnectionStats.visibility = View.VISIBLE
        binding.chronometerConnected.visibility = View.VISIBLE
        binding.chronometerConnected.text = "00:00:00"
        binding.chronometerConnected.base = SystemClock.elapsedRealtime()
        binding.chronometerConnected.start()
        startStatsPolling()
    }

    // Separated from startConnectionStats() so onResume can restart polling
    // after a background pause without resetting the chronometer back to 0.
    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch {
            while (true) {
                val stats = tunnelManager.statistics()
                if (stats != null) {
                    binding.textDataUsage.text =
                        "↓${formatBytes(stats.totalRx())} ↑${formatBytes(stats.totalTx())}"
                }
                delay(2000)
            }
        }
    }

    private fun stopConnectionStats() {
        binding.chronometerConnected.stop()
        binding.chronometerConnected.visibility = View.GONE
        binding.layoutConnectionStats.visibility = View.GONE
        binding.textDataUsage.text = "↓0 KB ↑0 KB"
        statsJob?.cancel()
        statsJob = null
    }

    private fun formatElapsed(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes} B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.0f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(gb)
    }

    companion object {
        /** How often the server list quietly refreshes itself while the app is open. */
        private const val SERVER_REFRESH_INTERVAL_MS = 30_000L

        /** Pass as an Int extra to open a specific tab (used by the Settings bottom bar). */
        const val EXTRA_TAB = "tab"
        const val TAB_HOME = 0
        const val TAB_LOCATIONS = 1
        private const val STATE_TAB = "state_tab"
    }
}
