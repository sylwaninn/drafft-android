package so.drafft.app.feature.matches

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.IntOffset
import so.drafft.core.data.AppModel
import so.drafft.core.data.telemetry.Screen
import so.drafft.core.model.L
import so.drafft.core.model.Profile
import so.drafft.core.ui.TrackScreen
import so.drafft.core.ui.components.Avatar
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.Photo
import so.drafft.core.ui.components.pressScale
import so.drafft.core.ui.components.TextLinkButton
import so.drafft.core.ui.components.bannerSurface
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.NightSurface
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.semibold

// BannerSurface is core:ui's `Modifier.bannerSurface()`.

/** Full-screen match moment: two portraits slide into drafting formation, one tucked behind the other. */
@Composable
fun MatchView(
    profile: Profile,
    me: Profile,
    onChat: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TrackScreen(Screen.MATCH)
    val reduceMotion = LocalReduceMotion.current
    var arrived by remember { mutableStateOf(reduceMotion) }
    var trails by remember { mutableStateOf(reduceMotion) }
    LaunchedEffect(Unit) {
        arrived = true
        trails = true
    }
    // Reduce Motion: everything already in place.
    val arrival by animateFloatAsState(
        if (arrived) 1f else 0f,
        if (reduceMotion) tween(0) else Motion.springOf(0.4, 0.75f),
        label = "arrival",
    )
    val trail by animateFloatAsState(
        if (trails) 1f else 0f,
        if (reduceMotion) tween(0) else tween(500, delayMillis = 150, easing = Motion.EaseOut),
        label = "trails",
    )
    val shared = remember(profile, me) {
        profile.sports.map { it.sport }.firstOrNull { s -> me.sports.any { it.sport == s } }
    }
    val subtitle = if (shared != null) {
        L("You and %s both do %s. Say hi, or propose a session while it's fresh.", profile.name, shared.inSentence)
    } else {
        L("%s likes you too. Say hi, or propose a session while it's fresh.", profile.name)
    }

    NightSurface {
        Box(modifier.fillMaxSize().background(DS.palette.night)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = DS.Space.xl)
                    .padding(bottom = DS.Space.sm),
                verticalArrangement = Arrangement.spacedBy(DS.Space.xl),
            ) {
                Spacer(Modifier.heightIn(min = DS.Space.lg).weight(1f))

                Formation(profile, me, { arrival }, { trail }, Modifier.fillMaxWidth().height(300.dp))

                Column(
                    Modifier.graphicsLayer {
                        alpha = arrival.coerceIn(0f, 1f)
                        translationY = (1f - arrival) * 24.dp.toPx()
                    },
                    verticalArrangement = Arrangement.spacedBy(DS.Space.md),
                ) {
                    Text(
                        L("It's mutual."),
                        Modifier.semantics { heading() },
                        style = display(64f),
                        color = DS.palette.accentOnNight,
                        maxLines = 2,
                    )
                    Text(subtitle, style = TextStyles.body, color = Color.White.copy(alpha = 0.75f))
                }

                Spacer(Modifier.weight(1f))

                Column(
                    Modifier.graphicsLayer { alpha = arrival.coerceIn(0f, 1f) },
                    verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
                ) {
                    DrafftButton(onClick = onChat) {
                        DrafftIcon("chat-round-line", size = 20.dp, tint = LocalContentColor.current)
                        Text(L("Say hi"), maxLines = 2)
                    }
                    TextLinkButton(
                        text = L("Keep swiping"),
                        onClick = onClose,
                        color = Color.White,
                        style = TextStyles.body.semibold,
                        minHeight = 52.dp,
                        fullWidth = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun Formation(
    profile: Profile,
    me: Profile,
    arrival: () -> Float,
    trail: () -> Float,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    val label = L("Your photo and %s's photo", profile.name)
    // The heart pops in once the pair has arrived (`symbolEffect(.bounce, value: arrived)`).
    val bounce = remember { Animatable(1f) }
    val reduceMotion = LocalReduceMotion.current
    LaunchedEffect(Unit) {
        if (reduceMotion) return@LaunchedEffect
        delay(250)
        bounce.animateTo(1.2f, tween(120, easing = Motion.EaseOut))
        bounce.animateTo(1f, Motion.bouncy())
    }
    Box(modifier.clearAndSetSemantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        // Ghost trails behind the lead card: the drafting motif.
        for (i in 1..3) {
            Box(
                Modifier
                    .graphicsLayer {
                        translationX = (-40f - i * 26f * trail()) * density
                        translationY = -10f * density
                        rotationZ = -6f
                    }
                    .size(170.dp, 230.dp)
                    .background(p.accentOnNight.copy(alpha = 0.22f / i), RoundedCornerShape(DS.Radius.xl)),
            )
        }

        Portrait(me.portrait, rotation = -6f, x = { -260f + (260f - 48f) * arrival() }, y = -10.dp)
        Portrait(profile.portrait, rotation = 7f, x = { 260f - (260f - 56f) * arrival() }, y = 24.dp)

        // Liking stays green whatever the brand accent.
        Box(
            Modifier
                .graphicsLayer {
                    translationX = 4f * density
                    translationY = 128f * density
                    val s = (0.2f + 0.8f * arrival()) * bounce.value
                    scaleX = s
                    scaleY = s
                }
                .size(64.dp)
                .background(p.like, CircleShape)
                .border(4.dp, p.night, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DrafftIcon("heart", size = 34.dp, tint = p.onLike)
        }
    }
}

@Composable
private fun Portrait(name: String, rotation: Float, x: () -> Float, y: Dp) {
    val shape = RoundedCornerShape(DS.Radius.xl)
    Photo(
        name,
        Modifier
            .graphicsLayer {
                translationX = x() * density
                translationY = y.toPx()
                rotationZ = rotation
            }
            .size(170.dp, 230.dp)
            .clip(shape)
            .border(4.dp, DS.palette.night, shape),
        side = 170.dp,
    )
}

/** Swipe it up to dismiss; otherwise it springs back. The offset only goes up. */
@Composable
private fun Modifier.swipeUpToDismiss(onDismiss: () -> Unit): Modifier {
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    return this
        .offset { IntOffset(0, min(0f, drag.value).roundToInt()) }
        .pointerInput(Unit) {
            var total = 0f
            detectVerticalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = {
                    if (total < -30.dp.toPx()) onDismiss() else scope.launch { drag.animateTo(0f, Motion.snappy()) }
                },
                onDragCancel = { scope.launch { drag.animateTo(0f, Motion.snappy()) } },
            ) { change, amount ->
                change.consume()
                total += amount
                scope.launch { drag.snapTo(total) }
            }
        }
}

/** In-app notification when someone likes you back later. */
@Composable
fun MatchBannerView(
    banner: AppModel.MatchBanner,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    val heart = remember { Animatable(1f) }
    val reduceMotion = LocalReduceMotion.current
    LaunchedEffect(banner.id) {
        // The heart bounces twice (`symbolEffect(.bounce, options: .repeat(2))`).
        if (!reduceMotion) {
            repeat(2) {
                heart.animateTo(1.25f, tween(120, easing = Motion.EaseOut))
                heart.animateTo(1f, Motion.bouncy())
            }
        }
    }
    LaunchedEffect(banner.id) {
        delay(5.seconds)
        onDismiss()
    }
    val hint = L("Opens the chat")
    Box(
        modifier
            .swipeUpToDismiss(onDismiss)
            .padding(horizontal = DS.Space.md)
            .pressScale(onOpen, scale = 0.97f, onClickLabel = hint),
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
                Avatar(banner.profile.portrait, size = 48.dp, ring = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(L("%s liked you back", banner.profile.name), style = TextStyles.headline, color = Color.White)
                    Text(L("It's mutual. Tap to say hi."), style = TextStyles.subheadline, color = p.accentOnNight)
                }
                DrafftIcon(
                    "heart",
                    Modifier
                        .graphicsLayer {
                            scaleX = heart.value
                            scaleY = heart.value
                        }
                        .clearAndSetSemantics { },
                    size = 20.dp,
                    tint = p.like,
                )
            }
        }
    }
}

/** Confirmation right after starting a boost: you're at the front of the pack for 30 minutes. */
@Composable
fun BoostBannerView(id: UUID, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val p = DS.palette
    val reduceMotion = LocalReduceMotion.current
    val pulse = remember { Animatable(0f) }
    val bolt = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        if (reduceMotion) return@LaunchedEffect
        launch { pulse.animateTo(1f, tween(700, delayMillis = 150, easing = Motion.EaseOut)) }
        // `symbolEffect(.bounce, value: pulse)`: the bolt bounces as the ring goes out.
        delay(150)
        bolt.animateTo(1.2f, tween(120, easing = Motion.EaseOut))
        bolt.animateTo(1f, Motion.bouncy())
    }
    LaunchedEffect(id) {
        delay(4.seconds)
        onDismiss()
    }
    NightSurface {
        Row(
            modifier
                .swipeUpToDismiss(onDismiss)
                .padding(horizontal = DS.Space.md)
                .fillMaxWidth()
                .bannerSurface()
                .padding(DS.Space.md)
                .padding(end = DS.Space.sm)
                .semantics(mergeDescendants = true) { },
            horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            val s = 1f + 0.6f * pulse.value
                            scaleX = s
                            scaleY = s
                            alpha = 0.8f * (1f - pulse.value)
                        }
                        .border(2.dp, p.accentOnNight, CircleShape),
                )
                Box(
                    Modifier
                        .size(48.dp)
                        .background(p.accentOnNight, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    DrafftIcon(
                        "bolt",
                        Modifier.graphicsLayer {
                            scaleX = bolt.value
                            scaleY = bolt.value
                        },
                        size = 24.dp,
                        tint = p.onAccentOnNight,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(L("You're boosted"), style = TextStyles.headline, color = Color.White)
                Text(L("People nearby see you first for 30 minutes."), style = TextStyles.subheadline, color = p.accentOnNight)
            }
        }
    }
}

/**
 * A swipe, undo, unmatch or boost the server turned down (or that couldn't reach it): the reason, in
 * the person's language. Swipe it up, or it goes by itself.
 */
@Composable
fun NoticeBannerView(notice: AppModel.Notice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val p = DS.palette
    LaunchedEffect(notice.id) {
        delay(4.seconds)
        onDismiss()
    }
    NightSurface {
        Row(
            modifier
                .swipeUpToDismiss(onDismiss)
                .padding(horizontal = DS.Space.md)
                .fillMaxWidth()
                .bannerSurface()
                .padding(DS.Space.md)
                .padding(end = DS.Space.sm)
                // Read out as it arrives (`UIAccessibility.post(notification: .announcement)`).
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(p.accentOnNight, CircleShape)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                DrafftIcon("exclamation-mark", size = 24.dp, tint = p.onAccentOnNight)
            }
            Text(notice.text, Modifier.weight(1f), style = TextStyles.subheadline.semibold, color = Color.White)
        }
    }
}
