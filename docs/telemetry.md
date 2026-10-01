# Telemetry: crash reporting (Sentry) and product analytics (PostHog)

What the app sends about how it behaves and how it's used, why, and the rules that keep it lawful
for an app that holds sensitive data. The code is in `core/data/.../telemetry/` (platform-neutral)
and `core/data/src/android/.../telemetry/` (the two SDKs).

## Two services, two jobs

| | Sentry | PostHog |
|---|---|---|
| Job | Reliability: crashes (Kotlin and native), ANRs, unexpected errors, slow requests and uploads, app start, frozen frames, the app's own logs | Product: which features are used, funnels (sign-up, first like, first match, first session, purchase), retention, experiments |
| Region | EU (`*.ingest.de.sentry.io`, enforced by the build) | EU cloud (`https://eu.i.posthog.com`, enforced by the build) |
| Legal basis | Legitimate interest: keeping the service working and safe. Already named in the privacy policy (`/privacy#data`) | Consent for linking events to the account; anonymous audience measurement otherwise (see below) |
| Who | The account's id (Supabase user id), always | The account's id only with consent (`AnalyticsConsent.GRANTED`); a random install id otherwise |
| Never | Screenshots, view hierarchy, session replay, IP address, name, email, phone, message content | Autocapture of taps, session replay, surveys, the profile's sensitive answers, anything typed |

The app talks to neither SDK directly: everything goes through `Telemetry` (an object with pluggable
engines, like `Haptics`), which applies `PrivacyGuard` and the person's consent first. Without keys
(local builds, unit tests) every call does nothing.

## Identity: who is who, within the GDPR

One pseudonymous id everywhere: the **Supabase user id** (a UUID). It is already the RevenueCat app
user id and the id in every backend table, so a Sentry issue, a PostHog person, a RevenueCat customer
and a profile row are the same person for the team, and for nobody else. No name, email or phone
number ever goes with it.

