package so.drafft.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import so.drafft.core.model.Brand

/**
 * The app's theme: the appearance, the palette, and a Material colour scheme mapped onto it so the
 * few Material pieces used (text fields' cursor and selection, pickers, sliders) wear drafft's colours.
 */
@Composable
fun DrafftTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (darkTheme) Palette.Dark else Palette.Light
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = p.lime, onPrimary = p.onLime, primaryContainer = p.limePale, onPrimaryContainer = p.ink,
            secondary = p.ink, onSecondary = p.onInk, background = p.sage, onBackground = p.ink,
            surface = p.white, onSurface = p.ink, surfaceVariant = p.sage, onSurfaceVariant = p.body,
            surfaceContainerHigh = p.white, surfaceContainer = p.white, surfaceContainerLow = p.sage,
            outline = p.hairline, outlineVariant = p.hairline, error = p.negative, onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = p.lime, onPrimary = p.onLime, primaryContainer = p.limePale, onPrimaryContainer = p.ink,
            secondary = p.ink, onSecondary = p.onInk, background = p.sage, onBackground = p.ink,
            surface = p.white, onSurface = p.ink, surfaceVariant = p.sage, onSurfaceVariant = p.body,
            surfaceContainerHigh = p.white, surfaceContainer = p.white, surfaceContainerLow = p.sage,
            outline = p.hairline, outlineVariant = p.hairline, error = p.negative, onError = Color.White,
        )
    }
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(
                LocalContentColor provides p.ink,
                LocalTextSelectionColors provides TextSelectionColors(p.accentInk, p.accentInk.copy(alpha = 0.3f)),
                content = content,
            )
        }
    }
}

/**
 * Marks a sheet's content: page and block tones flip (the sheet is the lifted white, its blocks sink
 * into sage wells). Every sheet's content goes through it (`DrafftSheet` does it for you).
 */
@Composable
fun SheetSurface(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIsSheetSurface provides true, content = content)
}

/** Marks content drawn on a night surface: components swap the accent for `accentOnNight`. */
@Composable
fun NightSurface(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIsNightSurface provides true, LocalContentColor provides Color.White, content = content)
}

/** Leaves a sheet or night context (a light page shown from inside one). */
@Composable
fun PlainSurface(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIsSheetSurface provides false, LocalIsNightSurface provides false, content = content)
}

/**
 * Copy that may mention the brand: each "drafft" / "drafft tempo" (any case) is set as the brand, the
 * rest stays as written. [brandWeight] is one step above the sentence's weight; [tierColor] colours
 * "tempo" (a tier tone per surface: `limeNeutral` on light, `tierOnAccent` on an accent fill...).
 */
fun branded(
    text: String,
    brandWeight: FontWeight = FontWeight.SemiBold,
    tierColor: Color? = null,
): AnnotatedString = buildAnnotatedString {
    var rest = text
    while (true) {
        val at = rest.indexOf(Brand.NAME, ignoreCase = true)
        if (at < 0) break
        append(rest.substring(0, at))
        pushStyle(SpanStyle(fontWeight = brandWeight))
        append(Brand.NAME)
        pop()
        var after = rest.substring(at + Brand.NAME.length)
        val tier = " " + Brand.TIER
        if (after.startsWith(tier, ignoreCase = true)) {
            append(" ")
            pushStyle(SpanStyle(fontWeight = brandWeight, color = tierColor ?: Color.Unspecified))
            append(Brand.TIER)
            pop()
            after = after.substring(tier.length)
        }
        rest = after
    }
    append(rest)
}
