package so.drafft.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import java.util.UUID
import org.koin.compose.koinInject
import so.drafft.app.feature.auth.LocationRequiredView
import so.drafft.app.feature.auth.TermsConsentView
import so.drafft.app.feature.chat.CalendarAccessBanner
import so.drafft.app.feature.chat.CalendarAccessNotice
import so.drafft.app.feature.chat.ConversationsView
import so.drafft.app.feature.chat.SessionFailureBanner
import so.drafft.core.data.sessions.SessionCalendar
import so.drafft.core.data.sessions.SessionFailureNotice
import so.drafft.app.feature.chat.LikesTabView
import so.drafft.app.feature.discover.DiscoverView
import so.drafft.app.feature.matches.BoostBannerView
import so.drafft.app.feature.matches.MatchBannerView
import so.drafft.app.feature.matches.MatchView
import so.drafft.app.feature.matches.NoticeBannerView
import so.drafft.app.feature.me.MeView
import so.drafft.app.feature.me.PhotoRefusalBanner
import so.drafft.app.feature.me.PhotoRefusalPresenter
import so.drafft.app.feature.me.PurchaseCreditBanner
import so.drafft.app.feature.me.PurchaseHelpPresenter
import so.drafft.core.data.moderation.PhotoModeration
import so.drafft.core.data.store.PurchaseCredit
import so.drafft.core.ui.components.LocalTabBarVisibility
import so.drafft.core.ui.components.TabBarVisibility
import so.drafft.core.ui.components.LocalTabIsCurrent
import so.drafft.core.ui.navigation.LocalNavBackEnabled
import so.drafft.core.ui.platform.LocalPlatformUi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import so.drafft.app.feature.me.SessionsView
import so.drafft.core.data.AppModel
import so.drafft.core.data.location.LocationGate
import so.drafft.core.data.notifications.NotificationService
import so.drafft.core.data.store.Store
import so.drafft.core.model.L
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.FullScreenCover
import so.drafft.core.ui.components.LocalTabBarInset
import so.drafft.core.ui.components.PauseScope
import so.drafft.core.ui.components.PausedLock
import so.drafft.core.ui.components.TopOverlayWindow
import so.drafft.core.ui.components.glass
import so.drafft.core.ui.components.pressScale
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion

// Port of MainTabs (Drafft/App/DrafftApp.swift).

private data class TabItem(val tab: AppModel.Tab, val title: () -> String, val symbol: String, val filled: String)

private val tabs = listOf(
    TabItem(AppModel.Tab.DISCOVER, { L("Discover") }, "fire", "fire-bold"),
    TabItem(AppModel.Tab.LIKES, { L("Likes") }, "heart", "heart-bold"),
    TabItem(AppModel.Tab.SESSIONS, { L("Sessions") }, "stopwatch-play", "stopwatch-play-bold"),
    TabItem(AppModel.Tab.CHATS, { L("Chats") }, "dialog-2", "dialog-2-bold"),
    TabItem(AppModel.Tab.ME, { L("You") }, "user-circle", "user-circle-bold"),
)

/** Height of the floating bar itself, without its bottom margin and the system navigation bar. */
private val TabBarHeight = 62.dp
private val TabBarMargin = 12.dp

/**
 * The five tabs. Each one is built once and kept (its screens, scroll positions and pushed pages
 * survive tab switches), so a tap never builds a tab. [isActive] is false while the tabs wait,
 * invisible, under the welcome screen or sign-up: nothing here may ask for a permission, present a
 * screen or show a banner then.
 */