- **Sentry** gets it at every sign-in (`Telemetry.signedIn`, fed by Supabase Auth's session status).
  Support can open "every crash of this account" from a ticket. Signing out clears it.
- **PostHog** gets it only once the person agreed to usage analytics (`identify`). Before that, events
  carry a random id of this install (`personProfiles = IDENTIFIED_ONLY`: no person profile is
  created), which a sign-out renews. Funnels and retention still work per install.
- A refusal (`DENIED`) opts PostHog out entirely, persisted by the SDK. Sentry keeps working.
- Withdrawing consent resets PostHog to a fresh anonymous id: nothing that follows links back.

Why consent for PostHog: identified, per-person product analytics reads an identifier from the phone
for a purpose that isn't strictly necessary (ePrivacy article 5(3)), and the CNIL's audience
measurement exemption only covers anonymous statistics. Anonymous mode stays within that exemption as
long as the privacy policy says so and people can object.

### What the app never sends

`PrivacyGuard` checks every property, tag and log line:

- Names on its `forbidden` list are dropped whatever the event: identity and contact (`name`, `email`,
  `phone`, `birthday`, `age`), **sensitive data under GDPR article 9** (`gender`, `interested_in`,
  `orientation`, `lifestyle`, `diet`, `drinks`, `smokes`, `religion`, `health`: together they reveal
  orientation, health or beliefs), what people write or record (`bio`, `message`, `text`, `note`,
  `prompt`, `answer`), location (`latitude`, `neighborhood`...), another person (`target_id`,
  `match_id`...) and secrets.
- Values must be numbers, booleans, or short codes (`^[a-z0-9][a-z0-9_.:-]*$`): a sentence someone
  typed never passes. Enums become their lowercase name.
- Free text that must go (log lines, exception messages, breadcrumbs) is scrubbed of emails, phone
  numbers, UUIDs (another person's id), tokens, JWTs and coordinates, and URLs lose their query string.
- A dropped property is logged (so it reaches Sentry's logs). Unit tests run it in strict mode, where
  it throws.
- Discover filters send distance and the number of sports, never who someone wants to meet. Sign-up
  sends counts and yes/no (photos, sports, prompts, "answered lifestyle"), never the answers. Reports
  send the category, never the details. Profile edits send which fields changed, never their content.

## Errors: what alerts and what doesn't

`Telemetry.unexpected(error, area, action)` is called in every `catch` that swallows a failure, and in
`attempt { }`. It classifies the error (`ErrorKind`):

| Kind | Sentry event? | Example |
|---|---|---|
| `cancelled`, `offline`, `signed_out` | No (breadcrumb) | Airplane mode, a coroutine cancelled |
| `refused` | No (breadcrumb) | `daily_like_limit`, a wrong password, a wrong SMS code |
| `rate_limited`, `store_declined` | No (breadcrumb) | HTTP 429, a purchase waiting for a parent |
| `client_contract` | **Yes** | A 4xx without a code the app knows: app and server disagree |
| `server` | **Yes** | HTTP 5xx, the SMS provider failing |
| `store_unconfirmed` | **Yes** | Google Play may have charged without RevenueCat confirming |
| `unexpected` | **Yes** | Anything else |

So an alert in Sentry means something needs a fix. Every non-2xx response is also a Sentry log line
(searchable, not an issue), and every request is a span (`http.client`, `POST rest/v1/rpc/discover`)
with its status and duration. Media uploads (`media.upload`) are timed too.

The app's own `java.util.logging` loggers (`so.drafft.*`) reach Sentry through `TelemetryLogHandler`:
INFO as breadcrumbs, WARNING as logs, SEVERE as events.

## The tracking plan

All events live in `AnalyticsEvent` (one class per event). Names are `object_action`, snake_case, past
tense. Every event also carries `screen` (the screen on show), and these super properties:
`app_environment` (`production`, `staging`, `local`), `app_language`, `app_phase` (`welcome`,
`onboarding`, `main`), `is_premium`. PostHog adds the app version, OS, device model and its lifecycle
events (`Application Installed`, `Updated`, `Opened`, `Backgrounded`).

Screens (`Screen`, PostHog `$screen`): the tabs, sign-up, the gates (location, terms, hold), and every
pushed screen and sheet that matters (`profile_detail`, `chat`, `paywall`, `extras`, `edit_profile`...).
`TrackScreen(Screen.X)` at the top of a composable counts it while it's on show in the current tab.

| Area | Events |
|---|---|
| Account | `sign_up_started`, `sign_up_failed`, `email_confirmed`, `email_code_resent`, `logged_in`, `log_in_failed`, `password_reset_requested`, `password_reset_completed`, `logged_out`, `session_ended`, `account_deleted`, `account_delete_failed`, `email_changed`, `password_changed`, `data_export_requested`, `terms_accepted`, `analytics_consent_changed`, `account_held` |
| Sign-up | `onboarding_step_viewed`, `onboarding_step_completed` (with `skipped`, `seconds_on_step`), `onboarding_step_blocked`, `onboarding_resumed`, `onboarding_completed`, `onboarding_failed` |
| Phone | `phone_code_sent`, `phone_code_failed`, `phone_verified`, `phone_verification_failed` |
| Discover | `deck_loaded`, `deck_load_failed`, `deck_empty_shown`, `profile_swiped` (`like`, `pass`, `super_like`; from the deck or Likes), `swipe_refused`, `swipe_undone`, `daily_like_limit_reached`, `profile_viewed`, `filters_changed`, `boost_started`, `boost_failed` |
| Likes and matches | `likes_viewed`, `match_created` (`my_swipe`, `their_like`), `match_screen_action`, `unmatched`, `match_ended` |
| Chat | `chat_opened`, `message_sent` (kind, reply, first message, duration), `message_failed`, `message_retried`, `message_reacted`, `message_deleted`, `chat_muted`, `chat_marked_unread` |
| Sessions | `session_proposed` (sport, options), `session_countered`, `session_responded`, `session_cancelled`, `session_action_failed`, `session_added_to_calendar` |
| Purchases | `paywall_viewed` (kind, `from_screen`), `paywall_dismissed`, `products_load_failed`, `purchase_started`, `purchase_completed`, `purchase_cancelled`, `purchase_failed`, `purchase_credited` (`seconds_to_credit`), `purchases_restored`, `restore_failed`, `subscription_manage_opened` |
| Own profile | `profile_edited` (`fields`), `profile_edit_failed`, `photo_added`, `photo_removed`, `photo_moderated` (`approved`, `refused`, `in_review`), `photo_review_requested`, `profile_paused`, `selfie_verification_started`, `selfie_verification_submitted`, `selfie_verification_failed` |
| Safety | `user_blocked`, `user_unblocked`, `user_reported` (category), `report_failed` |
| Settings and system | `language_changed`, `permission_requested` (permission, result, during), `notification_setting_changed`, `push_received`, `push_opened`, `legal_doc_opened`, `support_contacted` |

Revenue is not computed on the phone: turn on RevenueCat's PostHog integration (purchases, renewals,
cancellations and refunds with their real amounts, under event names like `rc_initial_purchase_event`,
keyed by the same app user id).

### Adding an event

1. Add a class to `AnalyticsEvent` with typed parameters (enums, numbers, booleans, codes).
2. Call `Telemetry.track(...)` where the thing happened, preferably in the model (`AppModel`, a
   service) rather than a button: the model knows whether it worked.
3. Add it to the table above, and to the iPhone app with the same name and properties.
4. Never rename an event or a property: dashboards depend on them. Add a new one.

## Setup

### Keys (`config/<flavor>.properties`)

| Key | Where to find it |
|---|---|
| `SENTRY_DSN` | Sentry › Project `drafft-android` › Settings › Client Keys (DSN). EU organisation |
| `POSTHOG_API_KEY` | PostHog › Project settings › Project API key (`phc_...`) |
| `POSTHOG_HOST` | `https://eu.i.posthog.com` |

Empty values turn the service off. Production and staging share the Sentry project (the
`environment` tag separates them); PostHog uses one project per environment so tests never pollute
real numbers. The build refuses a non-EU host or DSN, and anything shaped like a secret (`sntrys_`,
`sntryu_`, `phx_`).

### Readable stack traces (CI secrets)

Release builds are minified by R8. With `SENTRY_AUTH_TOKEN` (an organization token), `SENTRY_ORG` and
`SENTRY_PROJECT` in the environment, the Sentry Gradle plugin uploads the mapping file and the source
context of each release build. Without them the build works, the mapping id is still embedded, and
the mapping can be uploaded later with `sentry-cli`.

### Sentry project settings

- Security & Privacy: Data Scrubber on, "Prevent storing of IP addresses" on.
- Alerts, at least: a new issue in `production`; an issue's frequency above its baseline; crash-free
  sessions under 99.5% for the latest release; `area:purchase` issues (money is involved) to a
  dedicated channel; the "purchase credited late" message.
- Data retention: the plan's default (90 days) is fine for crash data.

### PostHog project settings

- Region EU, "Discard client IP data" on, GeoIP enrichment kept (country and city only).
- Person profiles: identified only (also set in the SDK).
- Data retention: within the CNIL's audience measurement guidance (an identifier lives 13 months at
  most, the data 25 months at most).
- Dashboards to start with: the sign-up funnel (`onboarding_step_viewed` by `step_index`), activation
  (`onboarding_completed` → `profile_swiped` → `match_created` → `message_sent` → `session_proposed`),
  monetisation (`paywall_viewed` by `from_screen` → `purchase_completed`), retention by first week.

## What remains to do outside this repository

- **Privacy policy (drafft-web):** add PostHog (EU) to `/privacy#data` with the purpose, the anonymous
  mode, the consent for linking to the account, and how to object; Sentry is already listed.
- **Account deletion (drafft-backend, `delete-account`):** delete the PostHog person and its events
  through PostHog's persons API (found by distinct id = the user id; a personal API key kept
  server-side, never in the app). Sentry keeps events for its retention period (90 days); an erasure
  request within it is handled by deleting the issues and events matching `user.id:<id>`. Data export
  should say that analytics data can be requested too.
- **Consent screen (drafft-ios first):** a switch in You › Privacy & data ("Share usage analytics",
  off by default, with one line on what it means), and optionally a one-time question after sign-up.
  Its words go in the iPhone catalog first (WORDING.md), then `TelemetrySession.setConsent` wires it.
  Until then everyone is in anonymous mode.
- **Google Play Data safety form:** declare "App activity: app interactions", "App info and
  performance: crash logs, diagnostics", "Device or other IDs" (the install id), collected, not shared,
  processed by service providers, encrypted in transit; user ids linked to the account for crash logs.
- **iPhone app:** the same events and screens with the same names (sentry-cocoa and posthog-ios), so
  funnels cover both platforms. `Diagnostics.swift` then sends its MetricKit payloads the same way.
