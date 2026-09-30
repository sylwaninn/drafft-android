# drafft for Android

The Android app of drafft, the sports dating app where the first date is a training session. It is a
port of the iPhone app (`sylwaninn/drafft`): same features, same behaviour, same wording, same look,
in Kotlin and Jetpack Compose. Conventions for contributors and agents: [AGENTS.md](AGENTS.md).

## Build

Requirements: Android Studio (AGP 8.13), JDK 17+, Android SDK 36.

```sh
./gradlew assembleProductionDebug      # production backend
./gradlew assembleStagingDebug         # "drafft β", staging backend
./gradlew assembleLocalDebug           # "drafft local", the local Supabase of drafft-backend
```

The three flavors mirror the iPhone schemes (`Drafft`, `Drafft Staging`, `Drafft Local`). They share
the application id `so.drafft.app`, so installing one replaces the other; the launcher name tells
them apart. Only public keys live in the build (Supabase publishable keys, Turnstile site key).

### Machine-specific settings (`local.properties`, never committed)

```properties
# Local flavor: the local Supabase (drafft-backend `supabase start`). From the emulator the Mac is 10.0.2.2.
drafft.local.supabaseUrl=http://10.0.2.2:54321
drafft.local.supabaseKey=sb_publishable_...
# RevenueCat public SDK keys for Google Play (goog_...), per project.
drafft.revenuecat.production=goog_...
drafft.revenuecat.staging=goog_...
```

Without the local URL and key, the local flavor stops at launch and says what's missing.

### Push notifications (FCM)

Put each Firebase project's `google-services.json` in `app/src/<flavor>/`. Without it the app builds
and runs; push stays off. Server side, two things are needed:
- the backend's `register_push_token` and push sender must accept FCM tokens (the iPhone sends APNs);
- Stream needs a Firebase push provider named `drafft-fcm`.

## Checks that run anywhere (no Android SDK)

```sh
gradle -p tools/jvmcheck compileKotlin -q   # type-checks all platform-neutral code on the JVM
gradle -p tools/jvmcheck test               # unit tests (model, cache, cards, chat payloads, sessions...)
python3 scripts/check-strings.py            # every L("...") key exists in the string catalog
```

`tools/jvmcheck` compiles `src/main/kotlin` of every module against Compose Multiplatform. Code that
needs the Android SDK lives in each module's `src/android/kotlin`; see AGENTS.md.

## Strings

All text comes from the iPhone app's catalog (`Localizable.xcstrings`), shared key for key, in the
seven languages. After a wording change on the iPhone side:

```sh
python3 scripts/sync-strings.py ../drafft/Drafft/Resources/Localizable.xcstrings
```

The few sentences the iPhone writes about its own platform (App Store, Apple Account, iPhone Settings)
have an Android wording, in the 7 languages, under the same keys in
`core/model/src/main/resources/i18n/android/` (Google Play, Google account, the phone's settings).
`L` reads them first. When such a sentence changes on the iPhone side, update its Android variant too
(the test `LocalizationTest` checks every variant still matches a catalog key).

## Layout

```
core/model   data types and standalone logic (pure Kotlin)
core/data    AppModel, Supabase, Stream chat, RevenueCat, caches, platform services
core/ui      design system (DESIGN.md), sheets, blur, navigation, photos
app          screens (feature/<area>), RootView, MainTabs, MainActivity
tools/jvmcheck, scripts   checks and catalog sync
```
