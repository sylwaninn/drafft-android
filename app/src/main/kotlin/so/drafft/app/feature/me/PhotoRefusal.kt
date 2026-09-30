package so.drafft.app.feature.me

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.mp.KoinPlatform
import so.drafft.core.data.backend.ServerMessage
import so.drafft.core.data.moderation.PhotoModeration
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.DrafftButtonKind
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.LocalSheetDismiss
import so.drafft.core.ui.components.Photo
import so.drafft.core.ui.components.SheetDetent
import so.drafft.core.ui.components.bannerSurface
import so.drafft.core.ui.components.pressScale
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.NightSurface
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.semibold

// Port of Drafft/Features/Me/PhotoRefusal.swift.

/**
 * Why a photo was refused, in plain words (never the detected labels), with what to do next: ask a
 * person for a second look, or take it off the profile.
 *
 * [measuring] lays it out without its scroll view (on the iPhone, to measure the height the sheet
 * opens at; a `SheetDetent.FIT` sheet measures itself here).
 */
@Composable
fun PhotoRefusalSheet(
    refusal: PhotoModeration.Refusal,
    dismiss: () -> Unit,
    measuring: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (measuring) {
        RefusalContent(refusal, dismiss, modifier)
    } else {
        // Scrolls only if it's taller than the screen (large text, small phone): never cut.
        Box(modifier.background(DS.palette.sheetRaised).verticalScroll(rememberScrollState())) {
            RefusalContent(refusal, dismiss, Modifier)
        }
    }
}

@Composable
private fun RefusalContent(refusal: PhotoModeration.Refusal, dismiss: () -> Unit, modifier: Modifier) {
    val p = DS.palette
    val moderation = koinInject<PhotoModeration>()
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val badgeFill by animateColorAsState(if (sent) p.night else p.negative, Motion.snappy(), label = "refusalBadge")

    fun askForReview() {
        scope.launch {
            sending = true
            error = null
            try {
                moderation.requestReview(refusal.path)
                Haptics.success()
                sent = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = ServerMessage.text(e) ?: L("Couldn't send it. Check your connection and try again.")
            }
            sending = false
        }
    }

    // The same space above the photo as between the photo and the title.
    Column(
        modifier.fillMaxWidth().background(p.sheetRaised),
        verticalArrangement = Arrangement.spacedBy(DS.Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(top = DS.Space.xxl).clearAndSetSemantics { }) {
            val shape = RoundedCornerShape(DS.Radius.lg)
            Photo(refusal.path, Modifier.size(width = 96.dp, height = 128.dp).clip(shape), side = 96.dp)
            // Their own photo, softened: the moment is about the decision, not the picture.
            Box(Modifier.size(width = 96.dp, height = 128.dp).background(Color.Black.copy(alpha = 0.25f), shape))
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 8.dp, y = 8.dp)
                    .size(30.dp)
                    .background(badgeFill, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(sent, animationSpec = Motion.snappy(), label = "refusalGlyph") { isSent ->
                    DrafftIcon(if (isSent) "hourglass" else "nosign", size = 16.dp, tint = Color.White)
                }
            }
        }

        Column(
            Modifier.padding(horizontal = DS.Space.xl),
            verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Wraps instead of truncating.
            FadingText(
                if (sent) L("Thanks, we'll take a look") else L("This photo can't go on your profile"),
            ) { Text(it, style = display(26f), color = p.ink, textAlign = TextAlign.Center) }
            FadingText(
                if (sent) {
                    L("Someone from our team will review it, usually within 24 hours. It stays off your profile until then.")
                } else {
                    L("Our automatic check spotted something that doesn't fit our community guidelines, like nudity, violence or hateful symbols. Only you can see it. If we got it wrong, ask for a second look.")
                },
            ) { Text(it, style = TextStyles.body, color = p.body, textAlign = TextAlign.Center) }
            error?.let {
                Text(it, style = TextStyles.footnote.semibold, color = p.negative, textAlign = TextAlign.Center)
            }
        }

        // The next steps.
        Column(
            Modifier.padding(horizontal = DS.Space.xl).padding(bottom = DS.Space.lg),
            verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
        ) {
            if (sent) {
                DrafftButton(L("Got it"), onClick = dismiss)
            } else {
                DrafftButton(
                    L("Remove this photo"),
                    onClick = {
                        moderation.remove(refusal.path)
                        dismiss()
                    },
                    enabled = !sending,
                )
                DrafftButton(onClick = ::askForReview, kind = DrafftButtonKind.SECONDARY, enabled = !sending) {
                    if (sending) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = p.ink, strokeWidth = 2.dp, trackColor = Color.Transparent)
                    } else {
                        Text(L("Ask for a second look"))
                    }
                }
            }
        }
    }
}

