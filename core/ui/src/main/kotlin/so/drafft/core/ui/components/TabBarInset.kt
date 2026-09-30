package so.drafft.core.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Room taken by the floating tab bar at the bottom of a tab (above the system navigation bar). Tab
 * screens scroll under it and end their content with this much extra space, like the iPhone's safe area.
 */
val LocalTabBarInset = compositionLocalOf<Dp> { 0.dp }
