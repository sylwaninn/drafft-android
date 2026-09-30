package so.drafft.core.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.Motion

// Port of Drafft/DesignSystem/FocusScrollView.swift.

/**
 * Lets a field bring itself into view when it gets focus, above the keyboard and above any pinned
 * bottom bar (which the system's own avoidance doesn't account for). Positions live in plain
 * references, never in state: nothing re-renders while the page scrolls.
 */
@Stable
class FocusScroller internal constructor(private val state: ScrollState, private val scope: CoroutineScope) {
    internal var viewport: LayoutCoordinates? = null

    /** Scrolls [target] to the upper third of the visible area. */
    fun reveal(target: () -> LayoutCoordinates?) {
        scope.launch {
            // Let the keyboard and the bar settle first.
            delay(300)
            val view = viewport?.takeIf { it.isAttached } ?: return@launch
            val item = target()?.takeIf { it.isAttached } ?: return@launch
            val itemTop = item.positionInRoot().y - view.positionInRoot().y
            val delta = itemTop - view.size.height * 0.3f
            state.animateScrollTo((state.value + delta.toInt()).coerceIn(0, state.maxValue), Motion.snappy())
        }
    }
}

/** The `FocusScrollView` around the current content, if any. */
val LocalFocusScroller = staticCompositionLocalOf<FocusScroller?> { null }

/**
 * Inside a [FocusScrollView]: when this field gets focus, it scrolls to the upper third, never
 * flush against the keyboard.
 */
@Composable
fun Modifier.revealsOnFocus(focused: Boolean): Modifier {
    val scroller = LocalFocusScroller.current ?: return this
    val anchor = remember { Anchor() }
    LaunchedEffect(focused) { if (focused) scroller.reveal { anchor.coordinates } }
    return onGloballyPositioned { anchor.coordinates = it }
}

/** Same, for a field with no focus state of its own. */
@Composable
fun Modifier.revealsOnFocus(): Modifier {
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.hasFocus }.revealsOnFocus(focused)
}

private class Anchor {
    var coordinates: LayoutCoordinates? = null
}

/**
 * A vertically scrolling column whose fields ([DrafftField], or any field with `revealsOnFocus`)
 * scroll themselves into view on focus. The content always ends with a margin, so even the last
 * field keeps room between it and the keyboard. [contentPadding] is where edge bars pass their
 * heights (`EdgeBars`).
 */
@Composable
fun FocusScrollView(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    contentPadding: PaddingValues = PaddingValues(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scroller = remember(state, scope) { FocusScroller(state, scope) }
    CompositionLocalProvider(LocalFocusScroller provides scroller) {
        Column(
            modifier
                .fillMaxSize()
                .onGloballyPositioned { scroller.viewport = it }
                .verticalScroll(state)
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
        ) {
            content()
            Spacer(Modifier.height(DS.Space.xl))
        }
    }
}
