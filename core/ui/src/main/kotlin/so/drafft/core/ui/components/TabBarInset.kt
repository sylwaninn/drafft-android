package so.drafft.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Room taken by the floating tab bar at the bottom of a tab (above the system navigation bar). Tab
 * screens scroll under it and end their content with this much extra space, like the iPhone's safe area.
 */
val LocalTabBarInset = compositionLocalOf<Dp> { 0.dp }

/**
 * Whether the floating tab bar shows (`.toolbarVisibility(.hidden, for: .tabBar)`): a tab's stack
 * hides it while a screen is pushed over its root (a chat), and it comes back as soon as Back begins.
 */
@Stable
class TabBarVisibility {
    private val hiders = mutableStateMapOf<Any, Unit>()

    val hidden: Boolean get() = hiders.isNotEmpty()

    fun set(owner: Any, hidden: Boolean) {
        if (hidden) hiders[owner] = Unit else hiders.remove(owner)
    }
}

/** Provided by `MainTabs`; elsewhere a stand-in that nothing reads. */
val LocalTabBarVisibility = staticCompositionLocalOf { TabBarVisibility() }

/** Hides the tab bar while [hidden] (and while this is composed). */
@Composable
fun HidesTabBar(hidden: Boolean) {
    val visibility = LocalTabBarVisibility.current
    val owner = remember { Any() }
    DisposableEffect(visibility, hidden) {
        visibility.set(owner, hidden)
        onDispose { visibility.set(owner, false) }
    }
}
