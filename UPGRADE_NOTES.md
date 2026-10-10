# FastVPN v1.0.4 -- upgrade notes

(Earlier rounds: AGP 9.0.1 / Gradle 9.1, new dark+light UI, Fastest Server button, full-green signal bars,
Play Store links, light/dark toggle, new website.)

## Ads: Meta Audience Network + Unity Ads

### What the project looked like before
Native Android, Kotlin, Gradle (Groovy DSL) with AGP 9.0.1, compileSdk/targetSdk 36, minSdk 24, JDK 17, ViewBinding.
AdMob had already been removed at your request (account not approved), so there was nothing to keep working.
The architecture below leaves room to add AdMob back as a third provider later.

### Files
New (package `com.fastvpnn.app.ads`):
- `AdConfig.kt` -- ALL ad IDs, debug/release switch, frequency caps
- `AdsManager.kt` -- the only API screens use
- `AdProvider.kt` -- provider contract, `LoadGate` (throttle), `AdLog`
- `MetaAdsProvider.kt`, `UnityAdsProvider.kt` -- one file per network
- `AdsConsent.kt` -- ad privacy choice (dialog + SDK privacy flags)
- `AppOpenAdManager.kt` -- app-open style ad (rewritten)
Removed: `AdManager.kt`, `res/values/ad_ids.xml`.
Modified: `app/build.gradle`, `FastVpnApp.kt`, `data/AppSettings.kt`, `ui/MainActivity.kt`, `ui/ConsentActivity.kt`,
`ui/SettingsActivity.kt`, `ui/HomeListAdapter.kt`, `res/layout/activity_settings.xml`, `res/layout/item_native_ad.xml`,
`backend/api/public/privacy.html`, `terms.html`, `app-ads.txt`, `README.md`.

### Dependencies added
- `com.facebook.android:audience-network-sdk:6.22.0` (newest on Maven Central, Jul 2026; Unity Ads 4.21.0 is already the newest, Oct 2026)
- `com.unity3d.ads:unity-ads:4.20.0`

### Where to enter IDs
`app/src/main/java/com/fastvpnn/app/ads/AdConfig.kt`
- Meta: `appId`, `bannerId`, `interstitialId`, `nativeId`, `nativeBannerId`, `rewardedId` (rewarded is empty = off)
- Unity: `gameId`, `bannerPlacementId`, `interstitialPlacementId`, `rewardedPlacementId` (rewarded is empty = off)
- Separate `*_DEBUG` and `*_RELEASE` blocks. Frequency: `MIN_INTERSTITIAL_INTERVAL_MS`, `MAX_INTERSTITIALS_PER_SESSION`,
  `MIN_TIME_AFTER_LAUNCH_MS`, `APP_OPEN_MIN_BACKGROUND_MS`.
Your current IDs are already filled in.

### Debug vs release
- Debug build (`assembleDebug` / running from Android Studio): test ads. Meta placements get the official
  `IMG_16_9_APP_INSTALL#` test prefix; Unity initialises with testMode = true.
- Release build: real ads. `USE_TEST_ADS` in `app/build.gradle` (release buildType) is `false`; set it to `true`
  to produce a release build that still shows only test ads.
- Meta and Unity do NOT provide separate "test placement IDs"; the test behaviour is the prefix / testMode flag above.

### How screens use it
```kotlin
AdsManager.showBanner(activity, container);  AdsManager.hideBanner()
AdsManager.preloadInterstitial();  AdsManager.isInterstitialReady()
AdsManager.showInterstitial(activity) { continueAction() }   // callback ALWAYS runs
AdsManager.preloadRewarded();  AdsManager.isRewardedReady()
AdsManager.showRewarded(activity, onReward = { grant() }, onComplete = { })
AdsManager.loadNativeAd(context, onLoaded = { handle -> handle.render(binding) })
AdsManager.showNativeBanner(activity, container)
```
Where they are used now: banner above the bottom bar (Home/Locations); interstitial only when you tap Connect while the VPN is OFF
(preloaded earlier; shown if loaded, otherwise the VPN just connects). No interstitial on Disconnect or when switching servers while connected; native ad every 4 countries in
Locations; native banner at the bottom of Settings; app-open style ad when you come back to the app.
Rewarded is implemented but not wired into any screen (no rewarded placements exist yet).

