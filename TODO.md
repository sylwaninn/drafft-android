# TODO: android

The iPhone app's list lives in `drafft/TODO.md`, backend work in `drafft-backend/TODO.md`.

## Configuration

- [x] **RevenueCat keys.** `goog_...` keys in `config/production.properties` (project "drafft") and
      `config/staging.properties` (project "drafft staging"; the local flavor uses the staging one). Each
      project has an app "drafft (Google Play)", package `so.drafft.app`, with its service account
      validated.
- [x] **Local backend over http.** Cleartext allowed for the local flavor only
      (`app/src/local/AndroidManifest.xml`).
- [x] **First build with a JDK.** `assembleLocalDebug` and `bundleProductionRelease` build.
- [x] **Release signing.** `app/build.gradle.kts` reads `DRAFFT_KEYSTORE_FILE`, `DRAFFT_KEYSTORE_PASSWORD`,
      `DRAFFT_KEY_ALIAS`, `DRAFFT_KEY_PASSWORD` from the environment. Put them in CI secrets when CI
      builds releases.

## Google Play and purchases

- [ ] **Subscription and in-app products in Google Play Console**, same ids as the iPhone's, then import
      them in both RevenueCat projects and attach them to the same entitlement and offerings
      (`drafft_tempo`; offerings `default`, `boosts`, `super_likes`):
      - subscriptions `so.drafft.app.tempo.monthly`, `.sixmonths`, `.yearly`, base plan id `base`;
      - one-time products `so.drafft.app.boost.1`, `.5`, `.10`, `so.drafft.app.superlike.3`, `.15`, `.30`.
- [ ] **License testers** (Play Console, Settings, License testing) to buy without paying.
- [ ] **Google developer notifications** (Pub/Sub, real-time events) connected to one RevenueCat project
      (production): RevenueCat, app "drafft (Google Play)", Google Play notifications.
- [ ] **Play Console forms** before a public release: privacy policy, data safety, content rating.

## Push (FCM)

- [x] `google-services.json` for production and staging in `app/src/<flavor>/` (gitignored). The local
      flavor has none: push stays off there.
- [x] Stream: a Firebase push provider named `drafft-fcm` in the production and staging apps.
- [ ] Backend: `register_push_token` and the push sender accept FCM tokens (the iPhone sends APNs). The
      service account keys are in `~/Secrets/drafft/firebase-fcm/`.