@Composable
fun MainTabs(
    isActive: Boolean,
    isVisible: Boolean,
    /** Nobody can see the tabs (under the splash, the welcome screen or sign-up): they may be built ahead. */
    mayPrebuild: Boolean = !isVisible,
    /** Called once the walk is over (done, or stopped because the tabs showed). */
    onBuilt: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val app = LocalAppModel.current
    val notifications = koinInject<NotificationService>()
    val store = koinInject<Store>()
    val location = koinInject<LocationGate>()
    val foreground = koinInject<so.drafft.core.data.platform.ForegroundReturns>()
    val saveable = rememberSaveableStateHolder()
    // Tabs opened at least once stay composed; the others are built on their first visit (or ahead of
    // it, one after another, while the tabs are still hidden).
    var built by remember { mutableStateOf(setOf(app.tab)) }
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val visibilities = remember { tabs.associate { it.tab to TabBarVisibility() } }
    val barHidden = visibilities.getValue(app.tab).isHidden
    val barInset = if (barHidden) navInset else TabBarHeight + TabBarMargin + navInset

    Box(modifier.fillMaxSize().background(DS.palette.canvasSoft)) {
        CompositionLocalProvider(LocalTabBarInset provides barInset) {
            for (item in tabs) {
                if (item.tab !in built) continue
                val current = item.tab == app.tab
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(if (current) 1f else 0f)
                        .alpha(if (current) 1f else 0f)
                        .then(if (current) Modifier else Modifier.clearAndSetSemantics { }),
                ) {
                    CompositionLocalProvider(
                        LocalTabBarVisibility provides visibilities.getValue(item.tab),
                        LocalNavBackEnabled provides (current && isVisible),
                        LocalTabIsCurrent provides (current && isVisible),
                    ) {
                    saveable.SaveableStateProvider(item.tab.name) {
                        when (item.tab) {
                            AppModel.Tab.DISCOVER -> PausedLock { DiscoverView() }
                            AppModel.Tab.LIKES -> PausedLock(locked = PauseScope.locksLikes) { LikesTabView() }
                            AppModel.Tab.SESSIONS -> SessionsView()
                            AppModel.Tab.CHATS -> ConversationsView()
                            AppModel.Tab.ME -> MeView()
                        }
                    }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !barHidden,
            modifier = Modifier.align(Alignment.BottomCenter).zIndex(3f),
            enter = slideInVertically(Motion.snappy()) { it } + fadeIn(Motion.snappy()),
            exit = slideOutVertically(Motion.snappy()) { it } + fadeOut(Motion.snappy()),
        ) {
            TabBar(
                selected = app.tab,
                badges = mapOf(AppModel.Tab.LIKES to app.likedMeCount, AppModel.Tab.CHATS to app.unreadTotal),
                onSelect = { app.tab = it },
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = TabBarMargin),
            )
        }

        // The match moments' banners, over the tabs (while they're active). The app-wide ones
        // (photo refused, purchase credit, calendar, session failure) are the root's: `WindowBanners`.
        val banner: Any? = when {
            !isActive -> null
            app.banner != null -> app.banner
            app.notice != null -> app.notice
            app.boostBanner != null -> BoostToken(app.boostBanner!!)
            else -> null
        }
        TopOverlayWindow(banner = banner) { shown ->
            when (shown) {
                is AppModel.MatchBanner -> MatchBannerView(
                    banner = shown,
                    onOpen = { app.openChatWith(shown.profile.id) },
                    onDismiss = { app.banner = null },
                )
                is AppModel.Notice -> NoticeBannerView(notice = shown, onDismiss = { app.notice = null })
                is BoostToken -> BoostBannerView(id = shown.id, onDismiss = { app.boostBanner = null })
            }
        }

        // Location is required: while it's off (or never answered), a screen in its own window blocks
        // everything, sheets included, until it's back on. The permission and the phone's location
        // switch are flows, not Compose state: followed here so the screen comes and goes with them.
        val locationAuthorization by location.authorization.collectAsState()
        val locationServicesOff by location.servicesOff.collectAsState()
        val locationAllowed = locationAuthorization == so.drafft.core.data.platform.LocationProvider.Authorization.ALLOWED &&
            !locationServicesOff
        if (isActive && !locationAllowed) {
            LocalPlatformUi.current.FullScreenWindow(onDismissRequest = {}) { LocationRequiredView() }
        }

        // A fresh read found no record of the current terms and the consent to sensitive data: asked
        // at each open until accepted, after the location gate. Unknown (no read yet, offline) asks
        // nothing: the read is retried until it says (refreshAccount), and a sign-up can't finish
        // without the consent on the server (complete_onboarding).
        FullScreenCover(
            visible = isActive && locationAllowed &&
                app.termsConsent == so.drafft.core.model.TermsConsent.Gate.REQUIRED,
            onDismissRequest = {},
        ) { TermsConsentView() }

        val match = app.matchScreen
        FullScreenCover(visible = match != null, onDismissRequest = { app.matchScreen = null }) {
            if (match != null) {
                MatchView(
                    profile = match,
                    me = app.publicMe,
                    onChat = { app.openChatWith(match.id) },
                    onClose = { app.matchScreen = null },
                )
            }
        }
    }

    // Builds the tabs not visited yet while nobody sees them, one per frame-ish step, so the first
    // tap on each lands on a screen that already exists.
    LaunchedEffect(mayPrebuild) {
        try {
            if (!mayPrebuild) return@LaunchedEffect
            for (item in tabs) {
                if (item.tab in built) continue
                kotlinx.coroutines.delay(250)
                built = built + item.tab
            }
            // The last one gets its turn to build too.
            kotlinx.coroutines.delay(250)
        } finally {
            onBuilt()
        }
    }
    LaunchedEffect(app.tab) { built = built + app.tab }

    // Location is required: read again each time the app comes back (from Settings, say).
    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        foreground.returns.collect { location.refresh() }
    }
    // drafft tempo's details (plan, renewal) follow the store, for the signed-in account only.
    LaunchedEffect(Unit) {
        store.load()
        store.customerInfoStream.collect { info ->
            if (store.reportsLinkedAccount) app.subscription = store.subscription(info)
        }
    }
    // In (sign-in, end of sign-up, a hold lifted): discovery as the server has it; notification status.
    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        app.refreshDiscovery()
        location.refresh()
        notifications.refresh()
    }
    // Tapped notifications: open the chat, Discover for the weekly boost, or Sessions.
    LaunchedEffect(notifications.openChatID) {
        notifications.openChatID?.let { app.openChat(it); notifications.openChatID = null }
    }
    LaunchedEffect(notifications.openBoost) {
        if (notifications.openBoost) { app.tab = AppModel.Tab.DISCOVER; notifications.openBoost = false }
    }
    LaunchedEffect(notifications.openSessions) {
        if (notifications.openSessions) { app.tab = AppModel.Tab.SESSIONS; notifications.openSessions = false }
    }
}

