package so.drafft.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import so.drafft.core.model.Brand
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.display

// Port of Wordmark (Drafft/Features/Auth/WelcomeView.swift).

/**
 * "drafft" set in the display face with a trailing ghost: the drafting motif from the app icon. Same as
 * the logo: the word solid, two copies trailing behind it (20% and 45%). [trailStrength] scales both
 * ghosts: 1 is the logo; lower keeps the motif quiet in the app's chrome.
 */
@Composable
fun Wordmark(
    modifier: Modifier = Modifier,
    size: Float = 28f,
    color: Color = DS.palette.lime,
    trail: Color? = null,
    trailStrength: Float = 1f,
) {
    val ghost = trail ?: color
    val style = display(size).copy(letterSpacing = (-0.02).em)
    Box(modifier.clearAndSetSemantics { contentDescription = Brand.NAME }) {
        Text(Brand.NAME, Modifier.offset(x = (-size * 0.21f).dp), color = ghost.copy(alpha = ghost.alpha * 0.2f * trailStrength), style = style, maxLines = 1, softWrap = false)
        Text(Brand.NAME, Modifier.offset(x = (-size * 0.104f).dp), color = ghost.copy(alpha = ghost.alpha * 0.45f * trailStrength), style = style, maxLines = 1, softWrap = false)
        Text(Brand.NAME, color = color, style = style, maxLines = 1, softWrap = false)
    }
}
