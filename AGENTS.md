# drafft Android: instructions for agents and contributors

drafft for Android is a native Android app, built with Kotlin and Jetpack Compose. It shares the backend, the
product, the design system, the wording and the string catalog with drafft-ios, and the two apps keep the same
features and behaviour.

- Product: [PRODUCT.md](PRODUCT.md). Design rules: [DESIGN.md](DESIGN.md) (binding, including the
  "Drafft app rules" section). Wording: [WORDING.md](WORDING.md) (binding for any text people see).
  DESIGN.md and WORDING.md are shared with drafft-ios, which holds their source: see "Shared docs" before
  changing them. They name iOS APIs and places: "Shared rules on Android" below gives the Android form of each.
- A feature both apps have behaves the same in both: check drafft-ios (next to this checkout locally; on the
  web, `gh repo clone sylwaninn/drafft-ios` into a temporary folder) before changing it here.
- Backend: the `drafft-backend` repository (Supabase), shared by both apps.
- **A standalone app.** Never describe this app as a port, a copy, a mirror or a translation of the iPhone app,
  in code, comments, docs, commits or pull requests, and never point a comment to a Swift file.
- **Who drafft is for stays in PRODUCT.md.** The audience (age above all, city, how often people train) is
  never written in a README or any other doc. A README never details what a session proposal holds.

## User-facing text: WORDING.md first (priority rule)

Before writing or changing any text people see (UI strings in any of the 7 languages, CTAs, errors,
empty states, push, email, paywall, Play Store listing, screenshots, marketing), read and apply
[WORDING.md](WORDING.md), then run its review checklist (section 10). The `wording` skill (`.claude/skills/wording/`) walks through it. Never write "plan" in any sense or language, and never
present a match as turning into something. App strings live in the shared catalog, whose source is drafft-ios's
`Localizable.xcstrings` (see "Text" below): a new or changed string is written there first. The only text
written here is the Android wording of platform sentences (`core/model/src/main/resources/i18n/android/`).

## Working with the user

- **Rules live in this repository, never in an agent's memory.** A rule the user gives (design, copy,
  product, way of working) goes into the document it belongs to, in the same change: DESIGN.md,
  PRODUCT.md, this file, or WORDING.md (in drafft-ios, its source). Never save it to Claude Code's auto
  memory: a cloud session, another machine or another agent would never see it.
- **Industry-grade solutions.** Every fix or feature takes the robust, secure, scalable solution the
  industry already uses (proven libraries and patterns: idempotency keys, retries with backoff,
  dead-letter queues and redrive, circuit breakers, stale-while-revalidate), never a quick patch.
  Challenge it before presenting it: name the pattern, its failure modes and how they are covered.
- **Design calls are yours.** On design and build tasks, decide the structure, the call to action and the
  wording (within WORDING.md) and say what you chose in the summary, instead of a round of questions.
  Lean modern: rich motion and micro-interactions.
- **Never check screens yourself**: no screenshots, no visual review by a subagent.
  Build, install and launch the app on the emulator (`installLocalDebug`), then hand over.
  The user checks the result themselves.
- **On the user's phone, launch only on their go.** Install, then launch or relaunch only once the user
  says "ok" or "prêt": they set the phone up first.
- **Always live** (PRODUCT.md principle 7): any server state the person can see listens to the account's
  Realtime channel and is read again on foreground and reconnect. Never a relaunch or a pull to refresh.
- **Reviews run in depth, never trimmed.** A review (`/pr-review-toolkit:review-pr`, a pull request
  audit) uses every applicable specialist agent on each pull request (code-reviewer,
  silent-failure-hunter, pr-test-analyzer, comment-analyzer, type-design-analyzer, then code-simplifier).
  Batch by repository if needed; never drop an aspect to save agents.
- **Don't wait for CI or deploys.** Start the run, look at its status once if useful, report and move on.
  Never block on `gh run watch`.

## Repository rules

Everything an agent needs is in this repository: this file, the docs it links, and `.claude/` (settings,
git guard, skills). Claude Code loads the same files on this machine and on the web.

