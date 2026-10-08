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
- `com.facebook.android:audience-network-sdk:6.21.0` (Meta requires 6.21+)
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
