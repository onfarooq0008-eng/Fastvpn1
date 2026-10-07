# Manual testing checklist -- required before relying on this in production

Some behavior here depends on real OS/OEM process-management decisions
(when Android kills a process, whether it restarts a service, how
battery-management skins like MIUI/ColorOS/EMUI treat foreground VPN
services) that cannot be verified by reading source code or running in a
sandbox. These need an actual physical device. Do not consider this app's
disconnect/notification behavior proven correct until this checklist has
been run and passed.

## Tunnel lifecycle after Activity/process death

Covers: `VpnTunnelManager`, `VpnTunnelManagerHolder`, `VpnActionReceiver`,
`NotificationHelper`. Logcat tags to filter on while testing:
`FastVPN-Tunnel` and `FastVPN-Disconnect`.

**Steps:**
1. Connect to any server from the app. Confirm the "Connected" notification
   appears with a Disconnect action.
2. Kill the Activity -- swipe FastVPN away from Recents (this is the common,
   everyday case: the underlying VPN service should keep running and the
   process should stay alive, since Android gives elevated priority to a
   process running a real foreground VPN service).
3. Open the notification shade and tap **Disconnect**.
4. **Confirm the WireGuard interface actually disappears** -- check
   `adb shell ip link` or `adb shell wg show` (if available) before and
   after step 3; the `fastvpn` interface/tunnel should be gone after.
5. **Confirm Android's own VPN state clears** -- the status bar VPN key icon
   should disappear, and Settings -> Network -> VPN should no longer show
   FastVPN as connected.
6. **Confirm the backend peer was actually removed** -- check the dashboard
   at `https://api.fastvpnn.pp.ua/` (registered device count should drop),
   or query the VPS node directly (`wg show` on the VPS itself should no
   longer list this device's public key as a peer).
7. Check logcat for the full expected sequence with no gaps:
   `onReceive: disconnect action from notification` -> `state before
   disconnect: UP` -> `disconnect: tunnel torn down` -> `backend peer
   unregistered for server ...` -> `notification cleared`.

**If any step fails:** the logcat trail from step 7 tells you which stage
broke (local teardown vs. backend unregister vs. notification) -- fix that
specific stage rather than assuming the whole flow needs a rewrite.

## Harder variant -- true process death

Repeat the above, but instead of just swiping from Recents, force the
process to die completely before testing Disconnect:
```
adb shell am kill com.fastvpnn.app
```
or, for a closer simulation of aggressive OEM battery management, disable
battery optimization exceptions for the app and let the OS kill it under
memory pressure naturally (harder to trigger on demand, but the most
realistic real-world case for aggressive OEM skins).

Expected: since the in-process wireguard-go native runtime dies with the
process, there should be no real tunnel left for `getRunningTunnelNames()`
to find on restart -- the app should just come up in a DOWN state. Confirm:
- The WireGuard interface is actually gone (step 4 above) -- if the OS
  auto-restarted the VpnService before your check, this may show
  differently than expected; note what you observe.
- The backend peer registration will be left stale in this scenario, since
  no disconnect/unregister call ever fires from a fully-dead process.
  **This is now handled server-side**: `backend/api/server.js` runs a daily
  sweep (`pruneStaleRegistrations`) that removes any registration not
  refreshed in 30+ days, calling the VPS agent's `/remove-peer` first so the
  real WireGuard peer is actually removed, not just the bookkeeping. This
  was added as part of this same fix -- verify it's actually deployed
  (`grep pruneStaleRegistrations backend/api/server.js` on the VPS, and
  check `journalctl -u fastvpn-api` a day later for a "Pruning N stale
  registration(s)" line if you have any to prune). 30 days means this
  won't reclaim a slot quickly -- if you need faster reclaim, lower
  `STALE_REGISTRATION_MAX_AGE_MS` in server.js.

## Sign-off

Do not check this off from code review alone. Run it on at least one real
device, ideally one with an aggressive OEM battery-management skin (MIUI,
ColorOS, EMUI, or similar), before considering this production-ready.

- [ ] Steps 1-7 (Activity death, foreground service survives) -- passed
- [ ] True process death variant -- observed and documented, gap
      (if any) understood and accepted or mitigated