### Frequency rules (AdConfig)
No full-screen ad in the first 30 s after launch, at most one per 90 s, at most 12 per session, never while a text
field has focus, never on the splash/consent screens, never while another full-screen ad is active.
If an ad doesn't start displaying within 5 s the user's action continues.

### Testing each format
1. Debug build on a device with internet. Filter Logcat by `FastVPN-Ads` (`[ADS] ...` lines).
2. Banner: open Home; the strip appears above the bottom bar only after "banner loaded".
3. Interstitial: with the VPN off and the ad loaded (see log), tap Connect: ad, then the VPN connects. With the VPN on: no ad. Gap between connect ads = CONNECT_AD_MIN_INTERVAL_MS (0).
4. Native: open Locations with 4+ countries listed. 5. Native banner: open Settings, scroll to the bottom.
6. App open: leave the app 30+ s, return. 7. Rewarded: add a rewarded placement ID, then call `showRewarded` from a button.
8. Fallback: put an invalid Meta ID in AdConfig and confirm Unity serves instead.

### Build release
`./gradlew bundleRelease` (AAB for Play) or `./gradlew assembleRelease` (APK). Needs `keystore.properties` as before.

### Dashboard work you still have to do
- Meta Monetization Manager: app + placements exist; add a Rewarded placement if wanted; complete business verification /
  payout info; add Meta's `app-ads.txt` line to `backend/api/public/app-ads.txt`.
- Unity Dashboard: you have Game ID + banner/interstitial placements. Unity Ads has NO native format. Create a rewarded
  placement if wanted. Add Unity's `app-ads.txt` line. Test mode ignores real fill, so a release build is needed to see real ads.
- Play Console: Data safety form must declare advertising ID / ads SDK data collection; Ads declaration = "Contains ads".

### Privacy / consent
- On first launch (after Terms) a dialog asks: Allow personalised ads / Don't allow. Settings > "Ad privacy choices" reopens it.
- Allow: Meta normal + Unity userConsent=true. Don't allow: Meta Limited Data Use ("LDU") + Unity nonBehavioral;
  in EEA/UK/Switzerland (or when country can't be detected) NO ad SDK is started at all.
- Ads do not start until a choice exists. Privacy policy: Settings > Privacy Policy, and from the dialog.
- Honest limits: country detection is SIM/network/locale based; this is a first-party dialog, not an IAB TCF-certified CMP.
  Meta has no Android GDPR API (the publisher must gate Meta requests), which is what the "no SDK in EEA without consent" rule does.
  For strict GDPR/UK compliance, have this reviewed and consider a certified CMP.

### Direct fallback is NOT mediation
Order Meta -> Unity. If Meta can't load/show, Unity is tried (and vice versa), once each, no retry loops (per-format
cool-down in `LoadGate`). There is no bidding, no eCPM ranking, no shared reporting, and the waterfall order is static.
For real mediation use a mediation platform (AdMob mediation, AppLovin MAX, or Unity LevelPlay) with Meta and Unity as
adapters; `AdProvider` is the seam where such a provider would plug in.

### Notes / limitations
- Not compiled here (no network/Gradle in this environment): the first CI build is the real test. Unity code uses the
  4.19+/4.20 API (`InitializationConfiguration`, `LoadConfiguration`, ...) and is isolated in `UnityAdsProvider.kt`;
  Meta code is in `MetaAdsProvider.kt`.
- Neither network has an App Open format: the app-open ad is an interstitial shown on return from background.
- Native ads are Meta only.

## Round 5 -- ad placement update
- Connect (VPN off): preloaded interstitial shows first, then the VPN connects (never waits for an ad).
- Disconnect (user taps disconnect, VPN now off): an interstitial is loaded if needed and shown as soon as ready
  (waits up to AdConfig.DISCONNECT_AD_WAIT_MS = 6 s, only while the app is in the foreground). No ad when switching servers.
- Home page: Meta native banner directly below the "Current Location" server card; normal bottom banner now tries
  Unity first (AdConfig.BANNER_ORDER), Meta as fallback. Settings no longer has a native banner.

