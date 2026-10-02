package so.drafft.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import so.drafft.core.data.telemetry.AnalyticsEvent
import so.drafft.core.data.telemetry.Screen
import so.drafft.core.data.telemetry.ScreenTracker
import so.drafft.core.data.telemetry.Telemetry
import so.drafft.core.ui.components.LocalTabIsCurrent
import so.drafft.core.ui.components.LocalTabsOnScreen

// Ports Drafft/Services/Telemetry/TrackScreen.swift.

/**
 * Counts [screen] as on show while it's composed in the current tab (every tab stays composed: a chat
 * left open in Chats isn't on show while Discover is), and not while the tabs are hidden (under the
 * welcome screen, sign-up or a hold). Put it at the top of a pushed screen, a sheet's content or a
 * cover's. [properties]: codes and numbers only (`PrivacyGuard`).
 */
@Composable
fun TrackScreen(screen: Screen, properties: Map<String, Any> = emptyMap()) {
    val onShow = LocalTabIsCurrent.current && LocalTabsOnScreen.current
    DisposableEffect(screen, onShow) {
        val token = if (onShow) ScreenTracker.enter(screen, properties) else null
        onDispose { token?.let(ScreenTracker::leave) }
    }
}

/**
 * A paywall or a packs sheet on show: `paywall_viewed` with where it was opened from, then
 * `paywall_dismissed` with whether something was bought, and the screen itself. The sheet is on show
 * whatever tab it was opened from: it enters unconditionally.
 */
@Composable
fun TrackPaywall(kind: AnalyticsEvent.ProductKind, screen: Screen = Screen.PAYWALL, purchased: () -> Boolean) {
    // Read before this sheet counts as on show: the screen it was opened from.
    val from = remember { ScreenTracker.current }
    val bought by rememberUpdatedState(purchased)
    DisposableEffect(Unit) {
        Telemetry.track(AnalyticsEvent.PaywallViewed(kind, fromScreen = from))
        val token = ScreenTracker.enter(screen)
        onDispose {
            ScreenTracker.leave(token)
            Telemetry.track(AnalyticsEvent.PaywallDismissed(kind, purchased = bought()))
        }
    }
}