/**
 * What the iPhone shows in its own window above the app (TopOverlayWindow.swift), in every phase:
 * sign-up included (a photo refused on the photos step), and over a moderation hold. Placed once, at
 * the root, with the explanation sheets those banners open.
 */
@Composable
fun WindowBanners() {
    val moderation = koinInject<PhotoModeration>()
    val credit = koinInject<PurchaseCredit>()
    val sessionFailure = koinInject<SessionFailureNotice>()
    val sessionCalendar = koinInject<SessionCalendar>()
    val banner: Any? = when {
        moderation.refusalBanner != null -> moderation.refusalBanner
        credit.banner != null -> CreditToken(credit.banner!!)
        CalendarAccessNotice.isShown -> CalendarAccessNotice
        sessionFailure.message != null -> SessionFailureToken(sessionFailure.message!!)
        else -> null
    }
    TopOverlayWindow(banner = banner) { shown ->
        when (shown) {
            is PhotoModeration.Refusal -> PhotoRefusalBanner(
                refusal = shown,
                onOpen = { moderation.refusalBanner = null; PhotoRefusalPresenter.show(shown) },
                onDismiss = { moderation.refusalBanner = null },
            )
            is CreditToken -> PurchaseCreditBanner(
                state = shown.state,
                pending = credit.oldest,
                onContact = credit::contactSupport,
                onDismiss = credit::dismissBanner,
            )
            is CalendarAccessNotice -> CalendarAccessBanner(
                onOpenSettings = {
                    CalendarAccessNotice.dismiss()
                    sessionCalendar.writer.openSettings()
                },
                onDismiss = CalendarAccessNotice::dismiss,
            )
            is SessionFailureToken -> SessionFailureBanner(message = shown.message, onDismiss = sessionFailure::dismiss)
        }
    }
    PhotoRefusalPresenter.Host()
    PurchaseHelpPresenter.Host()
}

/** The purchase credit banner, as a distinct banner value. */
private data class CreditToken(val state: PurchaseCredit.Banner)

/** A session change the server turned down, as a banner (a new sentence is a new banner). */
private data class SessionFailureToken(val message: String)

/** The boost banner's identity (a restart of the auto-dismiss per boost). */
private data class BoostToken(val id: UUID)

/**
 * The floating tab bar: a glass capsule, icons over their labels, monochrome (the selected tab in ink,
 * filled; the others outlined, in body grey), badges in ink inside the icon's corner.
 */
@Composable
private fun TabBar(
    selected: AppModel.Tab,
    badges: Map<AppModel.Tab, Int>,
    onSelect: (AppModel.Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .fillMaxWidth()
            .height(TabBarHeight)
            .glass(shape)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (item in tabs) {
            val isOn = item.tab == selected
            val tint by animateColorAsState(if (isOn) DS.palette.ink else DS.palette.body, Motion.select(), label = "tab")
            val title = item.title()
            val count = badges[item.tab] ?: 0
            Box(
                Modifier
                    .weight(1f)
                    .height(TabBarHeight - 8.dp)
                    .background(if (isOn) DS.palette.ink.copy(alpha = 0.07f) else DS.palette.ink.copy(alpha = 0f), shape)
                    .pressScale(onClick = { if (!isOn) onSelect(item.tab) }, scale = 0.94f, role = Role.Tab)
                    .semantics {
                        this.selected = isOn
                        contentDescription = if (count > 0) "$title, $count" else title
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box {
                        DrafftIcon(if (isOn) item.filled else item.symbol, size = 24.dp, tint = tint)
                        if (count > 0) {
                            Text(
                                if (count > 99) "99+" else count.toString(),
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 10.dp, y = (-4).dp)
                                    .background(DS.palette.ink, CircleShape)
                                    .defaultMinSize(minWidth = 17.dp)
                                    .padding(horizontal = 5.dp),
                                color = DS.palette.onInk,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                    Text(title, color = tint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
