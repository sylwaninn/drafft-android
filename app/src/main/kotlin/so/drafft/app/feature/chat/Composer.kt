package so.drafft.app.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import so.drafft.core.data.audio.VoiceRecorder
import so.drafft.core.data.media.PhotoCompressor
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.platform.PermissionPrompter
import so.drafft.core.model.L
import so.drafft.core.model.MessageContent
import so.drafft.core.model.clock
import so.drafft.core.ui.components.glass
import so.drafft.core.ui.components.pressScale
import so.drafft.core.ui.platform.LocalPlatformUi
import so.drafft.core.ui.platform.PickedMedia
import so.drafft.core.ui.platform.PlatformUi
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.monospacedDigits
import so.drafft.core.ui.theme.semibold
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Port of Drafft/Features/Chat/Composer.swift.

/** The message being replied to, shown above the field ("Replying to Sam"). [author] "yourself" for your own. */
data class ComposerReply(val id: String, val author: String, val text: String)

private const val CANCEL_THRESHOLD = -110f
private const val LOCK_THRESHOLD = -90f

/** A press on the mic, kept off state: it's bookkeeping, nothing draws it. */
private class MicPress {
    /** Uptime of the touch-down (ms), null between presses. */
    var start: Long? = null
}

private enum class Trailing { SEND, SEND_VOICE, MIC }

