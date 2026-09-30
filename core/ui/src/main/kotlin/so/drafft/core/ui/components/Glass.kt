package so.drafft.core.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.LocalDarkTheme

/**
 * The stand-in for Liquid Glass (`.glassEffect(.regular)`): a translucent fill (white 70 % in light
 * mode, white 12 % in dark mode) with a hairline rim, so controls read over any content scrolling
 * under a bar, where a faint tint vanished. [tint] colours the glass (`.regular.tint(...)`: the
 * accent clear button of a search field).
 *
 * It only draws: put it on the control's own frame, so the whole frame stays the touch target
 * (glass never takes or blocks a tap by itself).
 */
@Composable
fun Modifier.glass(shape: Shape, tint: Color? = null): Modifier {
    val dark = LocalDarkTheme.current
    val fill = tint?.copy(alpha = tint.alpha * 0.92f) ?: Color.White.copy(alpha = if (dark) 0.12f else 0.7f)
    val rim = if (dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.08f)
    return this
        .background(fill, shape)
        .border(0.5.dp, rim, shape)
}

/**
 * A round glass button with an SF Symbol (close, search, more): ink glyph, never the accent
 * (utility controls are ink). The whole [size] frame takes the touch.
 */
@Composable
fun GlassCircleButton(
    symbol: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    contentDescription: String? = null,
    enabled: Boolean = true,
    tint: Color? = null,
    glyph: Color = DS.palette.ink,
) {
    Box(
        modifier
            .size(size)
            .pressScale(onClick, enabled = enabled)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .glass(CircleShape, tint),
        contentAlignment = Alignment.Center,
    ) {
        // `.body.weight(.semibold)`.
        DrafftIcon(symbol, size = symbolBox(17f), tint = glyph)
    }
}
