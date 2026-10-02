package so.drafft.app.feature.discover

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.telemetry.Screen
import so.drafft.core.model.L
import so.drafft.core.model.Profile
import so.drafft.core.ui.TrackScreen
import so.drafft.core.ui.components.Photo
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.SuperLikeMark
import so.drafft.core.ui.components.VerticalEdge
import so.drafft.core.ui.components.draftTrail
import so.drafft.core.ui.components.pressScale
import so.drafft.core.ui.components.progressiveBlur
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.NightSurface
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.displayBold
import so.drafft.core.ui.theme.semibold
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Super like confirmation, shown in place over the deck like the profile's like composer: their
 * photo lifts forward on a dimmed background with a reminder of what a super like does, an
 * optional note, and the send button. Tap outside to cancel.
 *
 * It paints no page of its own: present it in a window over everything without any transition
 * (`PlatformUi.FullScreenWindow`) or as an overlay; it fades itself in and out, and stops taking
 * touches the moment it starts closing. The presenter removes it without a second fade.
 */
@Composable
fun SuperLikeComposer(
    profile: Profile,
    left: Int,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TrackScreen(Screen.SUPER_LIKE_COMPOSER)
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    var message by rememberSaveable { mutableStateOf("") }
    val appeared = remember { Animatable(0f) }
    /** Set on the way out: nothing here takes touches while it fades. */
    var closing by remember { mutableStateOf(false) }
    val hasMessage = message.isNotBlank()
    // Tracked by hand: the content anchors to the keyboard and rises with it.
    val ime = WindowInsets.ime
    val keyboardUp by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    val headerBlur by animateFloatAsState(if (keyboardUp) 1f else 0f, Motion.gentle(), label = "composerHeader")

    LaunchedEffect(Unit) { appeared.animateTo(1f, Motion.springOf(0.28, 1f)) }

    /** Fades out, then hands back (the presenter removes it without its own animation). */
    fun close(then: () -> Unit) {
        if (closing) return
        closing = true
        focus.clearFocus()
        scope.launch {
            launch { appeared.animateTo(0f, tween(160, easing = Motion.EaseOut)) }
            delay(if (reduceMotion) 0 else 160)
            then()
        }
    }

    fun send() {
        Haptics.success()
        val note = message.trim()
        close { onSend(note) }
    }

    fun cancel() {
        Haptics.tap()
        close(onCancel)
    }

    // System back is Cancel (over a profile sheet, it would otherwise close the whole sheet).
    BackHandler { cancel() }

    val statusTop = WindowInsets.statusBars.getTop(density)
    Box(modifier.fillMaxSize()) {
        // The dimmed page. A window here can't blur what's behind it, so two tints dim it instead: 18 % black,
        // then the night colour at 50 %.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (reduceMotion) 1f else appeared.value.coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = 0.18f))
                .background(DS.palette.night.copy(alpha = 0.5f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = null) { cancel() }
                .semantics {
                    contentDescription = L("Cancel super like")
                    role = Role.Button
                },
        )

        // Content keeps its full height. With the keyboard up it anchors to the keyboard and
        // rises, sliding under the blurred header when there isn't room.
        val bandPx = with(density) { 96.dp.toPx() } + statusTop
        KeyboardAnchored(
            Modifier
                .fillMaxSize()
                .progressiveBlur(VerticalEdge.TOP, band = { bandPx }, alpha = { headerBlur }),
        ) {
            Column(
                Modifier.padding(horizontal = DS.Space.xl),
                verticalArrangement = Arrangement.spacedBy(DS.Space.md),
            ) {
                Card(
                    profile, left,
                    Modifier.graphicsLayer {
                        val v = appeared.value
                        val s = if (reduceMotion) 1f else 0.96f + 0.04f * v
                        scaleX = s
                        scaleY = s
                        alpha = v.coerceIn(0f, 1f)
                    },
                )
                Column(
                    Modifier.graphicsLayer {
                        val v = appeared.value
                        alpha = v.coerceIn(0f, 1f)
                        translationY = if (reduceMotion) 0f else (12f * (1 - v)).dp.toPx()
                    },
                    verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
                ) {
                    NoteField(message, { message = it }, onSubmit = ::send)
                    SendButton(hasMessage, onClick = ::send)
                }
            }
        }

        // Header: close always on top.
        Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(DS.Space.lg)) {
            PressScaleButton(
                onClick = ::cancel,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(44.dp)
                    .background(Color.White.copy(alpha = 0.18f), CircleShape),
                contentDescription = L("Cancel"),
            ) {
                DrafftIcon("close", size = (17f * 1.2f).dp, tint = Color.White)
            }
        }

        // Stops every touch the moment it starts closing.
        if (closing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    },
            )
        }
    }
}

/**
 * Lays its content out against the full screen: centred, or with the keyboard up, just above it
 * (and never lower than centred, so it rises with the keyboard instead of jumping). Read in the
 * layout pass: the keyboard's slide re-places it without recomposing anything.
 */
@Composable
private fun KeyboardAnchored(modifier: Modifier, content: @Composable () -> Unit) {
    val ime = WindowInsets.ime
    val margin = with(LocalDensity.current) { DS.Space.md.roundToPx() }
    Layout(content, modifier) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity))
        val h = constraints.maxHeight
        layout(constraints.maxWidth, h) {
            val keyboard = ime.getBottom(this@Layout)
            val centered = (h - placeable.height) / 2
            val y = if (keyboard > 0) min(centered, h - keyboard - margin - placeable.height) else centered
            placeable.place((constraints.maxWidth - placeable.width) / 2, y)
        }
    }
}