/** Text + attachments + hold-to-record voice. Everything sends optimistically. */
@Composable
fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: (MessageContent) -> Unit,
    reply: ComposerReply? = null,
    onCancelReply: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val recorder = koinInject<VoiceRecorder>()
    val platform = LocalPlatformUi.current
    val scope = rememberCoroutineScope()
    val send by rememberUpdatedState(onSend)
    val focus = remember { FocusRequester() }

    var dragX by remember { mutableFloatStateOf(0f) }
    /** Upward drag while recording (negative), toward the lock. */
    var dragY by remember { mutableFloatStateOf(0f) }
    var locked by remember { mutableStateOf(false) }
    var holdHint by remember { mutableStateOf(false) }
    val press = remember { MicPress() }
    var hintJob by remember { mutableStateOf<Job?>(null) }
    /** Touch-down on the mic, microphone still opening: the recording UI already shows. */
    var starting by remember { mutableStateOf(false) }

    // The recording UI: from the instant the finger lands, not once the microphone is open (the audio
    // focus takes a moment, and waiting for it felt like a required hold).
    val isRecording = recorder.state == VoiceRecorder.State.RECORDING || starting
    val trimmed = text.trim()

    fun flashHint() {
        Haptics.warning()
        holdHint = true
        // A new tap restarts the timer instead of an older one hiding the hint early.
        hintJob?.cancel()
        hintJob = scope.launch {
            delay(1800)
            holdHint = false
        }
    }

    fun finishRecording(cancel: Boolean) {
        val r = recorder.finish(cancel)
        starting = false
        // Once locked, the mic gave way to Send mid-press, so that press never got its end: clear it,
        // or the next hold on the mic would be ignored. A cancel ends the press too (and stops a
        // microphone still opening from recording on its own).
        if (locked || cancel) press.start = null
        locked = false
        dragX = 0f
        dragY = 0f
        if (cancel) {
            Haptics.warning()
        } else if (r != null) {
            send(MessageContent.Voice(url = r.url, duration = r.duration, levels = r.levels))
        }
    }

    // Recording starts on touch-down, as in WhatsApp: the audio focus is taken while the finger is
    // still landing. A tap shorter than 0.1 s is discarded and shows the hint instead.
    suspend fun beginRecording(heldSince: Long) {
        if (press.start != heldSince) { // released (or pressed again) meanwhile
            starting = false
            return
        }
        val ok = recorder.start()
        starting = false
        if (!ok) {
            // No microphone access yet: Android's prompt; the next hold records.
            if (recorder.state == VoiceRecorder.State.DENIED) PermissionPrompter.request(listOf(RECORD_AUDIO))
            return
        }
        // Released or cancelled while the recorder was starting: don't leave it recording on its own.
        if (press.start != heldSince && !locked) {
            recorder.finish(cancel = true)
            return
        }
        Haptics.thump() // the microphone is live
    }

    /** End of a press on the mic, released or cancelled. Runs once (the second call finds no press). */
    fun releaseMic() {
        val start = press.start ?: return
        val held = System.currentTimeMillis() - start
        press.start = null
        if (held < 100) {
            starting = false
            if (recorder.state == VoiceRecorder.State.RECORDING) recorder.finish(cancel = true)
            flashHint()
            return
        }
        // Released before the microphone finished opening: nothing was recorded.
        if (starting && !locked) {
            starting = false
            return
        }
        if ((recorder.state == VoiceRecorder.State.RECORDING || starting) && !locked) finishRecording(cancel = false)
    }

    fun pressMic() {
        if (press.start != null) return
        val start = System.currentTimeMillis()
        press.start = start
        locked = false
        dragX = 0f
        dragY = 0f
        starting = true
        Haptics.tap() // felt on touch-down, before recording starts
        scope.launch { beginRecording(start) }
    }

    fun dragMic(x: Float, y: Float) {
        if (!(recorder.state == VoiceRecorder.State.RECORDING || starting) || locked) return
        // One axis at a time: up toward the lock, or left toward cancel.
        if (-y > abs(x)) {
            dragX = 0f
            val wasBelow = dragY > LOCK_THRESHOLD
            dragY = min(0f, y)
            if (wasBelow && dragY <= LOCK_THRESHOLD) {
                Haptics.thump()
                locked = true
                dragX = 0f
                dragY = 0f
            }
        } else {
            dragY = 0f
            dragX = min(0f, x)
            if (dragX < CANCEL_THRESHOLD) finishRecording(cancel = true)
        }
    }

    val pickMedia = platform.rememberMediaPicker(maxSelection = 5) { items ->
        scope.launch { sendPicked(items, platform) { send(it) } }
    }
    val openCamera = rememberCameraPicker { capture ->
        scope.launch {
            when (capture) {
                is so.drafft.core.ui.platform.CameraCapture.Photo -> send(MessageContent.Photo(asset = null, imageData = capture.jpeg))
                is so.drafft.core.ui.platform.CameraCapture.Video -> sendVideo(capture.path, platform) { send(it) }
            }
        }
    }

    // A reply puts the cursor in the field.
    LaunchedEffect(reply?.id) { if (reply != null) runCatching { focus.requestFocus() } }
    // Done when a chat opens, so a hold on the mic records sooner.
    LaunchedEffect(Unit) { recorder.prewarm() }

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = DS.Space.md)
            .padding(top = DS.Space.xs, bottom = DS.Space.xs),
        verticalArrangement = Arrangement.spacedBy(DS.Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(
            visible = holdHint,
            enter = slideInVertically(Motion.snappy()) { it } + fadeIn(Motion.snappy()),
            exit = slideOutVertically(Motion.snappy()) { it } + fadeOut(Motion.snappy()),
        ) {
            Text(
                L("Hold to record, release to send"),
                Modifier
                    .background(DS.palette.ink, CircleShape)
                    .padding(horizontal = DS.Space.md, vertical = 6.dp),
                style = TextStyles.footnote.semibold,
                color = DS.palette.canvas,
            )
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
            AnimatedVisibility(
                visible = !isRecording,
                enter = scaleIn(Motion.snappy()) + fadeIn(Motion.snappy()),
                exit = scaleOut(Motion.snappy()) + fadeOut(Motion.snappy()),
            ) {
                AttachMenu(onCamera = openCamera, onLibrary = pickMedia)
            }
            // The field stays in the hierarchy while recording (hidden under the bar): removing it
            // would drop its focus, and the keyboard with it.
            Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                Field(text, onTextChange, reply, onCancelReply, isRecording, focus)
                androidx.compose.animation.AnimatedVisibility(
                    visible = isRecording,
                    enter = slideInHorizontally(Motion.snappy()) { it } + fadeIn(Motion.snappy()),
                    exit = slideOutHorizontally(Motion.snappy()) { it } + fadeOut(Motion.snappy()),
                ) {
                    RecordingBar(
                        recorder = recorder,
                        locked = locked,
                        dragX = dragX,
                        onDiscard = { finishRecording(cancel = true) },
                    )
                }
            }
            val trailing = when {
                trimmed.isNotEmpty() && !isRecording -> Trailing.SEND
                isRecording && locked -> Trailing.SEND_VOICE
                else -> Trailing.MIC
            }
            AnimatedContent(
                targetState = trailing,
                transitionSpec = {
                    (scaleIn(Motion.snappy()) + fadeIn(Motion.snappy())).togetherWith(scaleOut(Motion.snappy()) + fadeOut(Motion.snappy()))
                },
                contentAlignment = Alignment.BottomCenter,
                label = "composerTrailing",
            ) { kind ->
                when (kind) {
                    Trailing.SEND -> LimeCircle(L("Send"), pressable = true) {
                        if (trimmed.isEmpty()) return@LimeCircle
                        send(MessageContent.Text(trimmed))
                        onTextChange("")
                    }
                    Trailing.SEND_VOICE -> LimeCircle(L("Send voice message"), pressable = false) { finishRecording(cancel = false) }
                    Trailing.MIC -> MicButton(
                        isRecording = isRecording,
                        locked = locked,
                        dragX = dragX,
                        dragY = dragY,
                        onPress = ::pressMic,
                        onDrag = ::dragMic,
                        onRelease = ::releaseMic,
                        onStartAccessibly = {
                            scope.launch { if (recorder.start()) locked = true }
                        },
                    )
                }
            }
        }
    }
}

