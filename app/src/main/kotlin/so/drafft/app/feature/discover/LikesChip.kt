package so.drafft.app.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import so.drafft.core.model.L
import so.drafft.core.model.Profile
import so.drafft.core.ui.components.Photo
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.RollingText
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.heavy
import so.drafft.core.ui.theme.monospacedDigits

// Port of Drafft/Features/Discover/LikesChip.swift.

/**
 * Header chip on Discover: who already liked you. Two stacked faces (blurred until drafft tempo)
 * and the count. Opens the likes grid, or the paywall.
 */
@Composable
fun LikesChip(
    likers: List<Profile>,
    unlocked: Boolean,
    action: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    PressScaleButton(
        onClick = action,
        modifier = modifier.defaultMinSize(minHeight = 44.dp),
        scale = 0.94f,
        contentDescription = if (unlocked) {
            L("%d people like you. See who", likers.size)
        } else {
            L("%d people like you. Unlock with drafft tempo", likers.size)
        },
    ) {
        Row(
            Modifier
                .defaultMinSize(minHeight = 36.dp)
                .background(p.canvas, CircleShape)
                .padding(start = 6.dp, end = DS.Space.md),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy((-9).dp)) {
                likers.take(2).forEachIndexed { i, liker ->
                    Box(Modifier.zIndex((2 - i).toFloat())) {
                        Photo(
                            liker.portrait,
                            Modifier.size(24.dp).clip(CircleShape).border(2.dp, p.canvas, CircleShape),
                            side = 24.dp,
                            blur = if (unlocked) 0.dp else 4.dp,
                        )
                    }
                }
            }
            RollingText("${likers.size}", style = TextStyles.subheadline.heavy.monospacedDigits, color = p.ink, maxLines = 1)
            // An accent dot says "new", inside the chip: the one accent on it.
            Box(Modifier.size(8.dp).background(p.lime, CircleShape))
        }
    }
}
