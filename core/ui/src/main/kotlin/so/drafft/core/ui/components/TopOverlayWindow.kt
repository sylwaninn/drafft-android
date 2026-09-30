package so.drafft.core.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion

// Port of Drafft/DesignSystem/TopOverlayWindow.swift.

/**
 * A window above the app's own, for banners that must show over everything: sheets, covers, the
 * keyboard. Touches only land on the banner itself; everywhere else they pass to the app (the
 * window is the banner's size and never takes focus, so the app keeps the keyboard).
 *
 * The app places it once at its root and says which banner shows ([banner]: the photo-refused
 * banner, a purchase on its way to the account, calendar access refused, a session change the
 * server turned down, in that order of priority; null for none) and how each one is drawn
 * ([content]). A banner slides in from the top (fades with Remove animations on); switching
 * banners cross-fades.
 */
@Composable
fun TopOverlayWindow(
    banner: Any?,
    modifier: Modifier = Modifier,
    content: @Composable (banner: Any) -> Unit,
) {
    val visible = remember { MutableTransitionState(false) }
    LaunchedEffect(banner != null) { visible.targetState = banner != null }
    // The last banner stays drawn while it slides out.
    val last = remember { arrayOfNulls<Any>(1) }
    if (banner != null) last[0] = banner
    if (!visible.currentState && !visible.targetState && visible.isIdle) return
    val shown = last[0] ?: return
    val reduceMotion = LocalReduceMotion.current
    // A window of its own gets no insets: the status bar's height is measured here, in the app's.
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + DS.Space.xs
    Popup(alignment = Alignment.TopCenter, properties = PopupProperties(focusable = false)) {
        Box(modifier.padding(top = top)) {
            AnimatedVisibility(
                visibleState = visible,
                enter = if (reduceMotion) fadeIn(tween(200, easing = Motion.EaseInOut)) else {
                    slideInVertically(Motion.bouncy<IntOffset>()) { -it * 2 } + fadeIn(Motion.bouncy())
                },
                exit = if (reduceMotion) fadeOut(tween(200, easing = Motion.EaseInOut)) else {
                    slideOutVertically(Motion.bouncy<IntOffset>()) { -it * 2 } + fadeOut(Motion.bouncy())
                },
            ) {
                AnimatedContent(
                    targetState = shown,
                    transitionSpec = { fadeIn(Motion.gentle()).togetherWith(fadeOut(Motion.gentle())) },
                    label = "topOverlay",
                ) { content(it) }
            }
        }
    }
}
