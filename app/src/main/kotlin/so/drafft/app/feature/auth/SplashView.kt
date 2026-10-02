package so.drafft.app.feature.auth

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import so.drafft.core.ui.components.Wordmark
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.Motion

/**
 * Launch: the session photos rotate full screen (the log-in's quick crossfade, faster here) under
 * the white wordmark, centred at the bottom. It stays long enough to
 * see a photo, then fades onto the first screen as soon as the app is ready.
 *
 * [isReady]: true once the first screen is ready (tabs mounted, session restored).
 */
@Composable
fun SplashView(isReady: Boolean, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    var shownLongEnough by remember { mutableStateOf(false) }
    var fading by remember { mutableStateOf(false) }
    val opacity = remember { Animatable(1f) }
    val finished by rememberUpdatedState(onFinished)
    val p = DS.palette

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = opacity.value }
            // Covers the first screen until it fades: nothing under it takes a touch meanwhile.
            .then(
                if (fading) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                },
            )
            .background(p.night)
            .clearAndSetSemantics { },
    ) {
        HeroSlideshow(photos = WelcomePhotos, modifier = Modifier.fillMaxSize(), interval = 2.5.seconds)
        // Flat dim: keeps the white word legible on bright photos.
        Box(Modifier.fillMaxSize().background(p.night.copy(alpha = 0.28f)))
        Wordmark(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = 48.dp),
            size = 34f,
            color = Color.White,
        )
    }

    LaunchedEffect(Unit) {
        delay(1.3.seconds)
        shownLongEnough = true
    }
    // Checked whenever either changes, never from a stale copy of `isReady`.
    LaunchedEffect(isReady, shownLongEnough) {
        if (isReady && shownLongEnough && !fading) fading = true
    }
    LaunchedEffect(fading) {
        if (!fading) return@LaunchedEffect
        opacity.animateTo(0f, tween(300, easing = Motion.EaseIn))
        finished()
    }
}