/** `.contentTransition(.opacity)`: a changed text cross-fades in place. */
@Composable
private fun FadingText(text: String, content: @Composable (String) -> Unit) {
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(Motion.snappy()).togetherWith(fadeOut(Motion.snappy())) },
        label = "fadingText",
    ) { content(it) }
}

/**
 * In-app banner when a photo is refused (outside the app, a push says the same). Tap: the
 * explanation. Swipe up or wait: it goes away.
 */
@Composable
fun PhotoRefusalBanner(
    refusal: PhotoModeration.Refusal,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    // Written while dragging and read only when drawing: the drag never recomposes the banner.
    var dragY by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 30.dp.toPx() }

    LaunchedEffect(refusal.id) {
        delay(6.seconds)
        onDismiss()
    }

    Box(
        modifier
            .padding(horizontal = DS.Space.md)
            .graphicsLayer { translationY = minOf(0f, dragY) }
            .draggable(
                state = rememberDraggableState { dragY += it },
                orientation = Orientation.Vertical,
                onDragStopped = {
                    if (dragY < -threshold) {
                        onDismiss()
                    } else {
                        animate(dragY, 0f, animationSpec = Motion.snappy()) { v, _ -> dragY = v }
                    }
                },
            )
            .pressScale(onOpen, scale = 0.97f, onClickLabel = L("Explains why and what you can do")),
    ) {
        NightSurface {
            Row(
                Modifier
                    .fillMaxWidth()
                    .bannerSurface()
                    .padding(DS.Space.md)
                    .padding(end = DS.Space.sm),
                horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Photo(refusal.path, Modifier.size(width = 44.dp, height = 56.dp).clip(RoundedCornerShape(DS.Radius.sm)), side = 44.dp)
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 6.dp, y = 6.dp)
                            .size(20.dp)
                            .background(p.negative, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        DrafftIcon("nosign", size = 13.dp, tint = Color.White)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(L("A photo wasn't approved"), style = TextStyles.headline, color = Color.White)
                    Text(
                        L("It's not on your profile. Tap to see why."),
                        style = TextStyles.subheadline,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

/**
 * Shows the explanation above whatever is on screen (Edit profile, sign-up, a chat), without closing
 * it. On Android: [show] hands the refusal to `PhotoModeration.presentedRefusal` (where a tapped push
 * puts it too), and [Host], placed once at the app's root, presents it as a raised sheet sized to its
 * content, over any sheet already open.
 */
object PhotoRefusalPresenter {
    fun show(refusal: PhotoModeration.Refusal) {
        KoinPlatform.getKoin().get<PhotoModeration>().presentedRefusal = refusal
    }

    @Composable
    fun Host() {
        val moderation = koinInject<PhotoModeration>()
        val refusal = moderation.presentedRefusal ?: return
        // Already showing this one: the same sheet stays.
        key(refusal.id) {
            DrafftSheet(
                onDismissRequest = { if (moderation.presentedRefusal == refusal) moderation.presentedRefusal = null },
                detent = SheetDetent.FIT,
                raised = true,
            ) {
                PhotoRefusalSheet(refusal = refusal, dismiss = LocalSheetDismiss.current)
            }
        }
    }
}