## Round 6
- Unity Ads SDK 4.21.0 (latest Android release; same 4.19+ API, AGP 9 compatible).
- Personalised ads are ON by default (no dialog) outside the EEA/UK/Switzerland; users can turn them off in
  Settings > Personalised ads (switch). Inside those regions (or if the country can't be detected) the consent dialog is
  still shown first, because consent is legally required there.
- The "Fastest Server" button is gone. It is now the first row of the server list ("Fastest Server - Auto connect to a random
  server"). While selected, the power button connects to a random reachable server; picking any specific server turns it off.

## Round 7
- Removed the 'Personalised ads' switch from Settings for everyone outside the EEA/UK/Switzerland. It stays visible only in those regions (consent withdrawal is a legal requirement there). To remove it everywhere, delete the `consentRegion` block in SettingsActivity.

## Round 8 -- project audit
Updated: core-ktx 1.17.0, material 1.13.0, recyclerview 1.4.0, swiperefreshlayout 1.2.0 (all checked as current stable).
Removed unused: constraintlayout, cardview, coordinatorlayout, lifecycle-livedata-ktx, gson (nothing referenced them) -> smaller APK.
Manifest: AD_ID permission (needed for ad networks to get the advertising ID on Android 13+), predictive back enabled.
ProGuard: readable crash line numbers in Play Console. Gradle: parallel + build cache, 3 GB heap.
Backend: security headers (CSP, HSTS, nosniff, frame-deny, referrer, permissions), x-powered-by off; API stays no-store,
website pages revalidate, images/logos cache for a week (was: nothing cached).
Not changed on purpose: OkHttp 4.12 -> 5.x (major version, needs its own testing), Express 4 -> 5, EncryptedSharedPreferences
(deprecated; migrating would reset stored keys), certificate pinning (needs your live cert hash).
Play Console reminders: Data safety form (advertising ID, ads SDKs), Ads declaration, upload the mapping file (automatic with AAB).

## Round 9 - real ads not showing in the signed build

- Meta Audience Network 6.21.0 -> 6.22.0. Unity Ads stays on 4.21.0 (newest; 4.20+ ships its own R8 rules for AGP 9).
- IDs checked: Meta app 1437978778491299 with banner, interstitial, native and native-banner placements; Unity game 800391481 with `BP_Banner_Android` and `BP_Interstitial_Android`. Release and debug use the same IDs; only the test switch differs. No rewarded placement exists on either network, so rewarded is off.
- Settings > About, long-press: shows "Ad status" (TEST/REAL mode, each network's init state, and the latest load results with the Meta error code and message). Works in the signed build, no adb needed.
- Meta error codes that matter: 1001 no fill, 1203 first request must come from an app admin/developer/tester, 1011 placement/format mismatch, 2000 invalid placement ID, 1012 SDK too old for new apps.
- CI already builds the signed APK and signed AAB (`assembleRelease` + `bundleRelease`) when the RELEASE_KEYSTORE_BASE64 secrets are set.

## Round 10 - ads are required to use the app

- First screen of the app asks "Allow ads". Allow starts Meta + Unity. "Don't allow" shows "Ads are required" with "Allow ads" or "Exit app".
- The dialog cannot be cancelled and returns on every launch until ads are allowed. New storage key (`ads_accepted_v1`), so existing users are asked again after updating.
- The Settings "Personalised ads" switch is hidden because ads can no longer be turned off.

## Round 11 - what the Ad status screen showed
- Real ads, both SDKs READY. Unity interstitial loaded a real ad (works). Unity banner 52100 and every Meta format 1001 = "no fill" (the networks had nothing to serve), not an app bug.
- Banners and the Home native banner now retry by themselves (60 s, 2 min, 4 min, 5 min, 5 min) when every network says no fill, so ads appear without restarting the app.
- `backend/api/public/app-ads.txt` was an empty template. Paste the real lines from the Unity and Meta dashboards (see the comments inside) and redeploy; both networks use it to decide whether to bid on the app.

## Round 12 - versions re-checked (Oct 2026) and interstitial order
- Interstitials: Meta first, Unity when Meta has no ad ready or fails to show (`AdConfig.INTERSTITIAL_ORDER`). Both are preloaded in parallel.
- App version 1.0.5 (code 5) so Play accepts a new upload.
- Already newest: Meta Audience Network 6.22.0, Unity Ads 4.21.0, appcompat 1.8.0, recyclerview 1.4.0, swiperefreshlayout 1.2.0, lifecycle 2.11.0, security-crypto 1.1.0, coroutines 1.11.0, WireGuard tunnel 1.0.20260102.
- Deliberately NOT upgraded (each needs its own tested change): core-ktx 1.18+ needs compileSdk 36.1; OkHttp 5.x changes some APIs; Material 1.14 can change the look; AGP 9.4 needs Gradle 9.6; Kotlin 2.4.10/2.4.20 are optional patch releases.