private const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"

// MARK: Pieces

/** Photos and videos: take one now with the camera, or pick up to five from the library. */
@Composable
private fun AttachMenu(onCamera: (() -> Unit)?, onLibrary: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = L("Send a photo or video")
    Box {
        Box(
            Modifier
                .size(44.dp)
                .pressScale({ open = true })
                .semantics { contentDescription = label }
                .glass(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DrafftIcon("plus", size = 22.dp, tint = DS.palette.ink)
        }
        ChatMenu(expanded = open, onDismiss = { open = false }) {
            if (onCamera != null) {
                ChatMenuItem(L("Take a photo or video"), "camera") {
                    open = false
                    onCamera()
                }
            }
            ChatMenuItem(L("Choose from library"), "photo.on.rectangle.angled") {
                open = false
                onLibrary()
            }
        }
    }
}

/** A drafft menu (the iPhone's `Menu`): raised surface, rounded, ink items. */
@Composable
internal fun ChatMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(DS.Radius.lg),
        containerColor = DS.palette.sheetRaised,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
    ) { Column { content() } }
}

@Composable
internal fun ChatMenuItem(title: String, symbol: String, destructive: Boolean = false, onClick: () -> Unit) {
    val tint = if (destructive) DS.palette.negative else DS.palette.ink
    DropdownMenuItem(
        text = { Text(title, style = TextStyles.body) },
        onClick = onClick,
        leadingIcon = { DrafftIcon(symbol, size = 20.dp, tint = tint) },
        colors = MenuDefaults.itemColors(textColor = tint, leadingIconColor = tint),
    )
}

/**
 * The message field. When replying, the quote sits inside the same glass shape, above the text, like
 * iMessage: one object, not a banner floating over the composer.
 */
@Composable
private fun Field(
    text: String,
    onTextChange: (String) -> Unit,
    reply: ComposerReply?,
    onCancelReply: () -> Unit,
    isRecording: Boolean,
    focus: FocusRequester,
) {
    val p = DS.palette
    val shape = RoundedCornerShape(22.dp)
    // While recording, text and glass both go. Quick fade, then scale.
    val hidden by animateFloatAsState(if (isRecording) 1f else 0f, tween(120, easing = Motion.EaseOut), label = "fieldHidden")
    val lastReply = remember { arrayOfNulls<ComposerReply>(1) }
    if (reply != null) lastReply[0] = reply
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = 1f - hidden
                val s = 1f - 0.04f * hidden
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(1f, 0.5f)
            }
            .then(if (isRecording) Modifier.semantics { invisibleToUser() } else Modifier)
            .glass(shape)
            // Anywhere on the glass field (reply quote included) puts the cursor in it.
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = !isRecording) {
                runCatching { focus.requestFocus() }
            },
    ) {
        AnimatedVisibility(
            visible = reply != null,
            enter = slideInVertically(Motion.snappy()) { it } + fadeIn(Motion.snappy()),
            exit = slideOutVertically(Motion.snappy()) { it } + fadeOut(Motion.snappy()),
        ) {
            val r = lastReply[0] ?: return@AnimatedVisibility
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(start = DS.Space.md, end = DS.Space.xs, top = DS.Space.sm),
                horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(p.lime, CircleShape))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        if (r.author == "yourself") L("Replying to yourself") else L("Replying to %s", r.author),
                        style = TextStyles.caption.semibold.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        color = p.accentInk,
                    )
                    Text(r.text, style = TextStyles.footnote, color = p.body, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                val cancel = L("Cancel reply")
                // 44 pt to hit, laid out as 32 so the quote doesn't grow.
                Box(
                    Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                            val inset = 6.dp.roundToPx()
                            layout(placeable.width - inset * 2, placeable.height - inset * 2) { placeable.place(-inset, -inset) }
                        }
                        .size(44.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onCancelReply)
                        .semantics { contentDescription = cancel },
                    contentAlignment = Alignment.Center,
                ) {
                    DrafftIcon("xmark.circle.fill", size = 20.dp, tint = p.mute)
                }
            }
        }
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            enabled = true,
            readOnly = isRecording,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp)
                .focusRequester(focus),
            textStyle = TextStyles.body.copy(color = p.ink),
            cursorBrush = SolidColor(p.accentInk),
            // Multi-line: Return adds a line (its key stays "return", not a send arrow).
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            minLines = 1,
            maxLines = 5,
            decorationBox = { inner ->
                Box(
                    Modifier.padding(horizontal = DS.Space.lg, vertical = 11.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) Text(L("Message"), style = TextStyles.body, color = p.mute)
                    inner()
                }
            },
        )
    }
}

