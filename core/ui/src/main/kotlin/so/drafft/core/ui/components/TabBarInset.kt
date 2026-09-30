package so.drafft.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Room taken by the floating tab bar at the bottom of a tab (above the system navigation bar). Tab
 * screens scroll under it and end their content with this much extra space, like the iPhone's safe area.
 * 0 while the bar is hidden (a pushed chat).
 */
val LocalTabBarInset = compositionLocalOf<Dp> { 0.dp }

/** Per tab: how many screens on it want the tab bar hidden right now. */
@Stable
class TabBarVisibility {
    var hiders by mutableIntStateOf(0)
        internal set
    val isHidden: Boolean get() = hiders > 0
}

val LocalTabBarVisibility = compositionLocalOf<TabBarVisibility?> { null }

/**
 * False inside a tab that isn't the one on screen. Every tab stays composed, so what SwiftUI does in
 * `onAppear`/`onDisappear` on a tab switch (a chat counting as open, say) follows this instead.
 */
val LocalTabIsCurrent = compositionLocalOf { true }

/**
 * Hides the tab bar while the calling screen is shown (iOS `.toolbarVisibility(.hidden, for: .tabBar)`):
 * a chat pushed from Sessions or Chats.
 */
@Composable
fun HidesTabBar() {
    val visibility = LocalTabBarVisibility.current ?: return
    DisposableEffect(visibility) {
        visibility.hiders++
        onDispose { visibility.hiders-- }
    }
}
