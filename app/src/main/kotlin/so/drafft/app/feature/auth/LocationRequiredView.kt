package so.drafft.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import so.drafft.core.model.L
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.platform.LocalPlatformUi
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.NightSurface
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.branded
import so.drafft.core.ui.theme.display

// Port of Drafft/Features/Auth/LocationRequiredView.swift.

/**
 * Blocking screen when location is off: drafft can't show people nearby without it.
 * No close button; it goes away by itself once location is back on.
 */
@Composable
fun LocationRequiredView(modifier: Modifier = Modifier) {
    val openSettings = LocalPlatformUi.current.rememberOpenAppSettings()
    val p = DS.palette
    Box(modifier.fillMaxSize().background(p.night)) {
        NightSurface {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(DS.Space.xl),
                verticalArrangement = Arrangement.spacedBy(DS.Space.xl),
            ) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(72.dp).background(p.accentOnNight, CircleShape), contentAlignment = Alignment.Center) {
                    // `.system(size: 32, weight: .bold)`.
                    DrafftIcon("location.slash.fill", size = (32f * 1.2f).dp, tint = p.onAccentOnNight)
                }
                Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                    Text(
                        L("Turn location back on."),
                        Modifier.semantics { heading() },
                        style = display(40f),
                        color = p.accentOnNight,
                    )
                    Text(
                        branded(L("drafft needs it to show people near you. While Using the App is enough, and only your area is ever shown.")),
                        style = TextStyles.body,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
                Spacer(Modifier.weight(1f))
                DrafftButton(onClick = openSettings) {
                    DrafftIcon("gearshape.fill", size = (17f * 1.2f).dp, tint = LocalContentColor.current)
                    Text(L("Open Settings"), maxLines = 2)
                }
            }
        }
    }
}
