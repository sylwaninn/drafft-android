package so.drafft.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import so.drafft.app.feature.auth.HoldLayer
import so.drafft.app.feature.auth.LocalHeroSlideshowLeads
import so.drafft.app.feature.auth.OnboardingView
import so.drafft.app.feature.auth.SplashView
import so.drafft.app.feature.auth.WelcomeView
import so.drafft.core.data.AppModel
import so.drafft.core.data.DiscoveryFreshness
import so.drafft.core.data.backend.AppOpens
import so.drafft.core.data.backend.DeviceIntegrity
import so.drafft.core.data.chat.ChatService
import so.drafft.core.data.UserChannel
import so.drafft.core.data.platform.ForegroundReturns
import so.drafft.core.data.sessions.SessionStore
import so.drafft.core.data.store.PurchaseCredit
import so.drafft.core.data.telemetry.ScreenTracker
import so.drafft.core.data.telemetry.TelemetrySession
import so.drafft.core.model.L
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.DrafftConfirm
import so.drafft.core.ui.platform.LocalPlatformUi
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.Motion

/**
 * The app's root: the welcome screen, sign-up or the tabs, the splash over them until the first screen
 * is ready, and the moderation hold over everything. A newly picked language reaches every text on
 * its own (`LanguageObservation`): nothing is rebuilt.
 */
