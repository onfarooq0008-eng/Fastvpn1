# FastVPN — Android VPN app (WireGuard, self-hosted servers)

A lightweight VPN client for Android that connects to WireGuard servers you run
yourself, with:

- **Servers grouped by country**, expandable inline — tap "United Kingdom" and
  its servers unfold right there in the list (no separate screen); a country
  with just one server connects directly
- **Automatic device registration** via a backend API baked into the app
  itself — every user who installs the app gets your servers automatically,
  zero setup on their end. See `/backend`.
- **100% free, ad-supported** — no paywall, monetized entirely through AdMob
- **Search**, **dark mode**, **split tunneling**, a **connection timer + data
  usage** counter, and a **persistent notification with a one-tap Disconnect
  button** while connected
- The home screen's action button doubles as **"⚡ Fastest" when idle and
  "Disconnect" when connected**
- Reopening the app after backgrounding it correctly shows "Connected" if the
  tunnel is still up, instead of losing track of the real state
- Built on the **official WireGuard Android library**
  (`com.wireguard.android:tunnel`) — the same one used by the real WireGuard app

---

## 1. Open & build

1. Install **Android Studio** (Koala or newer) — or use GitHub Codespaces
   from your phone if you don't have a PC.
2. `File > Open` → select the `FastVPN` folder. Let Gradle sync.
3. Run on a device/emulator with Play Store services (needed for AdMob).

The CI workflow (`.github/workflows/build.yml`) pins exact Gradle/Kotlin/AGP
versions known to work together — don't bump one without checking the others
stay compatible.

---

## 2. Set up your VPS servers

On **each** VPS you want to add (no limit to how many):

```bash
scp server-setup/setup-wireguard.sh root@your-vps-ip:~
ssh root@your-vps-ip
sudo bash setup-wireguard.sh
```

Prints the VPS IP, port, and server public key. Give two servers the same
2-letter country code and they group together automatically on the home
screen. **Or**, for automatic registration with zero manual work per VPS, use
`/backend/setup.sh` instead — see section 3.

---

## 3. Registering users — two modes

### Mode A: Manual

The old client-side hash-based address assignment has been removed. It could
collide between users and was not authoritative for the WireGuard server.
Production builds therefore require the automatic backend registration flow
below, which atomically allocates each device a unique tunnel address.

### Mode B: Automatic backend (recommended — this is what's baked into the app right now)

See **`/backend`** — one script, two modes:

```bash
sudo bash backend/setup.sh --role api                       # once, your "brain"
sudo bash backend/setup.sh --role node --api-url ... --admin-key ... --country "..." --country-code XX
```

Each VPS sets up WireGuard, installs its agent, and registers itself with the
brain automatically. **The app's `DEFAULT_BACKEND_API_URL` (in
`app/build.gradle`) is already set to your brain's address** — every install
of the app uses it automatically, no per-device setup needed by anyone.

---

## 4. AdMob

1. Create an AdMob account, add your app, get your **App ID** and ad unit IDs.
2. Replace the placeholder App ID in `AndroidManifest.xml` and the two ad
   unit IDs in `ads/AdManager.kt`.
3. **Never ship test IDs to production.**

---

## 5. HTTPS — read this before you publish

For production, put the control API behind HTTPS and configure the app with the HTTPS URL. Plain `http://` is only appropriate for controlled testing.

Your backend URL is currently plain `http://`. That's fine for testing (debug
builds allow cleartext — see `app/src/debug/`), and release builds have one
narrow exception carved out just for your brain VPS's IP
(`app/src/main/res/xml/network_security_config.xml`) so testing a release
build doesn't break either. **But**:

- Anyone on the network path between a user and your brain VPS can see (and
  in principle tamper with) this traffic, including the device public keys
  being registered.
- Play Store expects HTTPS for a real backend.

**Fix (free, ~15 minutes once you have a domain):**
1. Buy a domain name (~$10/year from Namecheap, Porkbun, etc.) if you don't
   have one.
