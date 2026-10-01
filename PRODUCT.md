# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Kotlin and Jetpack Compose, Android 10 minimum (API 29), Gradle with a version catalog. A port of the iPhone app (repository `drafft`, SwiftUI): same features, behaviour, wording and look; when they disagree, the iPhone app is right. Third-party dependencies allowed (Gradle) when proven robust and scalable. Backend: Supabase (drafft-backend), chat on Stream, purchases on RevenueCat; Discover and chats still show sample people until they're wired to it.

## Users

Active urban adults, 25–40, who train 2–5 times a week (run clubs, gyms, padel, climbing, cycling, swimming). They are single, time-poor, and already structure their week around sessions. Their job: meet someone compatible without spending evenings on dead-end chat; sport is both a filter and a shared language.

## Product Purpose

drafft is a dating app for people who train. Its distinctive mechanism: a match invites you to propose a session together (sport, day, time, a short note). A match is not an invitation to chat forever. Success = proposed sessions that actually happen within days.

## Positioning

Where swipe apps end at "it's a match", Drafft continues to "Tuesday 7am, 8 km, Canal Saint-Martin". Profiles lead with how someone moves (sports, weekly rhythm, preferred slots), not just photos. Reference in the category: bpm.so.

## Operating Context

Used on the go: between sessions, after a workout, on transit. One-handed, often outdoors in daylight. Chat is the core loop and must feel instant: text, photos, video, attachments, voice messages.

## Capabilities and Constraints

- Sign up / sign in: email + password credentials (Sign in with Apple and Google to come).
- Profile: photos, sports with level, a voice intro, and an interactive icebreaker ("joke"/prompt) that the viewer can react to.
- Discovery of profiles, matching, proposing a session.
- Messaging: text, photo, video, file attachment, voice message; must feel ultra-responsive (optimistic sends, instant feedback).
- Location: required (while using the app), approximate permission only, blurred to about 1 km on the phone before it's sent; others see an area, never an address. Like the iPhone, where the system picks the source, a reading must come whatever the phone's setup: Google Location Accuracy off (no Wi-Fi or cell location, only GPS), or no Google Play services (only Android's own providers). So `AndroidLocationProvider` uses a reading under 2 minutes old, otherwise asks every source at once (fused at balanced and high accuracy, system network and GPS) and takes the first answer, gives up after 20 s, and then falls back to a reading under 30 minutes old. The area name comes from the server (`area_at`, official boundaries of France, the same on every app); offline or outside France, the app's own resolver answers: arrondissement centres in Paris, Lyon and Marseille, the system geocoder elsewhere (often missing without Google Play services). Known limit: with the phone's location switched off, the request fails at once, with no prompt to turn it on.
- Accounts, profiles and moderation run on the backend; Discover and chats still show sample people until they're wired to it. No demo mode or test shortcut ships in the app.
- UI languages: English (source), French, Spanish, German, Italian, European Portuguese, Dutch. Picked in the app (sign-up, You › Language), not from the phone. Strings come from the iPhone app's catalog (`Localizable.xcstrings`), copied by `scripts/sync-strings.py` into `core/model/src/main/resources/i18n/`; the Android wording of the iPhone's platform sentences (App Store, iPhone Settings) lives in `i18n/android/`. Text goes through `L("…")`.

## Brand Commitments

- Name: drafft, always lowercase (app name included), set one weight heavier than the sentence around it. Paid tier: drafft tempo, both lowercase, "tempo" in the accent colour.
- Visual system pinned by the user: DESIGN.md (Wise-inspired: one accent, near-black ink, heavy 900 display, 24pt radius). The accent is violet `#7D70FD` for now (lime `#9fe870` was the original); the page tone follows it (a cool grey touched with violet, `#EDECF2`, with violet). Binding.

## Evidence on Hand

No real users, testimonials, photos, or metrics. All people, photos, and conversations in the demo are synthetic placeholders and must be replaced before any public use. Do not invent user counts, match rates, or press.

## Product Principles

1. Move, then talk: every flow nudges toward a real shared session.
2. Profiles show how someone lives, not only how they look.
3. Chat is instant: no spinner between tap and feedback.
4. Low-pressure first contact: icebreakers do the awkward part.
5. The iPhone app's look and behaviour first, Android conventions where the platform expects them (system back, notification permission, Google Play billing); brand lives in color, type, and motion.
6. Never leave people guessing what to do next: the validate action is always on screen, disabled until it can run (the screen itself says what's missing, not a line under the button).
7. Always live: whatever the server changes (balance, holds, photo decisions, likes, matches, sessions) reaches the screen at once, over the account's Realtime channel, and is read again on return to the foreground or after a reconnection. Nothing waits for a relaunch or a pull to refresh.
8. One language per person: everything sent to someone (push, email, SMS, support reply, cancellation) is in their app's language (the 7 languages of the app), with the same phrases as the app.

## Accessibility & Inclusion

System font scale (text in sp), TalkBack labels on all controls and media, "Remove animations" respected, 48dp touch targets. Inclusive gender/orientation options in onboarding (not yet specified in detail; open decision).
