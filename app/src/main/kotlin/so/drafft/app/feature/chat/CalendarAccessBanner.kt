package so.drafft.app.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.semibold
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Port of Drafft/Features/Chat/CalendarAccessBanner.swift.

/**
 * Calendar access refused when the person tries to add a session: the event isn't added (it couldn't
 * follow the session), and this banner says why and opens Settings. Shown above everything
 * (`TopOverlayWindow`, placed by the root), never blocking: it swipes up and leaves on its own.
 */
object CalendarAccessNotice {
    private val scope = MainScope()

    var isShown by mutableStateOf(false)
        private set
    private var hide: Job? = null

    /** How long it stays unless swiped away or tapped. */
    val duration = 8.seconds

    fun show() {
        isShown = true
        hide?.cancel()
        hide = scope.launch {
            delay(duration)
            isShown = false
        }
    }

    fun dismiss() {
        hide?.cancel()
        isShown = false
    }
}

@Composable
fun CalendarAccessBanner(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NoticeBannerFrame(onDismiss, modifier) {
        BannerIcon("calendar-warning")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DS.Space.xs)) {
            Text(L("Calendar access is off for drafft."), style = TextStyles.headline, color = Color.White)
            PressScaleButton(
                onClick = {
                    Haptics.tap()
                    onOpenSettings()
                },
                scale = 0.97f,
            ) {
                Box(Modifier.defaultMinSize(minHeight = 44.dp), contentAlignment = Alignment.CenterStart) {
                    Text(L("Open Settings"), style = TextStyles.subheadline.semibold, color = DS.palette.accentOnNight)
                }
            }
        }
    }
}
