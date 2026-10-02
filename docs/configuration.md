# Configuration

## Environment values (`config/<flavor>.properties`, committed)

Each flavor has one file of public values, read into `BuildConfig`:

| Key | What |
|---|---|
| `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY` | The Supabase project (publishable key only) |
| `REVENUECAT_API_KEY` | RevenueCat public SDK key for Google Play (`goog_...`) |
| `TURNSTILE_SITE_KEY` | Cloudflare Turnstile public site key |
| `PLAY_INTEGRITY_PROJECT_NUMBER` | Number of the Google Cloud project linked in Play Console (App integrity), public: Play Integrity asks Google for its token with it. Empty: the device is not attested (local only) |
| `SMS_CODE_LIFETIME` | Auth's SMS OTP expiry, in seconds |
| `APP_DISPLAY_NAME` | Launcher name |
| `SENTRY_DSN` | Sentry DSN, EU region (crashes, errors, performance). Empty: off |
| `POSTHOG_API_KEY`, `POSTHOG_HOST` | PostHog project key (`phc_...`) and EU host (product analytics). Empty: off |

Checked when Gradle configures the build, before anything compiles:

- only public keys: a value that looks like a secret (`sb_secret_`, `service_role`, a private key, a Sentry auth
  token, a PostHog personal key) stops the build;
- `SUPABASE_URL` must be https, except for the local flavor;
- `POSTHOG_HOST`, when set, must be PostHog's EU cloud (`https://eu.i.posthog.com`), and `SENTRY_DSN`, when set,
  a DSN of Sentry's EU region (`ingest.de.sentry.io`);
- `PLAY_INTEGRITY_PROJECT_NUMBER`, when set, must be a number of 1 to 18 digits (the Google Cloud project's), and
  the production flavor must set it;
- a release build lacking `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY` or `REVENUECAT_API_KEY` fails.

The local flavor is debug only. A new value goes in the three files and in `flavorFields`
(`app/build.gradle.kts`).

## Device attestation (Play Integrity)

Each time the app opens signed in, and at sign-in, it asks Play Integrity for a token and sends it to the
backend's `device-check`, which has Google decode it (drafft-backend's README lists its secrets). Things to know:

- Only a build installed from Google Play (an internal testing track is enough) is recognized. A build
  installed from the IDE, including a staging one, and an emulator still get a token, which the server refuses
  without storing it; the local flavor doesn't ask at all.
- Google's default quota is 10,000 token requests a day for the Google Cloud project, which production and
  staging share. Ask Google for more (Play Console's quota request) before the daily users approach it, and
  watch the quota in Google Cloud. Beyond it every request fails with `TOO_MANY_REQUESTS`, reported to Sentry
  (area `integrity`), and nothing is attested.

## Local backend

The local Supabase's URL and key depend on the machine: `scripts/local-backend.sh` writes them from
drafft-backend's running Supabase to `local.private.properties` (gitignored; `--device` for a phone on the
same Wi-Fi). Without them the local app stops at launch and says what's missing.

## Push notifications (FCM)

Put each Firebase project's `google-services.json` in `app/src/<flavor>/` (gitignored). With no such file
anywhere, the app builds and runs with push off (`BuildConfig.HAS_PUSH` false). As soon as one exists, the
Google Services plugin applies to every flavor, so each flavor you build needs its own file. Stream needs a
Firebase push provider named `drafft-fcm`. Setup state of Google Play, Firebase
and RevenueCat: [store-setup.md](store-setup.md).

## Telemetry

Crash reporting and product analytics, their privacy rules and the tracked events: [telemetry.md](telemetry.md).
Release builds upload their R8 mapping to Sentry when `SENTRY_AUTH_TOKEN`, `SENTRY_ORG` and `SENTRY_PROJECT`
are set (CI secrets).
