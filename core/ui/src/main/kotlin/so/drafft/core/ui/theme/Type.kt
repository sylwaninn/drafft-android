package so.drafft.core.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import so.drafft.core.ui.R

/**
 * Display face: Inter Display Black stands in for the proprietary Wise Sans (DESIGN.md substitute),
 * ExtraBold for inline titles. Everything else is the system text face at the iPhone's text-style
 * sizes, in sp so the phone's font size setting scales it (Dynamic Type).
 */
object DisplayFont {
    val black = FontFamily(Font(R.font.inter_display_black, FontWeight.Black))
    val extraBold = FontFamily(Font(R.font.inter_display_extrabold, FontWeight.ExtraBold))
}

private val tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/** Heavy 900 display at [size] (sp), with the tight leading of 900 display (≈ 0.88× size). */
fun display(size: Float): TextStyle = TextStyle(
    fontFamily = DisplayFont.black,
    fontWeight = FontWeight.Black,
    fontSize = size.sp,
    lineHeight = (size * 0.88f).sp,
    letterSpacing = (-0.01).em,
    lineHeightStyle = tight,
)

fun displayBold(size: Float): TextStyle = TextStyle(
    fontFamily = DisplayFont.extraBold,
    fontWeight = FontWeight.ExtraBold,
    fontSize = size.sp,
    lineHeight = (size * 1.1f).sp,
)

/** The iPhone's text styles (size, leading, weight), used where SwiftUI uses `.font(.body)` etc. */
object TextStyles {
    private fun style(size: Float, leading: Float, weight: FontWeight = FontWeight.Normal, tracking: TextUnit = TextUnit.Unspecified) =
        TextStyle(fontSize = size.sp, lineHeight = leading.sp, fontWeight = weight, letterSpacing = tracking)

    val largeTitle = style(34f, 41f)
    val title = style(28f, 34f)
    val title2 = style(22f, 28f)
    val title3 = style(20f, 25f)
    val headline = style(17f, 22f, FontWeight.SemiBold)
    val body = style(17f, 22f)
    val callout = style(16f, 21f)
    val subheadline = style(15f, 20f)
    val footnote = style(13f, 18f)
    val caption = style(12f, 16f)
    val caption2 = style(11f, 13f)
}

/** `.weight(...)` like SwiftUI's `.fontWeight`. */
fun TextStyle.weight(weight: FontWeight): TextStyle = copy(fontWeight = weight)
val TextStyle.semibold: TextStyle get() = copy(fontWeight = FontWeight.SemiBold)
val TextStyle.bold: TextStyle get() = copy(fontWeight = FontWeight.Bold)
val TextStyle.heavy: TextStyle get() = copy(fontWeight = FontWeight.ExtraBold)
val TextStyle.medium: TextStyle get() = copy(fontWeight = FontWeight.Medium)

/** Monospaced digits (timers, counters). */
val TextStyle.monospacedDigits: TextStyle get() = copy(fontFeatureSettings = "tnum")

