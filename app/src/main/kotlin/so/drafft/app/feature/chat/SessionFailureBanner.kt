package so.drafft.app.feature.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import so.drafft.core.model.L
import so.drafft.core.ui.components.bannerSurface
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.NightSurface
import so.drafft.core.ui.theme.TextStyles
import kotlinx.coroutines.launch

// Port of Drafft/Features/Chat/SessionFailureBanner.swift. The notice itself (`SessionFailureNotice`,
// what the banner says and when it leaves) is core:data's (so.drafft.core.data.sessions).

/**
 * A session change the server turned down or couldn't receive: the card is already back as it was
 * (`SessionStore`), and this banner says why, in the app's words for the server's code. Shown above
 * everything (`TopOverlayWindow`), never blocking: it swipes up and leaves on its own.
 */
@Composable
fun SessionFailureBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NoticeBannerFrame(onDismiss, modifier) {
        BannerIcon("calendar.badge.exclamationmark")
        Text(message, Modifier.weight(1f), style = TextStyles.headline, color = Color.White)
    }
}

/** The round accent disc on a notice banner (title3 bold glyph). */
@Composable
internal fun BannerIcon(symbol: String) {
    Box(
        Modifier
            .size(48.dp)
            .background(DS.palette.accentOnNight, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        DrafftIcon(symbol, size = 24.dp, tint = DS.palette.onAccentOnNight)
    }
}

/**
 * A notice banner's frame: `BannerSurface`, swipe up to dismiss (past 30 dp; otherwise it springs
 * back), and a "Dismiss" accessibility action.
 */
@Composable
internal fun NoticeBannerFrame(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    val dragY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val dismissLabel = L("Dismiss")
    Box(modifier.padding(horizontal = DS.Space.md)) {
        NightSurface {
            Row(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationY = minOf(0f, dragY.value) }
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (total / density < -30f) {
                                    dismiss()
                                } else {
                                    scope.launch { dragY.animateTo(0f, Motion.snappy()) }
                                }
                            },
                            onDragCancel = { scope.launch { dragY.animateTo(0f, Motion.snappy()) } },
                        ) { change, amount ->
                            change.consume()
                            total += amount
                            scope.launch { dragY.snapTo(total) }
                        }
                    }
                    .bannerSurface()
                    .semantics { customActions = listOf(CustomAccessibilityAction(dismissLabel) { dismiss(); true }) }
                    .padding(start = DS.Space.md, top = DS.Space.md, bottom = DS.Space.md, end = DS.Space.md + DS.Space.sm),
                horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}
