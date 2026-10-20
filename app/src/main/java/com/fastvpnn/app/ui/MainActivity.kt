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
import com.fastvpnn.app.util.ConnectivityCheckUtil
import com.fastvpnn.app.util.NotificationHelper
import com.fastvpnn.app.util.PingUtil
import com.fastvpnn.app.util.ThemeUtil
import com.fastvpnn.app.util.SecureKeyStore
import com.fastvpnn.app.vpn.TunnelState
import com.fastvpnn.app.vpn.VpnTunnelManager
import com.fastvpnn.app.vpn.VpnTunnelManagerHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    // Opening the encrypted key store is slow, so it is created lazily and only ever used from IO threads.
    private val keyStore by lazy { SecureKeyStore.get(applicationContext) }
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
    // True while the tunnel is being torn down by the power button; makes rapid taps harmless.
    private var disconnectInProgress = false
    // Only one server refresh at a time, and never two within one interval (see startServerAutoRefresh).
    private val refreshMutex = Mutex()
    private var lastRefreshStartedAt = 0L
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
        tunnelManager = VpnTunnelManagerHolder.get(this)
        // Warm the key store in the background so the first Connect tap doesn't pay for it.
        lifecycleScope.launch(Dispatchers.IO) { runCatching { keyStore } }

        binding.recyclerServers.layoutManager = LinearLayoutManager(this)
        (binding.recyclerServers.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        adapter = HomeListAdapter(
            onHeaderClick = { group -> onCountryTapped(group) },
            onServerClick = { server ->
                appSettings.fastestServerMode = false // picking a specific server turns "Fastest Server" off
                showTab(Tab.HOME)
                onServerTapped(server)
            },
            onFavoriteClick = { group -> onFavoriteTapped(group) },
            onFastestClick = { onFastestSelected() }
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

        // Idempotent: first-run users reach here without FastVpnApp having started ads yet.
        AdsManager.init(application)
        AdsManager.showBanner(this, binding.adContainer)
        // Ads are required to use the app: ask until the user allows them (also after rotation / process restart).
        if (AdsConsent.needsPrompt(this)) AdsConsent.showDialog(this)

        // Pick up the fetch SplashActivity already started while its logo was
        // showing, so this first load doesn't start the network call from zero.
        loadAndPing(warmPrefetch = ServerCache.take(), onDone = {
            lifecycleScope.launch {
                // Always reconcile the real WireGuard state before deciding whether to
                // auto-connect. This prevents an Activity-startup race from launching
                // a second connection while an existing tunnel is already active.
                tunnelManager.syncStateFromBackend()
                // Not while a connect is in flight: its brand-new lease would be released under it.
                if (tunnelManager.state == TunnelState.DOWN && !connectionFlowActive) {
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

    /** Tapped the "Fastest Server" row: remember the option and connect to a random server now. */
    private fun onFastestSelected() {
        appSettings.fastestServerMode = true
        renderRows()
        showTab(Tab.HOME)
        connectToFastest()
    }

    private fun onFavoriteTapped(group: CountryGroup) {
        val set = appSettings.favoriteCountries.toMutableSet()
        if (!set.add(group.countryCode)) set.remove(group.countryCode)
        appSettings.favoriteCountries = set
        renderRows()
    }

    override fun onDestroy() {
        super.onDestroy()
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

    /** DNS and the split-tunnel app list are applied on the WireGuard interface at connect time, so
     *  changing them while already connected has no effect on the running tunnel until the
     *  connection is cycled -- see AppSettings.settingsChangePendingReconnect.
     *  Runs a real reconnect (fresh registration + tunnel, same as switching servers) rather than
     *  just toggling the flag, so the new setting is actually live afterward. */
    private fun reconnectIfDnsSettingChanged() {
        if (!appSettings.settingsChangePendingReconnect) return
        val server = connectedServer ?: return
        if (connectionFlowActive || disconnectInProgress) return
        appSettings.settingsChangePendingReconnect = false
        Toast.makeText(this, "Reconnecting to apply your new settings…", Toast.LENGTH_SHORT).show()
        connectionFlowActive = true
        updateStatusCard()
        updateActionButton()
        lifecycleScope.launch {
            if (!disconnectTunnel("Could not reconnect to apply settings")) {
                connectionFlowActive = false
                updateStatusCard()
                updateActionButton()
                return@launch
            }
            beginConnection(buildFailoverChain(server))
        }
    }

    /** Tears the tunnel down and updates the UI. On failure it shows [failureMessage], re-reads the
     *  real tunnel state so the button never lies, and returns false. */
    private suspend fun disconnectTunnel(failureMessage: String): Boolean {
        val result = tunnelManager.disconnect()
        if (result.isFailure) {
            tunnelManager.syncStateFromBackend()
            updateStatusCard()
            updateActionButton()
            Toast.makeText(
                this, "$failureMessage: ${result.exceptionOrNull()?.message ?: "unknown error"}", Toast.LENGTH_LONG
            ).show()
            return false
        }
        onDisconnected() // UI first -- releasing the backend lease below can take a network round trip
        releaseActiveRegistrationLease()
        return true
    }

    /** Keeps the server list (and its ping times) current the whole time the app is
     *  in the foreground, instead of only refreshing once on open. Waits until
     *  [SERVER_REFRESH_INTERVAL_MS] has passed since the LAST refresh started (so coming back from
     *  Settings or the initial load never causes a duplicate request), then repeats; onPause()
     *  cancels this job so it never keeps polling while backgrounded. */
    private fun startServerAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (true) {
                val wait = lastRefreshStartedAt + SERVER_REFRESH_INTERVAL_MS - SystemClock.elapsedRealtime()
                if (wait > 0) delay(wait)
                refreshServers(showSpinner = false)
                delay(1_000) // never a tight loop, even if a refresh was skipped
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
        // A silent background tick simply skips if a refresh is already running; a user-initiated
        // one (initial load / pull-to-refresh) waits its turn so the spinner always resolves.
        if (!showSpinner && refreshMutex.isLocked) return
        if (showSpinner) binding.swipeRefresh.isRefreshing = true
        try {
            refreshMutex.withLock {
                lastRefreshStartedAt = SystemClock.elapsedRealtime()
                // If Splash already started (or finished) fetching, await that instead of firing a
                // second, redundant network call -- but if that prefetch failed, fetch again now
                // rather than leaving the first screen empty until the next tick.
                val prefetched = try {
                    warmPrefetch?.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive() // our own cancellation -> propagate
                    null
                } catch (e: Exception) {
                    null
                }
                val servers = prefetched ?: serverSource.getServers()
                // Carry over ping times we already measured so already-known servers
                // don't flash back to "Checking..." on every refresh.
                val knownPings = allServers.associate { it.id to it.pingMs }
                servers.forEach { s -> knownPings[s.id]?.let { s.pingMs = it } }
                allServers = servers
                renderRows()
                val targets = servers.map { Triple(it.id, it.endpointHost, 22) }
                val results = PingUtil.pingAll(targets)
                servers.forEach { it.pingMs = results[it.id] ?: -2 }
                renderRows()
            }
        } catch (e: CancellationException) {
            throw e // the screen went away -- not an error, and no Toast on a dead Activity
        } catch (e: Exception) {
            if (showSpinner) {
                Toast.makeText(this, "Couldn't refresh servers -- showing your last list.", Toast.LENGTH_SHORT).show()
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
        // The "Fastest Server" option sits at the very top of the full list.
        if (locationFilter == LocationFilter.ALL && query.isEmpty() && groups.isNotEmpty()) rows.add(HomeRow.FastestRow)
        groups.forEach { group ->
            val expanded = expandedCountryCodes.contains(group.countryCode)
            rows.add(HomeRow.Header(group, expanded))
            if (expanded) {
                group.servers.forEach { rows.add(HomeRow.ServerRow(it)) }
            }
        }
        adapter.submit(rows, connectedId, favorites, appSettings.fastestServerMode)

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
        if (disconnectInProgress) return
        if (tunnelManager.state == TunnelState.UP) {
            disconnectFromHome()
        } else {
            connectToPreferred()
        }
    }

    private fun disconnectFromHome() {
        if (disconnectInProgress || connectionFlowActive) return
        // Do not require connectedServer here. That field belongs to the Activity and is lost when
        // the Activity is recreated, while the WireGuard tunnel can still be running. The
        // notification can disconnect successfully in exactly this situation, so the home button
        // must do the same.
        disconnectInProgress = true
        updateActionButton()
        lifecycleScope.launch {
            try {
                disconnectTunnel("Could not disconnect VPN")
            } finally {
                disconnectInProgress = false
                updateActionButton()
            }
        }
    }

    /** Power button while disconnected: reconnect to the last used location if there is
     *  one, otherwise fall back to a reachable server. */
    private fun connectToPreferred() {
        // "Fastest Server" option on: always connect to a random reachable server.
        if (appSettings.fastestServerMode) { connectToFastest(); return }
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
        if (tunnelManager.state == TunnelState.CONNECTING || connectionFlowActive || disconnectInProgress) return
        if (tunnelManager.state == TunnelState.UP) {
            if (connectedServer?.id == server.id) {
                // Tapping the server you're already on disconnects.
                disconnectFromHome()
            } else {
                // Switching servers. Claim the flow BEFORE the first suspension so a second tap
                // while the old tunnel is still coming down can't start a parallel connect.
                connectionFlowActive = true
                updateStatusCard()
                updateActionButton()
                lifecycleScope.launch {
                    if (!disconnectTunnel("Could not switch server")) {
                        connectionFlowActive = false
                        updateStatusCard()
                        updateActionButton()
                        return@launch
                    }
                    beginConnection(buildFailoverChain(server))
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
    private fun beginConnection(chain: List<Server>, attemptIndex: Int = 0) {
        if (attemptIndex >= chain.size) {
            connectionFlowActive = false
            pendingChain = null
            connectedServer = null
            updateStatusCard()
            updateActionButton()
            Toast.makeText(
                this,
                "Couldn't establish a working connection through any nearby server. Check your internet connection or try again shortly.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (attemptIndex == 0) {
            doConnect(chain, attemptIndex)
        } else {
            Toast.makeText(
                this, "${chain[attemptIndex - 1].name} didn't work, trying another server…", Toast.LENGTH_SHORT
            ).show()
            doConnect(chain, attemptIndex)
        }
    }

    /** Everything doConnect() needs out of a successful registration call, bundled as
     *  one immutable value instead of four separate nullable `var`s -- so the rest of
     *  the function can use these fields directly with no null checks or `!!`. */
    private data class Registration(
        val server: Server,
        val assignedAddressCidr: String,
        val serverId: String,
        val token: String
    )

    private suspend fun registerWithServer(server: Server): Registration {
        val publicKey = withContext(Dispatchers.IO) { keyStore.clientPublicKeyBase64() }
        val reg = serverSource.register(publicKey, preferredServerId = server.id)
        if (reg.registrationToken.isNotBlank()) {
            withContext(Dispatchers.IO) { keyStore.addPendingRegistration(reg.serverId, reg.registrationToken) }
        }
        return Registration(
            server = server.copy(
                endpointHost = reg.endpointHost,
                endpointPort = reg.endpointPort,
                serverPublicKey = reg.serverPublicKey,
                dns = reg.dns
            ),
            assignedAddressCidr = "${reg.assignedAddress}/32",
            serverId = reg.serverId,
            token = reg.registrationToken
        )
    }

    /** Split-tunnel entries for apps that have since been uninstalled would make the VPN builder
     *  reject the whole configuration (every connect would fail), so only installed ones are used. */
    @Suppress("DEPRECATION")
    private fun installedExcludedPackages(): Set<String> =
        appSettings.excludedPackages.filterTo(HashSet()) { pkg ->
            try {
                packageManager.getApplicationInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

    private fun doConnect(chain: List<Server>, attemptIndex: Int) {
        val server = chain[attemptIndex]
        connectingServer = server
        updateStatusCard()
        lifecycleScope.launch {
            var registration: Registration? = null
            try {
                val privateKey = withContext(Dispatchers.IO) { keyStore.clientPrivateKeyBase64() }

                val reg = try {
                    registerWithServer(server)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    tryNextOrFail(chain, attemptIndex, "Registration failed: ${e.message}")
                    return@launch
                }
                registration = reg

                // Apply the user's DNS preference (Settings -> DNS) on top of whatever
                // the server itself specifies -- "Server default" leaves connectServer.dns
                // untouched; any other mode substitutes the chosen resolver.
                val connectServer = reg.server.copy(dns = appSettings.resolveDns(reg.server.dns))

                val result = tunnelManager.connect(
                    connectServer,
                    privateKey,
                    excludedPackages = installedExcludedPackages(),
                    assignedAddressCidr = reg.assignedAddressCidr
                )
                if (result.isFailure) {
                    releaseRegistrationLease(reg.serverId, reg.token)
                    tryNextOrFail(chain, attemptIndex, "Connection failed: ${result.exceptionOrNull()?.message}")
                    return@launch
                }

                // The interface coming up doesn't prove it actually works -- verify
                // real traffic flows through it before declaring success to the user.
                val working = ConnectivityCheckUtil.verifyInternetThroughVpnWithRetries(this@MainActivity)
                if (!working) {
                    tunnelManager.disconnect()
                    releaseRegistrationLease(reg.serverId, reg.token)
                    tryNextOrFail(chain, attemptIndex, null)
                    return@launch
                }

                // Past this point the tunnel is verified and in use: finish the bookkeeping even if
                // the screen is being torn down, so the lease is never left half-promoted.
                withContext(NonCancellable) {
                    if (reg.token.isNotBlank()) {
                        withContext(Dispatchers.IO) { keyStore.promotePendingToActive(reg.serverId, reg.token) }
                    }
                }
                connectionFlowActive = false
                pendingChain = null
                connectedServer = server
                appSettings.lastConnectedServerId = server.id
                // This connect already used the current DNS / split-tunnel settings, so any earlier
                // pending-reconnect flag is moot -- clear it to avoid a needless follow-up reconnect.
                appSettings.settingsChangePendingReconnect = false
                updateStatusCard()
                updateActionButton()
                startConnectionStats()
                renderRows()
                NotificationHelper.showConnected(this@MainActivity, "${server.flagEmoji()} ${server.name}")
            } catch (e: CancellationException) {
                // The screen was destroyed mid-connect (e.g. rotation). Don't leave a half-open
                // tunnel and a dangling backend registration behind -- undo both, then propagate.
                withContext(NonCancellable) {
                    runCatching { tunnelManager.disconnect() }
                    registration?.let { releaseRegistrationLease(it.serverId, it.token) }
                }
                throw e
            } catch (e: Exception) {
                // Anything unexpected (key store failure, malformed config...) must end as a normal
                // failed attempt, never as an uncaught exception that kills the app.
                registration?.let { releaseRegistrationLease(it.serverId, it.token) }
                tryNextOrFail(chain, attemptIndex, "Connection failed: ${e.message}")
            }
        }
    }

    private suspend fun releaseRegistrationLease(serverId: String?, token: String?) {
        if (serverId.isNullOrBlank() || token.isNullOrBlank()) return
        val success = try {
            val publicKey = withContext(Dispatchers.IO) { keyStore.clientPublicKeyBase64() }
            serverSource.unregister(publicKey, serverId, token)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false // the lease stays stored and is retried on the next launch
        }
        if (success) {
            withContext(Dispatchers.IO) {
                keyStore.removePendingRegistration(serverId, token)
                val active = keyStore.activeRegistration()
                if (active?.serverId == serverId && active.token == token) keyStore.clearActiveRegistration()
            }
        }
    }

    private suspend fun releaseActiveRegistrationLease() {
        val lease = withContext(Dispatchers.IO) { keyStore.activeRegistration() } ?: return
        releaseRegistrationLease(lease.serverId, lease.token)
    }

    private suspend fun cleanupStaleRegistrationLeases() {
        // If the VPN is down, no registration lease should remain active.
        val (active, pending) = withContext(Dispatchers.IO) { keyStore.activeRegistration() to keyStore.pendingRegistrations() }
        active?.let { releaseRegistrationLease(it.serverId, it.token) }
        pending.forEach { releaseRegistrationLease(it.serverId, it.token) }
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
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
        if (appSettings.fastestServerMode && uiState() == UiState.DISCONNECTED) {
            binding.textCurrentFlag.text = "⚡"
            binding.textCurrentCountry.text = "Fastest Server"
            binding.textCurrentCity.text = "Auto connect (random server)"
            binding.signalCurrent.level = 4
        } else if (server == null) {
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
        val busy = uiState() == UiState.CONNECTING || disconnectInProgress
        binding.powerButton.isEnabled = !busy
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
                // The notification's Disconnect button (or the system) can drop the tunnel while this
                // screen is visible; notice it here instead of showing "Connected" until the next resume.
                if (!connectionFlowActive && !disconnectInProgress &&
                    tunnelManager.syncStateFromBackend() == TunnelState.DOWN
                ) {
                    onDisconnected()
                    return@launch
                }
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
