# Telemetry: crash reporting (Sentry) and product analytics (PostHog)

What the app sends about how it behaves and how it's used, why, and the rules that keep it lawful
for an app that holds sensitive data. The code is in `core/data/.../telemetry/` (platform-neutral)
and `core/data/src/android/.../telemetry/` (the two SDKs).

## Two services, two jobs

| | Sentry | PostHog |
|---|---|---|
| Job | Reliability: crashes (Kotlin and native), ANRs, unexpected errors, slow requests and uploads, app start, frozen frames, the app's own logs | Product: which features are used, funnels (sign-up, first like, first match, first session, purchase), retention, experiments |
| Region | EU (`*.ingest.de.sentry.io`, enforced by the app and the build) | EU cloud (`https://eu.i.posthog.com`, enforced by the app and the build) |
| Legal basis | Legitimate interest: keeping the service working and safe. Already named in the privacy policy (`/privacy#data`) | Consent for linking events to the account; anonymous audience measurement otherwise (see below) |
| Who | The account's id (Supabase user id), always | The account's id only with consent (`AnalyticsConsent.GRANTED`); a random install id otherwise |
| Never | Screenshots, view hierarchy, session replay, IP address, name, email, phone, message content, other services' URLs | Autocapture of taps, screen autocapture, session replay, surveys, error tracking, the profile's sensitive answers, anything typed |

