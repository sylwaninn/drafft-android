package so.drafft.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.NightSurface
import so.drafft.core.ui.theme.heavy
import so.drafft.core.ui.theme.monospacedDigits

/**
 * `.nightBlock()`: a night block, the rounded night fill with its content marked as on night
 * (buttons, check discs and avatar rings switch to `accentOnNight` by themselves).
 */
@Composable
fun NightBlock(
    modifier: Modifier = Modifier,
    radius: Dp = DS.Radius.xl,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.draftBlock(DS.palette.night, radius)) {
        NightSurface { content() }
    }
}

/** Rounded coloured block behind its content, with the faint dark-mode hairline (`blockEdge`). */
@Composable
fun Modifier.draftBlock(fill: Color, radius: Dp = DS.Radius.xl): Modifier {
    val shape = RoundedCornerShape(radius)
    return background(fill, shape).border(1.dp, DS.palette.blockEdge, shape)
}

/** Round icon badge carrying a bold icon. */
@Composable
fun DraftGlyph(
    symbol: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    fill: Color = DS.palette.lime,
    glyph: Color = DS.palette.onLime,
) {
    Box(
        modifier
            .size(size)
            .background(fill, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        DrafftIcon(symbol, size = symbolBox(size.value * 0.38f), tint = glyph)
    }
}

/** The drafft tempo mark: a "+" drawn as a four-point spark, stretched wide. */
class SparkPlus(
    /** How pinched the sides are: 0 = sharp star, 1 = diamond. */
    private val pinch: Float = 0.16f,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cx = size.width / 2
        val cy = size.height / 2
        val dx = size.width / 2 * pinch
        val dy = size.height / 2 * pinch
        val p = Path().apply {
            moveTo(cx, 0f)
            quadraticTo(cx + dx, cy - dy, size.width, cy)
            quadraticTo(cx + dx, cy + dy, cx, size.height)
            quadraticTo(cx - dx, cy + dy, 0f, cy)
            quadraticTo(cx - dx, cy - dy, cx, 0f)
            close()
        }
        return Outline.Generic(p)
    }

    override fun equals(other: Any?) = other is SparkPlus && other.pinch == pinch
    override fun hashCode() = pinch.hashCode()
}

/** Super like mark: a single heart. */
@Composable
fun SuperLikeMark(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = DS.palette.lime,
) {
    DrafftIcon("heart", modifier.clearAndSetSemantics { }, size = symbolBox(size.value), tint = color)
}

/**
 * Round super-like button face: the mark with what's left written under it, both inside the
 * disc (a count never hangs off a button's edge). No number at zero.
 */
@Composable
fun SuperLikeCountMark(
    count: Int,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
) {
    val lift by animateDpAsState(if (count > 0) size * 0.02f else 0.dp, Motion.snappy(), label = "superLikeLift")
    val numberStyle = TextStyle(fontSize = (size * 0.21f).fixedSp()).heavy.monospacedDigits
    Box(modifier.size(size).clip(CircleShape).background(DS.palette.negative), contentAlignment = Alignment.Center) {
        Column(
            Modifier.offset(y = lift),
            verticalArrangement = Arrangement.spacedBy(1.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SuperLikeMark(size = size * 0.3f, color = Color.White)
            // The last number stays drawn while it fades out.
            val last = remember { IntArray(1) }
            if (count > 0) last[0] = count
            AnimatedVisibility(count > 0, enter = fadeIn(Motion.snappy()), exit = fadeOut(Motion.snappy())) {
                RollingText("${last[0]}", style = numberStyle, color = Color.White)
            }
        }
    }
}