2. Point an A record at your brain VPS's IP.
3. Install [Caddy](https://caddyserver.com) on the brain VPS and give it a
   ~4-line config — it gets a real certificate automatically and keeps it
   renewed forever, no manual work.
4. Update `DEFAULT_BACKEND_API_URL` to `https://your-domain.com`.
5. Delete `app/src/main/res/xml/network_security_config.xml` and remove the
   `android:networkSecurityConfig` line from `AndroidManifest.xml` — the
   cleartext exception is no longer needed once you're on HTTPS.

Tell me when you're ready and I'll write the exact Caddy config for your domain.

---

## 6. Monetization

No paywall, no subscriptions — every server is free. AdMob banner + interstitial only.

---

## 7. Kill switch & Split tunneling

**Kill switch**: Android only lets the *user* (not the app) enable true
network lockdown. Settings has a toggle plus a one-tap deep link to the
exact system screen (Settings → Network → VPN → this app → "Block
connections without VPN").

**Split tunneling**: Settings → pick apps that bypass the VPN. Uses a
`<queries>` manifest declaration (not the heavily-scrutinized
`QUERY_ALL_PACKAGES` permission) to list installed apps.

---

## 8. Notification & background behavior

While connected, a persistent notification shows the server and a
**Disconnect** button — tapping it disconnects even if the app isn't open,
via `VpnActionReceiver`. Reopening the app after backgrounding it queries
Android's own record of whether a VPN is active (`VpnStateUtil`) and resyncs
the UI to match reality, rather than assuming "not connected" just because
the app process was recreated.

---

## 9. Before publishing to Play Store

- [ ] **Sign your release build.** Generate a keystore (from your Codespace
      terminal, since keytool ships with the JDK already installed there):
      ```bash
      keytool -genkey -v -keystore fastvpn-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias fastvpn
      ```
      Copy `keystore.properties.example` to `keystore.properties`, fill in
      the real values (never commit this file — it's gitignored already).
      Then `gradle assembleRelease` (or add a release job to the CI workflow —
      ask me when you're ready and I'll set that up with GitHub Secrets so
      your keystore password never touches the repo).
- [ ] **HTTPS backend** — see section 5.
- [ ] **Real AdMob IDs** — see section 4.
- [ ] **Privacy Policy**, linked in Play Console → App content. Any VPN app
      needs one. Ask me and I'll help draft one based on what this app
      actually collects.
- [ ] **Data Safety form** in Play Console, filled out accurately.
- [ ] **Real app icon** (replace the placeholder in `res/mipmap-anydpi-v26`).
- [ ] **Google Play Developer account** (play.google.com/console, one-time $25).
- [ ] Confirm your VPS provider's ToS allows running VPN exit nodes.
- [ ] Prominent Disclosure (the "app wants to set up a VPN" dialog) is
      already handled automatically by `VpnService.prepare()` — nothing to do.

---

## 10. Project structure

```
FastVPN/
  app/src/main/java/com/fastvpn/app/
    data/         Server model, local repo, ServerSource (local vs backend),
                   BackendApiClient, app settings
    vpn/          WireGuard tunnel manager, notification action receiver
    ui/           Home screen (inline-expandable country list + search),
                   split tunneling picker, settings, splash
    admin/        Admin login, panel (list/CRUD + backend URL), add/edit
                   server form
    ads/          AdMob banner + interstitial manager
    util/         Ping, secure on-device keystore, per-device tunnel address
                   derivation, notification helper, system VPN state check
  app/src/debug/  Debug-only cleartext HTTP config for local backend testing
  server-setup/   Mode A: manual per-VPS WireGuard setup + registration
  backend/        Mode B: one-script automatic backend (agent + control API)
  keystore.properties.example   copy to keystore.properties for release signing
```

Note: the AndroidManifest declares `com.wireguard.android.backend.GoBackend$VpnService`
as the VPN service component — required by the WireGuard library itself, don't
rename or remove it.

## 11. What you still need to fill in yourself

| Item | Where |
|---|---|
| AdMob App ID + ad unit IDs | `AndroidManifest.xml`, `ads/AdManager.kt` |
| App icon / branding | `res/mipmap-anydpi-v26`, `res/drawable` |
| HTTPS for your backend | See section 5 |
| Signing keystore | See section 9 |
| Privacy policy | Play Console + ask me to help draft it |