/** Send (text, or a locked recording): a lime-tinted glass circle with an up arrow. */
@Composable
private fun LimeCircle(label: String, pressable: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .then(
                if (pressable) {
                    Modifier.pressScale(onClick)
                } else {
                    Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                },
            )
            .semantics { contentDescription = label }
            .glass(CircleShape, tint = DS.palette.lime),
        contentAlignment = Alignment.Center,
    ) {
        DrafftIcon("arrow.up", size = 22.dp, tint = DS.palette.onLime)
    }
}

/**
 * Hold to record. While holding, a glass rail with a lock rises above the mic: drag up and the mic
 * travels into it, the rail shortens and the padlock closes; slide left to cancel. A press the system
 * cancels (a sheet, a call, the app going away) also ends here, never only on release, so the mic
 * can't stay "held" and ignore the next hold.
 */
@Composable
private fun MicButton(
    isRecording: Boolean,
    locked: Boolean,
    dragX: Float,
    dragY: Float,
    onPress: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onRelease: () -> Unit,
    onStartAccessibly: () -> Unit,
) {
    val press by rememberUpdatedState(onPress)
    val drag by rememberUpdatedState(onDrag)
    val release by rememberUpdatedState(onRelease)
    /** 0 at rest, 1 when the mic reaches the lock. */
    val lockProgress = (dragY / LOCK_THRESHOLD).coerceIn(0f, 1f)
    val follow = Motion.springOf<Float>(0.25, 0.8f)
    val y by animateFloatAsState(if (isRecording) max(dragY, LOCK_THRESHOLD) else 0f, follow, label = "micY")
    val x by animateFloatAsState(if (isRecording) max(dragX, CANCEL_THRESHOLD) * 0.25f else 0f, follow, label = "micX")
    val scale by animateFloatAsState(if (isRecording) 1.5f - 0.35f * lockProgress else 1f, Motion.snappy(), label = "micScale")
    val p = DS.palette
    val label = L("Record voice message")
    val hint = L("Hold to record, release to send. Slide left to cancel, up to lock.")
    val start = L("Start recording")

    Box(
        Modifier
            .size(44.dp)
            .semantics {
                contentDescription = "$label. $hint"
                customActions = listOf(CustomAccessibilityAction(start) { onStartAccessibly(); true })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    press()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val t = change.position - down.position
                            drag(t.x / density, t.y / density)
                        }
                    } finally {
                        // Released, or the touch was cancelled: the press ends either way.
                        release()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = isRecording && !locked,
            modifier = Modifier.offset(y = (-104).dp).wrapContentSize(unbounded = true),
            enter = scaleIn(Motion.snappy(), initialScale = 0.6f, transformOrigin = TransformOrigin(0.5f, 1f)) + fadeIn(Motion.snappy()),
            exit = scaleOut(Motion.snappy(), targetScale = 0.6f, transformOrigin = TransformOrigin(0.5f, 1f)) + fadeOut(Motion.snappy()),
        ) {
            LockRail(lockProgress)
        }
        Box(
            Modifier
                .offset { IntOffset((x * density).roundToInt(), (y * density).roundToInt()) }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .size(44.dp)
                .glass(CircleShape, tint = if (isRecording) p.lime else null)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            DrafftIcon("mic.fill", size = 22.dp, tint = if (isRecording) p.onLime else p.ink)
        }
    }
}