### Branches and commits

- Never commit on `main` and `staging`. Branch from a fresh `origin/staging` (`git fetch origin` first), named
  `feat/`, `fix/`, `chore/`, `docs/` or `hotfix/` + a short kebab-case name.
- Commit messages: `type(scope): description`, one line, no body, no trailers. Types: feat, fix, docs,
  style, refactor, test, chore. Scope (required): the area touched: `chat`, `discover`, `profile`, `ui`, `data`, `model`, `i18n`, `ci`, or `android` for build and platform-wide changes. The description is lowercase,
  imperative, starts with a verb and has no final period. Example: `feat(chat): add voice message replies`.
- Commits are authored by the user only: never a `Co-Authored-By` or any AI attribution line
  (`.claude/settings.json` turns Claude Code's off; the `commit-msg` hook and CI refuse them).
- One logical change per commit; every commit passes verify. Never `--no-verify`.
- Enforcement: the git hooks in `.agents/git-hooks/` (`git config core.hooksPath .agents/git-hooks`,
  which `.claude/settings.json` runs at the start of every session) and, for Claude Code,
  `.claude/hooks/guard-git.py` (commits and pushes to `main` and `staging`, deleting them, `--no-verify`). If a hook
  refuses, change the approach; never work around it.

### Pull requests and releases

- Open them with the `create-pr` skill (`.claude/skills/create-pr/`), into `staging`. Title in
  conventional commit format, English, 70 characters at most (it becomes the squash commit and feeds
  the release version: `type!:` major, any `feat` minor, else patch). Every section of the body filled,
  no AI attribution. Squash-merge.
- Never merge a pull request whose checks are red or still running, never with admin rights.
- A merge into `staging` runs CI only. A release (Actions > release, started by hand on GitHub) fast-forwards `main` to `staging`, tags `vX.Y.Z` and publishes a GitHub release; store builds are made by hand from that tag.
- Agents never start a release or a deploy unless the user asks for it in the current request, and
  never tag by hand.

### Secrets

Never open, print, copy, search or summarize `.env*` files (`.env.example` is safe), `.dev.vars`
(`.dev.vars.example` is safe), keys, `google-services.json` or anything in `~/Secrets/`, by any means.
Run the CLI that consumes them without showing them, and only when the user asks: it writes to a remote
project. Never write, regenerate or overwrite a user's `.env.local`. `.claude/settings.json` denies the
reads.

### Environments

Apps an agent installs or launches always target the local Supabase. Never build, install, deploy or run
mutations against staging or production unless the user asks for that environment in the current
request. Compile-only checks are the exception.

### Work that spans repositories

A product feature usually runs backend, then the two apps (then the website for legal or marketing
copy): one session and one pull request per repository, backend first since the apps call its RPCs and
functions. Both apps ship it with the same names, behaviour and strings. The first
pull request states the contract (RPCs, payloads, event names) and the next ones link it. Another
repository is read on GitHub (`gh repo clone sylwaninn/<repo>` into a temporary folder), never edited
from here, except the shared docs below when the user agrees.

### Shared docs

`WORDING.md` (in drafft-ios, drafft-android, drafft-backend and drafft-web) and `DESIGN.md` (in drafft-ios
and drafft-android) are one document kept identical in each repository; drafft-ios holds the reference.
**After changing either one here, ask the user whether the change goes to the other repositories' copies.**
If yes, make the identical change in each, one pull request per repository (`gh repo clone
sylwaninn/<repo>` into a temporary folder, a branch from its base, the `create-pr` skill), and link the
pull requests to each other. Locally, drafft-ios's `scripts/sync-shared.sh` writes the copies from
drafft-ios, and `--check` lists those that differ.

### This repository

`staging` (the default branch) takes every pull request; `main` is production and only moves through
the release workflow (Actions > release: staging's new commits onto `main`, a `vX.Y.Z` tag and a GitHub
release, see `scripts/ci/release.sh`). Store builds are made by hand, from the release tag.

### Verify

"Verify" in these rules means, in this repository:

```sh
./gradlew -p tools/jvmcheck compileKotlin test   # platform-neutral code on the JVM, unit tests
python3 scripts/check-strings.py                 # every L("...") key exists in the catalog
python3 scripts/ci/design_lint.py && python3 scripts/ci/i18n_lint.py
./gradlew assembleLocalDebug                     # the Android build (needs the Android SDK)
```

CI (`.github/workflows/app.yml`) runs all of them on every pull request, plus gitleaks, actionlint and
the asset size and build-key checks. The last line needs the Android SDK and Google's Maven. Without
them, run the others and say that the Android build wasn't checked.

The design lint encodes DESIGN.md's rules in their Compose
form:
- no gradients except photo scrims and blur masks;
- no '·';
- no '…' on copy (`TextOverflow.Ellipsis` included: people's content may be cut, annotated);
- lowercase brand;
- palette colours only;
- sheets through `DrafftSheet`.

A deliberate exception carries its reason: `// design-lint: allow <rule> - <why>`, on the line or the
line above. The i18n lint wants all 7 languages, matching placeholders, lowercase brand and none of
the wording WORDING.md forbids (its `wording-forbidden` block). It runs on the synced tables, the
Android variants (`i18n/android/`) and `NotificationText.kt`.

### Flavors

Three flavors, each with its values in `config/<flavor>.properties`, committed:
- **production** (`drafft`, production backend),
- **staging** (`drafft β`, staging backend),
- **local** (`drafft local`, the local Supabase of drafft-backend with its staging services, debug
  only: there is no local release build).

Values: Supabase URL and publishable key, RevenueCat public SDK key (`goog_...`), Turnstile site key,
SMS code lifetime, launcher name, Sentry DSN, PostHog key and host (the full list:
[docs/configuration.md](docs/configuration.md)). `app/build.gradle.kts` reads them into `BuildConfig` and
`app_name`. A new value goes in the three files and in `flavorFields`.

Only public keys, ever. The build stops at configuration on anything that looks like a secret, a remote
Supabase URL that isn't https, and telemetry outside the EU (details in docs/configuration.md).

CI greps for the same secrets, and gitleaks allows only publishable and `goog_` keys by value. A
release build lacking a Supabase or RevenueCat value fails.

The local Supabase depends on the machine: run `supabase start` in drafft-backend, then
`scripts/local-backend.sh`. It writes the URL and key to the gitignored `local.private.properties`
(emulator `10.0.2.2`, `--device` for a phone on the same
Wi-Fi). Without it the local app stops at launch and says what's missing. The app reads no dotenv
file.

**Run the app on the local backend, always.** Every build an agent installs or launches (emulator
or phone) is the local flavor: `./gradlew installLocalDebug`. NEVER build, install or launch
production or staging (`installProductionDebug`, `installStagingDebug`, any `*Release`) unless the
user explicitly asks for that environment in the current request. All flavors share the application
id `so.drafft.app`, so any other build silently replaces the local app. It then sends real actions
(sign-ups, likes, messages) to that backend. Before installing, check
`app/build/generated/source/buildConfig/local/debug/so/drafft/app/BuildConfig.java`: `SUPABASE_URL`
must be the local machine's address. Compile-only checks (the verify commands above, no install, no
launch) are the one exception.

## Modules

| Module | What | Plugin |
|---|---|---|
| `core:model` | Data types and the logic that stands alone: `Profile`, `Message`, `Sport`, `DiscoverFilters`, the string catalog (`L`), dates, ThumbHash, ledgers. No Android. | Kotlin JVM |
| `core:data` | `AppModel` (the app's state, observed by every screen) and everything it talks to: Supabase (`Backend`, `ProfileSync`...), chat (Stream), purchases (RevenueCat), caches, and the platform services as interfaces (`platform/`). | Android library |
| `core:ui` | The design system: tokens (`DS`), type, surfaces, `Symbols`/`DrafftIcon`, components, sheets, progressive blur, navigation (`NavStack`), bundled photos. | Android library, Compose |
| `app` | The screens (`so.drafft.app.feature.*`), the root (`RootView`, `MainTabs`), `MainActivity`, `DrafftApplication`, Koin wiring. | Android application |

Packages: `so.drafft.core.model`, `so.drafft.core.data[.backend|.chat|.media|.sessions|.platform|...]`,
`so.drafft.core.ui[.theme|.components|.navigation|.image|.platform]`, `so.drafft.app[.feature.<area>]`.

### Two source sets per module: `src/main/kotlin` and `src/android/kotlin`

`src/main/kotlin` is **platform-neutral**: no `android.*`, no `androidx.activity`/`androidx.core`/
`LocalContext`/`LocalView`, no Stream, RevenueCat, Play Services, CameraX or Media3 imports. It is
compiled on the JVM by `tools/jvmcheck` (see below), so it must build there.

`src/android/kotlin` holds everything that needs the Android SDK or an Android-only library: the
implementations of the `platform` interfaces, Stream and RevenueCat code, Activity result launchers,
`AndroidView`s (camera preview, video player), `RenderEffect` blur, `MainActivity`.

The bridge between the two:
- **Services** (location, audio, calendar, notifications, photo and video compression, face detection,
  device integrity, key-value storage, links): an interface in `core:data/src/main/.../platform/`,
  implemented in `core:data/src/android/...`, registered in Koin.
- **Composable platform pieces** (pickers, permission prompts, camera preview, video player, native
  share, blur): an interface in `core:ui/src/main/.../platform/PlatformUi.kt` with `@Composable`
  members, provided at the root through `LocalPlatformUi`, implemented in `core:ui/src/android/...`.
- Singletons that screens and the model call directly (`Haptics`) are objects with a pluggable engine.

### The compile check (no Android SDK needed)

```sh
gradle -p tools/jvmcheck compileKotlin -q   # type-checks every src/main/kotlin on the JVM
gradle -p tools/jvmcheck test               # core:model and core:data unit tests
python3 scripts/check-strings.py            # every L("...") key exists in the catalog
```

It compiles `core/*/src/main/kotlin` and `app/src/main/kotlin` against Compose Multiplatform 1.7
(same packages and APIs as Jetpack Compose 1.7), with small stand-ins in `tools/jvmcheck/shims/`
for the few Android-only Compose APIs used from shared code (`painterResource(Int)`, `Font(Int)`,
`BackHandler`, `R`). Consequences:
- Use Compose APIs that exist in Compose 1.7 / Material3 1.3 (the Android build uses a newer BOM, a
  superset). No `androidx.navigation`, no `androidx.lifecycle.ViewModel` in shared code.
- Keep it green. A shim is only for an Android-only API that shared code genuinely needs.

The full Android build (`./gradlew assembleProductionDebug`) needs the Android SDK and Google's Maven.

## Kotlin conventions

Shared concepts carry the same names in both apps (types, functions, properties, parameters, event names,
payload fields), so a contract reads the same everywhere. Enum cases are UPPER_SNAKE (`SUPER_LIKE`), with the
backend's raw value kept as `id`/`rawValue`.

| Need | Kotlin |
|---|---|
| A value type | `data class` with `val`s (copy to change) |
| A choice that carries data | `sealed interface` + `data class`es |
| Observed state (AppModel, services' state) | a class whose observed properties are `var x by mutableStateOf(...)` (Compose snapshot state), read directly by composables |
| A field nothing observes | plain `var` |
| State local to a screen | `var x by remember { mutableStateOf(...) }` (`rememberSaveable` for what should survive rotation) |
| A two-way value | two parameters: `x: T, onXChange: (T) -> Unit` |
| The app's state in a screen | `val app = LocalAppModel.current` |
| Closing a screen | an `onDismiss: () -> Unit` parameter, or `LocalNavStack.current.pop()` |
| Async work | `scope.launch { ... }` (`rememberCoroutineScope()` in UI, `AppModel.scope` in the model) |
| Work tied to a screen or a key | `LaunchedEffect(Unit) { }` / `LaunchedEffect(id) { }` |
| Reacting to a change | `LaunchedEffect(x) { }` (skip the first run when only changes matter) |
| Failure | `suspend` + exceptions |
| Times | `java.time.Instant` / `Double` seconds (or `kotlin.time.Duration` where clearer) |
| Links, bytes, free JSON | `String`, `ByteArray`, `kotlinx.serialization.json.JsonObject` |
| Broadcast events | a `SharedFlow` on the owner |
| Small values kept on the phone | `KeyValueStore` (platform) |

A screen is a `@Composable fun` named after it, its parameters first, then `modifier: Modifier = Modifier`.

**Same behaviour and wording in both apps; the look follows the platform.** Features, flows and copy stay
aligned. Where Android works differently (Compose's self-blur, footer layouts), adapt the look. A fix that
improves both apps goes into both repositories.

Comments say why (they carry the product decisions), never what the code plainly does.

### Text

- Every string people see comes from the shared catalog: `L("English text", args...)`. The key is the
  catalog's English source string, its placeholders as Java format specifiers: catalog `%lld× a week` →
  `L("%d× a week", n)`; `%@ session` → `L("%s session", name)`. Look keys up in
  `core/model/src/main/resources/i18n/keys.txt`. Run `scripts/check-strings.py`.
- Interface text is `Text(L("literal"))`; people's own content is never passed to `L`.
- Never write new user-facing text without checking WORDING.md. Never use "…" to cut interface copy,
  never a middle dot `·`, brand always lowercase `drafft` (see `branded(...)`).
- Dates: `DateText` (ICU skeletons, app language). Numbers: `String.format(appLocale, ...)`.

### Look

- Colours: `DS.palette.<token>` only (DESIGN.md roles: `lime` = the accent fill, `accentInk` = accent
  text, `ink`, `body`, `mute`, `canvas` = a block, `canvasSoft` = the page, `night`...). `canvas` and
  `canvasSoft` flip inside `SheetSurface { }`; accent-aware components read `LocalIsNightSurface`
  (`NightSurface { }`). No gradients except photo scrims and blur masks.
- Type: `display(size)` (Inter Display Black), `displayBold(size)`, `TextStyles.body/.subheadline/...`
  (the shared type scale, in sp) with `.semibold`, `.bold`, `.heavy`.
- Spacing `DS.Space.*`, radius `DS.Radius.*`, motion `Motion.snappy()/bouncy()/gentle()/select()`.
- Icons: `DrafftIcon("heart")` / `Symbols.vector(name)`, by their Solar names. Each name is a vector
  drawable `core/ui/res/drawable/ic_<name>.xml` listed in `Symbols`; a new icon comes with its drawable and
  its table entry together (rules in DESIGN.md, Icons).
- Photos: bundled ones by name through `BundledImages`, remote ones through the design system's photo
  component (Coil), sized to where they're drawn.
- Haptics: `Haptics.tap()/thump()/success()/warning()/select()`.
- Components: sheets → `DrafftSheet` (a bottom sheet with drafft's sheet shape and surface); full-screen
  covers → `FullScreenCover`; confirmations → `DrafftConfirm`; navigation → `NavStack` + `NavStackHost`;
  glass → the design system's glass surface (a translucent fill with a hairline; real blur where the
  platform allows); progressive blur → `Modifier.progressiveBlur` (RenderEffect on Android 12+, a soft scrim
  below). Android's system back always does what the screen's back or close does.

### Shared rules on Android

DESIGN.md and WORDING.md name iOS APIs and places. The rule stays, and its Android form
is:

| In the shared docs | Here |
|---|---|
| pt (sizes, 44pt targets) | dp, sp for text. Touch targets at least 48dp (Android's minimum) |
| Dynamic Type, VoiceOver | the system font scale (text in sp, layouts that wrap), TalkBack (`contentDescription`, `semantics`) |
| Reduce Motion | the system's animator duration scale / "Remove animations" |
| Liquid Glass, `.glassEffect(.regular)` | `Modifier.glass` |
| `VariableBlurView`, `ProgressiveBlur` | `Modifier.progressiveBlur` |
| `.sheet` + `.sheetSurface()` | `DrafftSheet` + `SheetSurface { }` |
| `ViewThatFits` | `FirstThatFits`, a small helper in `PaywallView` and `SuperLikeComposer` (not in core:ui) |
| `DS.Palette` | `DS.palette` in core:ui |
| `Image("name")` (Icons catalog) | `Symbols` / `DrafftIcon("name")` |
| keyboard safe area | IME insets (`WindowInsets.ime`, `imePadding`) |
| App Store, Apple Account, Apple ID, "Apple emails your receipt" | Google Play, Google account. The wording is in `i18n/android/` |
| `manageSubscriptionsSheet`, App Store subscriptions | Google Play's subscriptions page (`play.google.com/store/account/subscriptions`) |
| iPhone Settings | the phone's settings (the app's notification settings screen) |
| `UserDefaults` | `KeyValueStore` |
| App Store screenshots, subtitle (iOS) | Play Store screenshots, short description |

## Telemetry (Sentry and PostHog): part of every change

[docs/telemetry.md](docs/telemetry.md) is the reference (events, screens, errors, alerts); the code is
`so.drafft.core.data.telemetry`. Both apps send the same events, with the same names and properties.

**Every feature, change or task finishes with a telemetry pass. The pull request's "Notes" says what was
done, or "Telemetry: none, because ..." (a refactor, a copy change).** The checklist:

1. **Events.** What the person did and whether it worked: a factory in `AnalyticsEvent` (`object_action`, snake_case,
   past tense, typed properties: numbers, booleans, codes, never free text), fired where the model knows
   the outcome (after success; a `*_failed` event with a `reason` code on failure). Same name and
   properties in drafft-ios, in the same change or its twin pull request. Never rename an event or a
   property: add a new one.
2. **Screens.** A new screen, sheet or cover gets `TrackScreen(Screen.X)` at the top (a paywall `TrackPaywall(kind)`), with its `Screen` entry.
3. **Errors.** A `catch` that swallows or rethrows something unexpected calls
   `Telemetry.unexpected(error, area, action)`: only what needs a fix alerts (not offline, not a refusal
   the screen explains). Log with a `java.util.logging` logger named `so.drafft.<area>` (the ones `TelemetryLogHandler` sends to Sentry), never `android.util.Log` directly.
4. **Alerts.** A flow that costs money, accounts or safety (sign-up and sign-in, purchases, deletion,
   moderation, push, chat send) gets its alert, not only its event: a Sentry alert rule filtered on
   `environment:production` (and the `area` tag), and a PostHog alert or insight on the failure event.
   Write it in the "Alerts" part of `docs/telemetry.md`; create it through the PostHog MCP / Sentry when
   asked.
5. **Conventions, always.** One PostHog project for the two apps and the website, one Sentry project per
   app, each shared by production and staging: every insight, funnel, alert and experiment filters `app_environment = production`
   (PostHog; the project's test-account filter already does) and `environment:production` (Sentry).
   Never switch the privacy rules off to get a number: consent, `PrivacyGuard`, no screenshots or replay
   (docs/telemetry.md).
6. **Docs and tests.** The event goes in the doc's table and in the catalog test (`everyEventPassesThePrivacyGuardUntouched`).

Never put what people typed, their sensitive answers (gender, who they want to meet, lifestyle), their
location or another person's id in an event, a tag or a log line. `PrivacyGuard` drops it anyway, and unit
tests fail on it.

## State and data

- `AppModel` (core:data) is the app's single observable state. Screens read it through
  `LocalAppModel.current` and call its methods; it's a Koin singleton.
- Services are Koin singletons with constructor injection.
- All model mutation happens on the main thread (`Dispatchers.Main.immediate`).
- The backend is Supabase (supabase-kt for Auth and Realtime, Ktor for the raw REST, RPC and Edge
  Function calls), chat is Stream (low-level client only, every screen is
  ours), purchases are RevenueCat (Google Play).
