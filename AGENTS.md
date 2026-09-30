# drafft Android: instructions for agents and contributors

drafft for Android is a port of the iPhone app (repository `sylwaninn/drafft`, SwiftUI). Same features,
same behaviour, same wording, same look, built with Kotlin and Jetpack Compose. When the two apps
disagree, the iPhone app is right: read its source before changing behaviour here.

- Product: `../drafft/PRODUCT.md`. Design rules: `../drafft/DESIGN.md` (binding, including the
  "Drafft app rules" section). Wording: `../drafft/WORDING.md` (binding for any text people see).
- The iPhone sources live at `../drafft/Drafft/`. Each Kotlin file says which Swift file it ports.

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

## Porting rules (Swift to Kotlin)

Names mirror the Swift ones so a reader can go from one app to the other: same type names, same
function and property names (lowerCamelCase), same parameter names and order. Enum cases become
UPPER_SNAKE (`case superLike` → `SUPER_LIKE`), with the Swift raw value kept as `id`/`rawValue`.

| Swift | Kotlin |
|---|---|
| `struct` value type | `data class` with `val`s (copy to change) |
| `enum` with associated values | `sealed interface` + `data class`es |
| `@Observable final class` (AppModel, services' state) | a class whose observed properties are `var x by mutableStateOf(...)` (Compose snapshot state), read directly by composables like SwiftUI reads `@Observable` |
| `@ObservationIgnored var` | plain `var` |
| `@State private var` in a view | `var x by remember { mutableStateOf(...) }` (`rememberSaveable` for what should survive rotation) |
| `@Binding var x: T` | two parameters: `x: T, onXChange: (T) -> Unit` |
| `@Environment(AppModel.self)` | `val app = LocalAppModel.current` |
| `@Environment(\.dismiss)` | an `onDismiss: () -> Unit` parameter, or `LocalNavStack.current.pop()` |
| `Task { ... }` | `scope.launch { ... }` (`rememberCoroutineScope()` in UI, `AppModel.scope` in the model) |
| `.task { }` / `.task(id:)` | `LaunchedEffect(Unit) { }` / `LaunchedEffect(id) { }` |
| `.onChange(of: x)` | `LaunchedEffect(x) { }` (skip the first run when the Swift code does) |
| `async throws` | `suspend` + exceptions |
| `Date` / `TimeInterval` | `java.time.Instant` / `Double` seconds (or `kotlin.time.Duration` where clearer) |
| `URL` | `String` |
| `Data` | `ByteArray` |
| `[String: Any]` JSON | `kotlinx.serialization.json.JsonObject` |
| `NotificationCenter` posts | a `SharedFlow` on the owner |
| `UserDefaults` | `KeyValueStore` (platform) |

A SwiftUI `View` becomes a `@Composable fun` with the same name and the same parameters in the same
order, then `modifier: Modifier = Modifier`. A view model class keeps its name.

Keep the iPhone code's comments when they explain why: they carry the product decisions. Don't add
comments that say what the code plainly does.

### Text

- Every string people see comes from the shared catalog: `L("English text", args...)`. The key is the
  English source string exactly as in the Swift code, with Swift interpolation turned into Java
  placeholders the way the catalog has them: `"\(n)× a week"` (catalog `%lld× a week`) →
  `L("%d× a week", n)`; `"\(name) session"` (`%@ session`) → `L("%s session", name)`. Look keys up in
  `core/model/src/main/resources/i18n/keys.txt`. Run `scripts/check-strings.py`.
- SwiftUI localizes `Text("literal")` on its own: in Kotlin that is `Text(L("literal"))`.
  `Text(verbatim:)` and people's own content are never passed to `L`.
- Never write new user-facing text without checking WORDING.md. Never use "…" to cut interface copy,
  never a middle dot `·`, brand always lowercase `drafft` (see `branded(...)`).
- Dates: `DateText` (ICU skeletons, app language). Numbers: `String.format(appLocale, ...)`.

### Look

- Colours: `DS.palette.<token>` only (DESIGN.md roles: `lime` = the accent fill, `accentInk` = accent
  text, `ink`, `body`, `mute`, `canvas` = a block, `canvasSoft` = the page, `night`...). `canvas` and
  `canvasSoft` flip inside `SheetSurface { }`; accent-aware components read `LocalIsNightSurface`
  (`NightSurface { }`). No gradients except photo scrims and blur masks.
- Type: `display(size)` (Inter Display Black), `displayBold(size)`, `TextStyles.body/.subheadline/...`
  (the iPhone text styles, in sp) with `.semibold`, `.bold`, `.heavy`.
- Spacing `DS.Space.*`, radius `DS.Radius.*`, motion `Motion.snappy()/bouncy()/gentle()/select()`.
- Icons: `DrafftIcon("sf.symbol.name")` / `Symbols.vector(name)`. The SF Symbol names stay in data and
  code; `Symbols` maps each to a Material glyph. A symbol missing from the table: add it there (one
  glyph per meaning).
- Photos: bundled ones by name through `BundledImages`, remote ones through the design system's photo
  component (Coil), sized to where they're drawn.
- Haptics: `Haptics.tap()/thump()/success()/warning()/select()`.
- iPhone idioms and their Android form: sheets → `DrafftSheet` (a bottom sheet with the iPhone sheet's
  shape and surface); `.fullScreenCover` → `FullScreenCover`; `.drafftConfirm` → `DrafftConfirm`;
  `NavigationStack` → `NavStack` + `NavStackHost`; Liquid Glass → the design system's glass surface
  (a translucent fill with a hairline; real blur where the platform allows); `ProgressiveBlur` → the
  design system's version (RenderEffect on Android 12+, a soft scrim below). Android's system back
  always does what the iPhone's back or close does.

## State and data

- `AppModel` (core:data) is the app's single observable state, like the iPhone's. Screens read it
  through `LocalAppModel.current` and call its methods; it's a Koin singleton.
- Services mirror the iPhone's `.shared` singletons as Koin singletons with constructor injection.
- All model mutation happens on the main thread (`Dispatchers.Main.immediate`), like `@MainActor`.
- The backend is Supabase (supabase-kt for Auth and Realtime, Ktor for the raw REST/RPC/Functions
  calls the iPhone makes with `URLSession`), chat is Stream (low-level client only, every screen is
  ours), purchases are RevenueCat (Google Play).

## Git

Branches and pull requests follow `../drafft/.agents/rules/` (commit message format, no push to `main`).
