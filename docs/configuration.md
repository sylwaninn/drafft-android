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

Only public keys: the build refuses anything that looks like a secret, and a production or staging URL that
isn't https. A release build lacking a Supabase or RevenueCat value fails. The local flavor is debug only.

## Local backend

The local Supabase's URL and key depend on the machine: `scripts/local-backend.sh` writes them from
drafft-backend's running Supabase to `local.private.properties` (gitignored; `--device` for a phone on the
same Wi-Fi). Without them the local app stops at launch and says what's missing.

## Push notifications (FCM)

Put each Firebase project's `google-services.json` in `app/src/<flavor>/`. Without it the app builds and runs;
push stays off. Stream needs a Firebase push provider named `drafft-fcm`. Setup state of Google Play, Firebase
and RevenueCat: [store-setup.md](store-setup.md).

## Telemetry

Crash reporting and product analytics, their privacy rules and the tracked events: [telemetry.md](telemetry.md).
Release builds upload their R8 mapping to Sentry when `SENTRY_AUTH_TOKEN`, `SENTRY_ORG` and `SENTRY_PROJECT`
are set (CI secrets).
