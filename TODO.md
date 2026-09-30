# TODO: android

The iPhone app's list lives in `drafft/TODO.md`, backend work in `drafft-backend/TODO.md`.

## Configuration

- [ ] **RevenueCat keys.** Paste the Google Play public SDK keys (`goog_...`) into `REVENUECAT_API_KEY` in
      `config/production.properties` (project "drafft") and `config/staging.properties` (project "drafft
      staging"). They're public and committed, like the iPhone's `appl_` keys. Until then purchases stay
      off and the release builds of both environments fail.
- [ ] **Local backend over http.** The local flavor reaches `http://10.0.2.2:54321` (or the Mac's Wi-Fi
      address), but no network security config allows cleartext: with targetSdk 36, OkHttp refuses it.
      Allow cleartext for the local flavor only (a manifest or network security config in `app/src/local/`).
- [ ] **First build with a JDK.** Check `app/build.gradle.kts` on a real build: `config/<flavor>.properties`
      loading, `localRelease` disabled (`beforeVariants` with `withFlavor("env" to "local")`), the release
      check (`onVariants`) failing on a missing value.
- [ ] **Release signing.** No `signingConfigs` yet: keystore (gitignored) and its passwords kept out of
      the repository (CI secrets).

## Push (FCM)

- [ ] `google-services.json` per Firebase project in `app/src/<flavor>/` (gitignored). Without it push
      stays off.
- [ ] Backend: `register_push_token` and the push sender accept FCM tokens (the iPhone sends APNs).
- [ ] Stream: a Firebase push provider named `drafft-fcm`.
