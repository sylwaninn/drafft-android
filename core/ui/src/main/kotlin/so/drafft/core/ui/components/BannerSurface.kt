package so.drafft.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import so.drafft.core.ui.theme.DS

// Port of BannerSurface (Drafft/Features/Matches/MatchViews.swift).

/**
 * Surface for in-app banners (match, boost, notices): solid night, a thin light rim and a layered
 * shadow, so it lifts off any page, light or dark. No gradient in the fill. Wrap the banner's content
 * in `NightSurface { }` too.
 */
@Composable
fun Modifier.bannerSurface(): Modifier {
    val shape = RoundedCornerShape(DS.Radius.xl)
    return this
        .shadow(28.dp, shape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
        .shadow(4.dp, shape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
        .background(DS.palette.night, shape)
        .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
}