The app talks to neither SDK directly: everything goes through `Telemetry` (an object with pluggable
engines, like `Haptics`), which applies `PrivacyGuard` and the person's consent first. Without keys
(builds without keys, unit tests) every call does nothing. `Telemetry` never throws into the app: an engine
that fails, or a bug in it, is logged on the phone only and swallowed (unit tests, in strict mode, get
the exception back).

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
- A refusal (`DENIED`) opts PostHog out entirely, persisted by the SDK, and Sentry gets no usage
  either: no product event and no screen as a breadcrumb. Sentry keeps its crash and error reports,
  which carry no usage (their own error breadcrumbs and logs only).
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
- Values must be numbers, booleans, or short codes (`^[a-z0-9][a-z0-9_.:-]{0,79}$`: 80 characters at
  most, `_ . : -` the only punctuation): a sentence someone typed never passes, nor a value that is a
  UUID (another person's id) or only digits, seven or more (a phone number). A list holds 20 codes at
  most. Enums become their lowercase name.
- Free text that must go (log lines, exception messages, breadcrumbs) is scrubbed of emails, phone
  numbers, UUIDs (another person's id), tokens, JWTs and coordinates. Request labels and breadcrumb
  URLs also lose their query string (`PrivacyGuard.path`); `scrub` alone doesn't strip it.
- A dropped property is logged (so it reaches Sentry's logs). Unit tests run it in strict mode, where
  it throws.
- Discover filters send distance and the number of sports, never who someone wants to meet. Sign-up
  sends counts and yes/no (photos, sports, prompts, "answered lifestyle"), never the answers. Reports
  send the category, never the details. Profile edits send which fields changed, never their content.
- PostHog's `beforeSend` drops the property names on the `forbidden` list; Sentry's `beforeSend`,
  `beforeBreadcrumb` and logs' `beforeSend` scrub the message text, the exceptions' messages, the
  breadcrumbs and the user's fields. They run on the way out as a second net, but they don't check the
  shape of values (a code or a sentence): that is `PrivacyGuard`'s job when the value is set.

## Errors: what alerts and what doesn't

`Telemetry.unexpected(error, area, action)` is called in a `catch` that swallows or rethrows a
failure the app didn't expect. Best-effort upkeep written `attempt { }` (a failure gives null: realtime
joins, wallet and block-list reads that run again) is not reported, unless a call asks for it with its
own area and action. It classifies the error (`ErrorKind`):

| Kind | Sentry issue? | Example |
|---|---|---|
| `cancelled` | No (nothing at all) | A coroutine cancelled, a call the HTTP client cancelled |
| `offline`, `signed_out` | No (breadcrumb) | Airplane mode, a timeout, an expired token |
| `refused` | No (breadcrumb) | `daily_like_limit`, a wrong password, a wrong SMS code |
| `rate_limited`, `store_declined` | No (breadcrumb) | HTTP 429, a purchase waiting for a parent |
| `client_contract` | **Yes** | A 4xx without a code the app knows: app and server disagree |
| `server` | **Yes** | HTTP 5xx, the SMS provider failing |
| `store_unconfirmed` | **Yes** | Google Play may have charged without RevenueCat confirming |
| `unexpected` | **Yes** | Anything else |

So an issue in Sentry means something needs a fix. A 4xx with a one-word code (`not_found`,
`already_swiped`) is a refusal the server meant, even when the app has no words for it; a 401 is the
session's business. Every non-2xx response is also a Sentry log line (searchable, not an issue).
RevenueCat and Stream unreachable count as offline, and so does a timeout; a TLS or certificate
failure doesn't (`unexpected`). The same failure (area, action, kind) is one Sentry issue per 5
minutes, the rest are breadcrumbs, so a retry loop can't flood; the error's kind is also a tag and an
extra (`error_kind`), next to the caller's own extras. A cancelled task is never a failure:
`Telemetry.track` drops any event whose `reason` is `cancelled`.

Performance: every request is a span (`http.client`, `POST rest/v1/rpc/discover`) with its status and
duration, a child of the running trace (app start, screen load) or a trace of its own. Lone requests are
the most frequent traces, so production keeps 2% of them and 20% of the others (app starts, uploads);
staging keeps everything. Media uploads (`media.upload`) are timed too. A span ends as its
outcome says: cancelled is not a failure, a 4xx the server meant takes the response's status (not found,
resource exhausted...), only an exception or a 5xx is an internal error. Ids in a span's name are
scrubbed. Profiles follow 5% of the sampled traces in production (`ProfileLifecycle.TRACE`).

The app's own `java.util.logging` loggers (`so.drafft.*`) reach Sentry through `TelemetryLogHandler`, by
level: INFO is a breadcrumb (it comes with the next error report), WARNING is also a Sentry log line
(searchable, never an issue), SEVERE is a Sentry issue (something that should never happen, like a
backend that isn't deployed). Lower levels stay on the phone.

System exit diagnostics: crashes and ANRs reach Sentry on their own;
`Diagnostics` keeps why past runs ended (`ApplicationExitInfo`) on the phone and sends their daily
summary as a Sentry log line. PostHog's queue is sent when the app leaves the front.

## Tracked events

All events live in `AnalyticsEvent` (one class per event). Names are `object_action`, snake_case, past
tense. Every event also carries `screen` (the screen on show), and these super properties:
`app_environment` (`production`, `staging`, or `unknown` for a build whose environment is missing or invalid:
never production; also stamped on every event on its way out, PostHog's own `$` events included, so the first
lifecycle events can't miss it), `app_language`, `app_phase` (`welcome`,
`onboarding`, `main`), `is_premium`. PostHog adds the app version, OS, device model and its lifecycle
events (`Application Installed`, `Updated`, `Opened`, `Backgrounded`).

Screens (`Screen`, PostHog `$screen`): the tabs, sign-up and the welcome screen (set by `RootView` from
the phase and the tab; it stays on Discover while the tabs are built invisibly under the splash), the
gates (location, terms, hold), and every pushed screen and sheet that matters (`profile_detail`, `chat`,
`paywall`, `extras`, `edit_profile`...). `TrackScreen(Screen.X)` at the top of a composable counts it
while it's on show in the current tab (not while the tabs are hidden); `TrackPaywall(kind)` also sends
`paywall_viewed` (with `from_screen`) and `paywall_dismissed` (with `purchased`).

| Area | Events |
|---|---|
| Account | `account_created`, `sign_up_failed`, `email_confirmed`, `email_code_resent`, `logged_in`, `log_in_failed`, `password_reset_requested`, `password_reset_completed`, `logged_out`, `session_ended`, `account_deleted`, `account_delete_failed`, `email_changed`, `password_changed`, `data_export_requested`, `terms_accepted`, `analytics_consent_changed`, `account_held` |
| Sign-up | `onboarding_step_viewed`, `onboarding_step_completed` (with `skipped`, `seconds_on_step`), `onboarding_step_blocked`, `onboarding_resumed`, `onboarding_completed`, `onboarding_failed` |
| Phone | `phone_code_sent`, `phone_code_failed`, `phone_verified`, `phone_verification_failed` |
| Discover | `deck_loaded`, `deck_load_failed`, `deck_empty_shown`, `profile_swiped` (`like`, `pass`, `super_like`; from the deck or Likes), `swipe_refused`, `swipe_undone`, `daily_like_limit_reached`, `profile_viewed`, `filters_changed`, `boost_started`, `boost_failed`, `voice_intro_played` (`where`: the screen, when a tap starts playback, not for chat voice messages), `icebreaker_answered` (once per card, not on the person's own profile) |
| Likes and matches | `likes_viewed`, `match_created` (`my_swipe`, `their_like`), `match_screen_action`, `unmatched`, `match_ended` |
| Chat | `chat_opened`, `message_sent` (kind, reply, first message, duration), `message_failed`, `message_retried`, `message_reacted`, `message_deleted`, `chat_muted`, `chat_marked_unread` |
| Sessions | `session_proposed` (sport, options), `session_countered`, `session_responded`, `session_cancelled`, `session_action_failed`, `session_added_to_calendar` |
| Purchases | `paywall_viewed` (kind, `from_screen`), `paywall_dismissed`, `products_load_failed`, `purchase_started`, `purchase_completed`, `purchase_cancelled`, `purchase_failed`, `purchase_credited` (`seconds_to_credit`), `purchases_restored`, `restore_failed`, `subscription_manage_opened` |
| Own profile | `profile_edited` (`fields`), `profile_edit_failed`, `photo_upload_started` (`retry`), `photo_upload_failed`, `photo_removed`, `photo_moderated` (`approved`, `refused`, `in_review`), `photo_review_requested`, `voice_intro_recorded` (`duration_seconds`, `where`), `profile_paused`, `selfie_verification_started`, `selfie_verification_submitted`, `selfie_verification_failed` |
| Safety | `user_blocked`, `user_unblocked`, `user_reported` (category), `report_failed` |
| Settings and system | `language_changed`, `permission_requested` (permission, result, during; sent when the system asked or the person is blocked, never for a permission already granted), `notification_setting_changed`, `push_received` (only while the app is on screen, so `in_foreground` is always true), `push_opened` (sent once the tap is followed, the tabs on screen: `kind` `like`, `super_like`, `match`, `new_message`, `reaction`, `session` (proposed, accepted, declined), `session_cancelled`, `session_reminder`, `photo_refused`, `moderation`, `weekly_boost`, `local`, `unknown`; `routed`, true only when the tap reached its own destination; false when its chat wasn't found after loading, a chat push had no usable chat id (the list shows), an unknown kind named no chat or tab (Discover), the tap waited over ten minutes, was replaced by a newer one, or its account signed out or changed), `legal_doc_opened`, `support_contacted`, `share_tapped` (`what`: `photo` or `video`, from the media viewer) |

Events fire when the person acts (the screen answers at once, the server confirms after), and a
`*_failed` event follows when it didn't work: success counts are the events minus their failures.

Revenue is not computed on the phone: turn on RevenueCat's PostHog integration (purchases, renewals,
cancellations and refunds with their real amounts, under event names like `rc_initial_purchase_event`,
keyed by the same app user id).

## Alerts (what pages someone, and where it is set)

Keep this list true: a new flow that costs money, accounts or safety adds its line here.

| Where | Alert | Why |
|---|---|---|
| Sentry | New issue in `environment:production` | Something needs a fix |
| Sentry | An issue's frequency above its baseline (production) | A bad release or an outage |
| Sentry | Crash-free sessions under 99.5% for the latest release | Release health |
| Sentry | `area:purchase` issues, to a dedicated channel; the "purchase credited late" message | Money is involved |
| PostHog | `purchase_failed`, `sign_up_failed`, `log_in_failed` trend up (`app_environment = production`) | Funnel breaks that raise no exception |
| PostHog | `onboarding_completed` per day falls (`app_environment = production`) | Sign-up is broken or traffic fell |
| PostHog | `push_opened` with `routed = false` above 5% of `push_opened`, by `kind` (`app_environment = production`) | Taps that miss their destination: a chat push without a usable chat id, an unknown kind (an app too old for it), a match that doesn't load, or a tap that expired |

Set in Sentry's and PostHog's interfaces (or through the PostHog MCP); alerts and insights always filter on
production.

### Adding an event

1. Add a class to `AnalyticsEvent` with typed parameters (enums, numbers, booleans, codes), and a sample
   of it to the catalog test `everyEventPassesThePrivacyGuardUntouched` (it fails when an event has
   none).
2. Call `Telemetry.track(...)` where the thing happened, preferably in the model (`AppModel`, a
   service) rather than a button: the model knows whether it worked.
3. Add it to the table above, and to drafft-ios with the same name and properties.
4. Never rename an event or a property: dashboards depend on them. Add a new one.

## Setup

### Keys (`config/<flavor>.properties`)

| Key | Where to find it |
|---|---|
| `SENTRY_DSN` | Sentry › Project `drafft-android` › Settings › Client Keys (DSN). EU organisation |
| `POSTHOG_API_KEY` | PostHog › Project settings › Project API key (`phc_...`) |
| `POSTHOG_HOST` | `https://eu.i.posthog.com` |

Empty values turn the service off. Production and staging share the Sentry project (the
`environment` tag separates them) and the PostHog project too, with drafft-ios and the website:
filter every insight, funnel, alert and experiment on `app_environment = production`. Staging events count in the same quota. The app and the build both refuse a non-EU host (anything but exactly
`https://eu.i.posthog.com`) or DSN (anything but `https://<key>@o<org>.ingest.de.sentry.io/<project>`),
and the build refuses anything shaped like a secret (`sntrys_`, `sntryu_`, `phx_`).

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
- Data retention: Sentry's default (90 days) is fine for crash data.

### PostHog project settings

- Region EU, "Discard client IP data" on, GeoIP enrichment kept (country and city only).
- Person profiles: identified only (also set in the SDK).
- Feature flags are not preloaded (each preload is a billed request): turn `preloadFeatureFlags` on
  with the first experiment.
- Data retention: not a setting. PostHog Cloud keeps events 1 year on the free tier and 7 years on a paid
  tier, and a shorter period isn't available. The CNIL's audience measurement guidance (an identifier
  lives 13 months at most, the data 25 months at most) holds on the free tier; on a paid tier, delete the
  older data (persons or batch deletion) or change the privacy policy first.
- Dashboards to start with: the sign-up funnel (`onboarding_step_viewed` by `step_index`), activation
  (`onboarding_completed` → `profile_swiped` → `match_created` → `message_sent` → `session_proposed`),
  monetisation (`paywall_viewed` by `from_screen` → `purchase_completed`), retention by first week.

## What remains to do

### Here

- **Consent switch:** a switch in You › Privacy & data ("Share usage analytics", off by default, with one
  line on what it means), and optionally a one-time question after sign-up, wired to
  `TelemetrySession.setConsent`. Until then everyone is in anonymous mode.

### Elsewhere

- **The switch's words (drafft-ios):** written in the shared catalog first (WORDING.md), then synced here.
  drafft-ios gets the same switch.
- **Privacy policy (drafft-web):** add PostHog (EU) to `/privacy#data` with the purpose, the anonymous
  mode, the consent for linking to the account, and how to object; Sentry is already listed.
- **Account deletion (drafft-backend, `delete-account`):** delete the PostHog person and its events
  through PostHog's persons API (found by distinct id = the user id; a personal API key kept
  server-side, never in the app). Sentry keeps events for its retention period (90 days); an erasure
  request within it is handled by deleting the issues and events matching `user.id:<id>`. Data export
  should say that analytics data can be requested too.
- **Google Play Data safety form:** declare "App activity: app interactions", "App info and
  performance: crash logs, diagnostics", "Device or other IDs" (the install id), collected, not shared,
  processed by service providers, encrypted in transit; user ids linked to the account for crash logs.