/**
 * Glass rail above the mic: open padlock on top, a chevron nudging upward. It shortens as the mic
 * climbs and the padlock closes at the top.
 */
@Composable
private fun LockRail(p: Float) {
    val reduceMotion = LocalReduceMotion.current
    val bounce = if (reduceMotion) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "lockChevron")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = Motion.EaseInOut), RepeatMode.Reverse), label = "bounce").value
    }
    Column(
        Modifier
            .offset(y = (14 * p).dp)
            .width(40.dp)
            .height((84 - 28 * p).dp)
            .glass(CircleShape)
            .padding(vertical = 12.dp)
            .clearAndSetSemantics { },
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Crossfade(targetState = p >= 1f, animationSpec = tween(150), label = "padlock") { closed ->
            DrafftIcon(if (closed) "lock.fill" else "lock.open.fill", size = 18.dp, tint = DS.palette.ink)
        }
        DrafftIcon(
            "chevron.up",
            Modifier
                .graphicsLayer { translationY = -3.dp.toPx() * bounce }
                .alpha(1f - p),
            size = 14.dp,
            tint = DS.palette.ink,
        )
    }
}

@Composable
private fun RecordingBar(
    recorder: VoiceRecorder,
    locked: Boolean,
    dragX: Float,
    onDiscard: () -> Unit,
) {
    val p = DS.palette
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .glass(CircleShape)
            .padding(end = DS.Space.md),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (locked) {
            val discard = L("Discard recording")
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDiscard)
                    .semantics { contentDescription = discard },
                contentAlignment = Alignment.Center,
            ) { DrafftIcon("trash", size = 20.dp, tint = p.negative) }
        } else {
            Box(
                Modifier
                    .padding(start = DS.Space.md)
                    .size(10.dp)
                    .background(p.negative, CircleShape)
                    .clearAndSetSemantics { },
            )
        }
        Text(recorder.duration.clock, style = TextStyles.body.semibold.monospacedDigits, color = p.ink)
        LiveWave(recorder.levels, Modifier.weight(1f).height(28.dp))
        if (!locked) {
            Row(
                Modifier.alpha(1f - min(1f, abs(dragX) / abs(CANCEL_THRESHOLD))),
                horizontalArrangement = Arrangement.spacedBy(DS.Space.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DrafftIcon("chevron.left", size = 15.dp, tint = p.body)
                Text(L("Slide to cancel"), style = TextStyles.footnote.semibold, color = p.body, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** The live waveform while recording: the latest levels, newest on the right, drawn in one canvas. */
@Composable
fun LiveWave(levels: List<Float>, modifier: Modifier = Modifier) {
    val ink = DS.palette.ink
    Canvas(modifier.clearAndSetSemantics { }) {
        val bar = 3.dp.toPx()
        val step = 5.dp.toPx()
        val maxBars = (size.width / step).toInt()
        val recent = levels.takeLast(maxBars)
        var x = size.width - bar
        for (l in recent.asReversed()) {
            val h = max(3.dp.toPx(), size.height * l)
            drawRoundRect(ink, Offset(x, (size.height - h) / 2), Size(bar, h), CornerRadius(bar / 2))
            x -= step
        }
    }
}

// MARK: Actions

private suspend fun sendPicked(items: List<PickedMedia>, platform: PlatformUi, send: (MessageContent) -> Unit) {
    for (item in items) {
        when (item) {
            is PickedMedia.Video -> sendVideo(item.path, platform, send)
            is PickedMedia.Photo -> {
                // At most 2048 px, decoded off the main thread: the thread never holds a 48 MP original.
                val photo = runCatching { PhotoCompressor.prepare(item.data).data }.getOrNull() ?: item.data
                send(MessageContent.Photo(asset = null, imageData = photo))
            }
        }
    }
}

/** A video bubble: its first frame as the poster, and its length. */
private suspend fun sendVideo(path: String, platform: PlatformUi, send: (MessageContent) -> Unit) {
    val info = platform.videoInfo(path)
    send(MessageContent.Video(url = path, thumbnail = info?.thumbnail, duration = info?.duration ?: 0.0))
}
