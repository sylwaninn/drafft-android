# Configuration

## Environment values (`config/<flavor>.properties`, committed)

Each flavor has one file of public values, read into `BuildConfig`:

| Key | What |
|---|---|
| `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY` | The Supabase project (publishable key only) |
| `REVENUECAT_API_KEY` | RevenueCat public SDK key for Google Play (`goog_...`) |
| `TURNSTILE_SITE_KEY` | Cloudflare Turnstile public site key |
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
- a release build lacking `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY` or `REVENUECAT_API_KEY` fails.

The local flavor is debug only. A new value goes in the three files and in `flavorFields`
(`app/build.gradle.kts`).

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