@Composable
fun RootView(app: AppModel) {
    val scope = rememberCoroutineScope()
    val appOpens = koinInject<AppOpens>()
    val deviceIntegrity = koinInject<DeviceIntegrity>()
    val purchaseCredit = koinInject<PurchaseCredit>()
    val chat = koinInject<ChatService>()
    val userChannel = koinInject<UserChannel>()
    val sessions = koinInject<SessionStore>()
    val foreground = koinInject<ForegroundReturns>()
    val telemetry = koinInject<TelemetrySession>()
    val moderation = app.moderation

    // The tabs exist from shortly after launch, invisible under the welcome screen or sign-up.
    var tabsMounted by remember { mutableStateOf(false) }
    // The saved session has been checked (signed in straight away, or the welcome screen).
    var sessionChecked by remember { mutableStateOf(false) }
    var splashShown by remember { mutableStateOf(true) }
    // Each tab has been built once (MainTabs). A signed-in launch lands on the tabs: the splash stays
    // until they're built, so the first tap on a tab never builds it.
    var tabsBuilt by remember { mutableStateOf(false) }
    // The hold's own window is up (while held, and during the fade once it lifts).
    var holdWindow by remember { mutableStateOf(false) }
    val inMain = app.phase == AppModel.Phase.MAIN

    CompositionLocalProvider(LocalAppModel provides app) {
        Box(Modifier.fillMaxSize().background(DS.palette.night)) {
            // The real tabs are built early, invisibly, under the welcome screen (or sign-up); signing
            // in reveals screens that already exist. Sign-out rebuilds them fresh (sessionID).
            if (tabsMounted || inMain) {
                key(app.sessionID) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .zIndex(if (inMain) 2f else 0f)
                            .alpha(if (inMain) 1f else 0f)
                            .then(if (inMain) Modifier else Modifier.clearAndSetSemantics { }),
                    ) {
                        MainTabs(
                            isActive = inMain && moderation.hold == null,
                            isVisible = inMain,
                            // Hidden under the splash, the welcome screen or sign-up: tabs may be built.
                            mayPrebuild = splashShown || !inMain,
                            onBuilt = { tabsBuilt = true },
                        )
                    }
                }
            }
            if (!inMain) {
                AnimatedContent(
                    targetState = app.phase,
                    modifier = Modifier.fillMaxSize().zIndex(1f),
                    transitionSpec = {
                        if (targetState == AppModel.Phase.ONBOARDING) {
                            slideInHorizontally(Motion.gentle()) { it } togetherWith fadeOut(Motion.gentle())
                        } else {
                            fadeIn(Motion.gentle()) togetherWith fadeOut(Motion.gentle())
                        }
                    },
                    label = "phase",
                ) { phase ->
                    when (phase) {
                        // Under the splash, the welcome photos wait for it: it hands over on the photo it shows.
                        AppModel.Phase.WELCOME -> CompositionLocalProvider(LocalHeroSlideshowLeads provides !splashShown) { WelcomeView() }
                        AppModel.Phase.ONBOARDING -> OnboardingView()
                        AppModel.Phase.MAIN -> Box(Modifier.fillMaxSize())
                    }
                }
            }
            // A moderation hold: the hold screen covers everything, at once, and lifts the same way. In its own
            // full-screen window (`FullScreenWindow`), above the sheets and covers already open; kept a moment
            // after the hold lifts, for the fade.
            if (app.phase != AppModel.Phase.WELCOME && holdWindow) {
                LocalPlatformUi.current.FullScreenWindow(onDismissRequest = {}) { HoldLayer() }
            }
            // The launch: it covers the first screen until it's ready, then fades onto it.
            if (splashShown) {
                Box(Modifier.fillMaxSize().zIndex(10f)) {
                    SplashView(isReady = sessionChecked && (if (inMain) tabsBuilt else tabsMounted), onFinished = { splashShown = false })
                }
            }
            // Banners in their own window above everything, whatever the phase.
            WindowBanners()
            DrafftConfirm(
                visible = app.sessionEndedNotice,
                onDismissRequest = { app.sessionEndedNotice = false },
                icon = "user-warning",
                title = L("You've been logged out"),
                message = L("Your session ended on this iPhone. Log in again to pick up where you left off."),
                cancelTitle = L("Got it"),
                actions = emptyList(),
            )
        }
    }

    // Who is signed in and what the screen is, for crash reports and product analytics.
    LaunchedEffect(Unit) { telemetry.watch(app) }
    // The tabs are built invisibly under the splash, the welcome screen and sign-up: the screen on show
    // stays on Discover until then, so no `$screen` is sent for tabs nobody sees.
    val baseScreen = TelemetrySession.baseScreen(app, prebuilding = splashShown || !inMain)
    // Set as the change is applied (not a frame later in a LaunchedEffect), so the screens leaving with the
    // old tab don't publish it again before the new base is there.
    SideEffect { ScreenTracker.base(baseScreen) }
    LaunchedEffect(Unit) {
        // Once the welcome screen has drawn and settled.
        delay(800)
        tabsMounted = true
    }
    // Signed in on this device before: straight in.
    LaunchedEffect(Unit) {
        app.restoreSession()
        sessionChecked = true
    }
    // A session that ends on its own (revoked, expired, account deleted elsewhere): back to the
    // welcome screen, which says why. The server turning actions down (paused, on hold).
    LaunchedEffect(Unit) { app.watchSession() }
    LaunchedEffect(Unit) { app.followServerRefusals() }
    LaunchedEffect(app.phase) { if (app.phase == AppModel.Phase.WELCOME) moderation.clear() }
    // The hold paused the profile; lifting it gave the person's own pause back.
    var heldBefore by remember { mutableStateOf(moderation.hold != null) }
    LaunchedEffect(moderation.hold) {
        val held = moderation.hold != null
        if (heldBefore && !held && app.phase != AppModel.Phase.WELCOME) scope.launch { app.refreshAccount(force = true) }
        heldBefore = held
        if (held) {
            holdWindow = true
        } else {
            // After the fade: back to the app, where the person was.
            delay(400)
            holdWindow = false
        }
    }
    // Signed in or launched: this opening, for the team's safety checks; a purchase confirmed earlier but
    // not credited yet; chat (one connection for the account); the account's live channel.
    LaunchedEffect(app.phase == AppModel.Phase.WELCOME, app.sessionID) {
        if (app.phase == AppModel.Phase.WELCOME) return@LaunchedEffect
        launch { deviceIntegrity.report() }
        launch { appOpens.report() }
        purchaseCredit.resume(app)
        launch { chat.start(app) }
        // Blocks made offline go now; then the blocked list as the server has it.
        launch { app.sendPendingSafety(); app.loadBlocked() }
        userChannel.watch(app)
    }
    // A real return to the app (from the background): what changed while away.
    LaunchedEffect(Unit) {
        foreground.returns.collect {
            if (app.phase == AppModel.Phase.WELCOME) return@collect
            scope.launch { moderation.load() }
            // Photo verdicts given while away (a missed live event): read again, never assumed.
            app.photoModeration.recheck()
            scope.launch { appOpens.report() }
            scope.launch { app.loadWallet() }
            purchaseCredit.resume(app)
            scope.launch { sessions.refresh() }
            // Blocks still waiting for the server, and those made on another device.
            scope.launch { app.sendPendingSafety(); app.loadBlocked() }
            // The deck, likes and matches as the server has them now (no card outlives it), if
            // away long enough for them to have changed; quietly, over what's on screen.
            app.refreshDiscovery(DiscoveryFreshness.Moment.FOREGROUND)
        }
    }
}
