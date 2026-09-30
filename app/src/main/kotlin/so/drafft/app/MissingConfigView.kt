package so.drafft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.display

/**
 * A build that can't reach a backend (the local flavor without its URL and key in local.properties)
 * stops here and says what's missing, like the iPhone's launch check. Developer-facing: not translated.
 */
@Composable
fun MissingConfigView(missing: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().background(DS.palette.canvasSoft).safeDrawingPadding().padding(DS.Space.xl),
        verticalArrangement = Arrangement.spacedBy(DS.Space.md),
    ) {
        Text("Backend not configured", style = display(28f), color = DS.palette.ink)
        Text(
            "Set these in local.properties (see app/build.gradle.kts), then build again:",
            style = TextStyles.body, color = DS.palette.body,
        )
        missing.forEach { Text(it, style = TextStyles.body, color = DS.palette.ink) }
    }
}
