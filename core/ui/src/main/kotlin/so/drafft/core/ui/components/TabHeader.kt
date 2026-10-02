package so.drafft.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.display
import kotlin.math.roundToInt

/**
 * Shared header for top-level tabs, instead of a large-title bar. The title sits right under the
 * status bar and shrinks as content scrolls. Attach it with `TopBar(scroll, bar = { TabHeader(...) })`:
 * content passes under it through the progressive blur. An optional search field ([search] non-null)
 * sits under the title.
 *
 * [offset] is the scroll distance past the top, in dp (0 at rest; see [trackingScrollOffset]). It is
 * read at draw time, so scrolling never recomposes the header.
 */
@Composable
fun TabHeader(
    offset: () -> Float,
    modifier: Modifier = Modifier,
    search: String? = null,
    onSearchChange: (String) -> Unit = {},
    searchPrompt: String = L("Search"),
    trailing: @Composable RowScope.() -> Unit = {},
    leading: @Composable () -> Unit,
) {
    var searchExpanded by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    // The search field no longer folds on scroll: changing the header's height moved the list under
    // the finger (a visible jump when scrolling back up).
    val folded = false
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val showsSearchField = search != null && (!folded || searchExpanded || searchFocused || search.isNotEmpty())
    val p = DS.palette

    Column(
        modifier
            .fillMaxWidth()
            .padding(start = DS.Space.lg, end = DS.Space.lg, top = DS.Space.xs, bottom = DS.Space.sm),
        verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.CenterVertically) {
            // Fixed height: only the scale changes, so the bar (and the content padding) stay put.
            Box(
                Modifier
                    .height(44.dp)
                    .weight(1f, fill = false)
                    .graphicsLayer {
                        val collapse = (offset() / 56f).coerceIn(0f, 1f)
                        val s = 1f - collapse * 0.32f
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    },
                contentAlignment = Alignment.CenterStart,
            ) { leading() }
            Spacer(Modifier.weight(1f, fill = true).widthIn(min = DS.Space.sm))
            AnimatedVisibility(
                visible = search != null && !showsSearchField,
                enter = scaleIn(Motion.snappy()) + fadeIn(Motion.snappy()),
                exit = scaleOut(Motion.snappy()) + fadeOut(Motion.snappy()),
            ) {
                GlassCircleButton(
                    "magnifier",
                    onClick = {
                        Haptics.tap()
                        searchExpanded = true
                        focus.requestFocus()
                    },
                    size = 40.dp,
                    modifier = Modifier.padding(2.dp),
                    contentDescription = searchPrompt,
                )
            }
            trailing()
        }

        AnimatedVisibility(
            visible = search != null && showsSearchField,
            enter = expandVertically(Motion.snappy()) + fadeIn(Motion.snappy()),
            exit = shrinkVertically(Motion.snappy()) + fadeOut(Motion.snappy()),
        ) {
            val text = search.orEmpty()
            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 40.dp)
                        // Glass: it reads over anything scrolling under the header, where a faint tint vanished.
                        .glass(CircleShape)
                        // The whole capsule focuses the field, icon and padding included.
                        .clickable(remember { MutableInteractionSource() }, indication = null) { focus.requestFocus() }
                        .padding(horizontal = DS.Space.md),
                    horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DrafftIcon("magnifier", tint = p.body)
                    BasicTextField(
                        value = text,
                        onValueChange = onSearchChange,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focus)
                            .onFocusChanged { searchFocused = it.isFocused },
                        textStyle = TextStyles.body.copy(color = p.ink),
                        singleLine = true,
                        cursorBrush = SolidColor(p.accentInk),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                        decorationBox = { inner ->
                            Box(Modifier.defaultMinSize(minHeight = 40.dp), contentAlignment = Alignment.CenterStart) {
                                if (text.isEmpty()) Text(searchPrompt, style = TextStyles.body, color = p.mute, maxLines = 1)
                                inner()
                            }
                        },
                    )
                }
                AnimatedVisibility(
                    visible = text.isNotEmpty() || searchExpanded,
                    enter = scaleIn(Motion.bouncy(), initialScale = 0.4f) + fadeIn(Motion.bouncy()),
                    exit = scaleOut(Motion.bouncy(), targetScale = 0.4f) + fadeOut(Motion.bouncy()),
                ) {
                    GlassCircleButton(
                        "close",
                        onClick = {
                            Haptics.tap()
                            onSearchChange("")
                            searchExpanded = false
                            focusManager.clearFocus()
                        },
                        size = 40.dp,
                        contentDescription = L("Clear search"),
                        tint = p.lime,
                        glyph = p.onLime,
                    )
                }
            }
        }
    }
}

/**
 * Title text used as a [TabHeader] leading view. Like the status bar, it turns white over a night
 * block or a photo scrolling under it, and back to ink over the page ([LocalEdgeTone]), with a
 * 0.2 s fade. One line: a longer language shrinks it (down to 0.75), never "…".
 */
@Composable
fun TabTitle(text: String, modifier: Modifier = Modifier) {
    val tone = LocalEdgeTone.current
    val color by animateColorAsState(tone?.ink ?: DS.palette.ink, tween(200, easing = Motion.EaseOut), label = "tabTitle")
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val base = display(34f)
        val max = constraints.maxWidth
        val style = remember(text, max) {
            val width = measurer.measure(text, base, maxLines = 1, softWrap = false).size.width
            val scale = if (width > max && width > 0) (max.toFloat() / width).coerceAtLeast(0.75f) else 1f
            if (scale < 1f) base.copy(fontSize = base.fontSize * scale, lineHeight = base.lineHeight * scale) else base
        }
        Text(text, Modifier.semantics { heading() }, style = style, color = color, maxLines = 1, softWrap = false)
    }
}

/**
 * How far this scroll view has moved past its top, in dp, for [TabHeader]. Only the fold range
 * matters: clamped to 56 and rounded, so scrolling further doesn't recompose anything. Content that
 * fits on screen doesn't scroll, so its overscroll never folds the title.
 */
@Composable
fun ScrollState.trackingScrollOffset(): State<Float> {
    val density = LocalDensity.current
    return remember(this, density) {
        derivedStateOf {
            if (maxValue <= 0) 0f else with(density) { value.toDp().value }.coerceIn(0f, 56f).roundToInt().toFloat()
        }
    }
}

/** [trackingScrollOffset] for a lazy list. */
@Composable
fun LazyListState.trackingScrollOffset(): State<Float> {
    val density = LocalDensity.current
    return remember(this, density) {
        derivedStateOf {
            when {
                !canScrollForward && !canScrollBackward -> 0f
                firstVisibleItemIndex > 0 -> 56f
                else -> with(density) { firstVisibleItemScrollOffset.toDp().value }.coerceIn(0f, 56f).roundToInt().toFloat()
            }
        }
    }
}