/** One line only, so the send button never moves out of view. */
@Composable
private fun NoteField(message: String, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    val p = DS.palette
    BasicTextField(
        value = message,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .background(p.canvas, RoundedCornerShape(22.dp))
            .padding(horizontal = DS.Space.lg, vertical = 13.dp),
        textStyle = TextStyles.body.copy(color = p.ink),
        singleLine = true,
        cursorBrush = SolidColor(p.accentInk),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSubmit() }),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (message.isEmpty()) Text(L("Add a note (optional)"), style = TextStyles.body, color = p.mute, maxLines = 1)
                inner()
            }
        },
    )
}

@Composable
private fun SendButton(hasMessage: Boolean, onClick: () -> Unit) {
    val negative = DS.palette.negative
    val shape = RoundedCornerShape(DS.Radius.xl)
    Box(
        Modifier
            .padding(start = 12.dp)
            .draftTrail(shape, color = negative, step = DpOffset((-6).dp, 0.dp))
            .fillMaxWidth()
            .pressScale(onClick, scale = 0.97f)
            .defaultMinSize(minHeight = 52.dp)
            .background(negative, shape),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(horizontal = DS.Space.lg, vertical = DS.Space.xs),
            horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SuperLikeMark(size = 15.dp, color = Color.White)
            AnimatedContent(
                targetState = hasMessage,
                transitionSpec = { fadeIn(Motion.gentle()) togetherWith fadeOut(Motion.gentle()) },
                label = "sendTitle",
            ) { with ->
                Text(
                    if (with) L("Send super like with note") else L("Send super like"),
                    style = TextStyles.body.semibold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

/**
 * Their photo fills the whole profile block; toward the bottom it turns into a blur tinted night,
 * and the reminder sits on that frosted part. No hard band anywhere.
 */
@Composable
private fun Card(profile: Profile, left: Int, modifier: Modifier) {
    val shape = RoundedCornerShape(DS.Radius.xl)
    Box(
        modifier
            .fillMaxWidth()
            .shadow(24.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
            .clip(shape),
    ) {
        Backdrop(profile, Modifier.matchParentSize())
        NightSurface {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = DS.Space.xl, end = DS.Space.xl, top = DS.Space.lg, bottom = DS.Space.xl)
                    .semantics(mergeDescendants = true) { },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
            ) {
                ProfileIdentity(profile = profile, nameSize = 30f, showsLocation = false, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(240.dp))
                Remaining(left)
                Text(
                    L("%s sees you first.", profile.name),
                    style = displayBold(22f),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun Backdrop(profile: Profile, modifier: Modifier) {
    val night = DS.palette.night
    BoxWithConstraints(modifier) {
        Photo(profile.portrait, Modifier.fillMaxSize())
        // Same photo, heavily blurred, revealed progressively toward the bottom. The blur is baked
        // into a small copy (never a live blur).
        // design-lint: allow gradient - blur mask over the photo
        Photo(
            profile.portrait,
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        // design-lint: allow gradient - blur mask over the photo
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.66f to Color.Transparent,
                            0.76f to Color.Black.copy(alpha = 0.6f),
                            0.84f to Color.Black,
                            1f to Color.Black,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
            side = maxWidth,
            blur = 28.dp,
        )
        // Soft tint for legibility: light at the top for the name, deeper under the text.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    // design-lint: allow gradient - photo scrim for legibility
                    Brush.verticalGradient(
                        0f to night.copy(alpha = 0.45f),
                        0.28f to night.copy(alpha = 0f),
                        0.64f to night.copy(alpha = 0f),
                        0.8f to night.copy(alpha = 0.35f),
                        1f to night.copy(alpha = 0.62f),
                    ),
                ),
        )
    }
}

/** How many super likes are left after this one. */
@Composable
private fun Remaining(left: Int) {
    val after = left - 1
    val long = when (after) {
        0 -> L("Your last super like")
        1 -> L("1 super like left after this")
        else -> L("%d super likes left after this", after)
    }
    val short = when (after) {
        0 -> L("Your last super like")
        1 -> L("1 super like left")
        else -> L("%d super likes left", after)
    }
    val ink = Color.White.copy(alpha = 0.85f)
    Row(
        Modifier
            .defaultMinSize(minHeight = 30.dp)
            .background(Color.White.copy(alpha = 0.1f), CircleShape)
            .padding(start = DS.Space.sm, end = DS.Space.md),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SuperLikeMark(size = 11.dp, color = Color.White)
        // A pill stays on one line: the short wording takes over when the long one won't fit.
        FirstThatFits {
            Text(long, style = TextStyles.footnote.semibold, color = ink, maxLines = 1, softWrap = false)
            Text(short, style = TextStyles.footnote.semibold, color = ink, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * `ViewThatFits(in: .horizontal)`: shows the first child whose natural width fits, else the last.
 * Only the chosen one is placed.
 */
@Composable
internal fun FirstThatFits(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val max = constraints.maxWidth
        val pick = measurables.indexOfFirst { it.maxIntrinsicWidth(Constraints.Infinity) <= max }
            .takeIf { it >= 0 } ?: measurables.lastIndex
        val placeable = measurables[pick].measure(constraints.copy(minWidth = 0))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}
