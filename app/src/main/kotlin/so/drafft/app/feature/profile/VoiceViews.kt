package so.drafft.app.feature.profile

// Ports Drafft/Features/Profile/VoiceViews.swift.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import so.drafft.core.data.audio.AudioPlayback
import so.drafft.core.data.audio.VoiceRecorder
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.platform.PermissionPrompter
import so.drafft.core.model.L
import so.drafft.core.model.clock
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.DrafftButtonKind
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.RollingText
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.branded
import so.drafft.core.ui.theme.monospacedDigits
import so.drafft.core.ui.theme.semibold

/**
 * Waveform bars with a played portion; tap or drag to seek when it's the active clip.
 * [progress] is read at draw time only: a clip playing redraws one layer per frame, nothing recomposes.
 */
@Composable
fun WaveformBars(
    levels: List<Float>,
    progress: () -> Double,
    played: Color,
    unplayed: Color,
    barWidth: Dp = 3.dp,
    /** Drag to scrub. Off in chat bubbles, where a sideways drag means "reply". */
    scrubs: Boolean = true,
    onSeek: ((Double) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val seek by rememberUpdatedState(onSeek)
    val hasSeek = onSeek != null
    Canvas(
        modifier
            .clearAndSetSemantics { }
            // Tap to seek, or drag sideways. Horizontal-only, so a vertical swipe that starts on the
            // waveform still scrolls the page.
            .then(
                if (hasSeek) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { seek?.invoke((it.x / size.width).toDouble().coerceIn(0.0, 1.0)) }
                    }
                } else Modifier,
            )
            .then(
                if (hasSeek && scrubs) {
                    Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures { change, _ ->
                            seek?.invoke((change.position.x / size.width).toDouble().coerceIn(0.0, 1.0))
                        }
                    }
                } else Modifier,
            )
            // One layer instead of two stacks of bars and a mask: a chat with several voice messages
            // lays out fast, and playback redraws one layer per frame.
            .drawWithCache {
                // Always span the full width: bars keep their width (shrinking if needed), and the
                // gaps stretch so the last bar lands on the right edge. When space is tight, show
                // fewer bars (downsampled) rather than overflowing the frame.
                val minGap = 1.5.dp.toPx()
                val minBar = 2.dp.toPx()
                val w = size.width
                val h = size.height
                val fit = max(1, ((w + minGap) / (minBar + minGap)).toInt())
                val shown = if (levels.size > fit) VoiceRecorder.downsample(levels, fit) else levels
                val count = max(shown.size, 1)
                val bar = max(minBar, min(barWidth.toPx(), (w - minGap * (count - 1)) / count))
                val spacing = if (count > 1) max(minGap, (w - bar * count) / (count - 1)) else 0f
                val bars = Path()
                shown.forEachIndexed { i, level ->
                    val bh = max(bar, h * level)
                    bars.addRoundRect(
                        RoundRect(i * (bar + spacing), (h - bh) / 2, i * (bar + spacing) + bar, (h + bh) / 2, CornerRadius(bar / 2)),
                    )
                }
                onDrawBehind {
                    drawPath(bars, unplayed)
                    // Played part: the same bars in the played colour, clipped to the progress, so it
                    // glides instead of jumping bar by bar.
                    val p = progress().coerceIn(0.0, 1.0).toFloat()
                    if (p > 0f) clipRect(right = w * p) { drawPath(bars, played) }
                }
            },
    ) {}
}

/** [WaveformBars] with a fixed progress. */
@Composable
fun WaveformBars(
    levels: List<Float>,
    progress: Double,
    played: Color,
    unplayed: Color,
    barWidth: Dp = 3.dp,
    scrubs: Boolean = true,
    onSeek: ((Double) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(progress)
    WaveformBars(levels, { current }, played, unplayed, barWidth, scrubs, onSeek, modifier)
}

/**
 * A frame counter that ticks while [running] (the iPhone's `TimelineView(.animation(paused:))`).
 * Read it inside a draw lambda: each frame redraws, nothing recomposes.
 */
@Composable
internal fun rememberFrameTick(running: Boolean): MutableLongState {
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) {
        if (running) while (true) withFrameNanos { tick.longValue = it }
    }
    return tick
}

/** 1×, 1.5× or 2×, as the speed button reads. */
internal fun rateLabel(rate: Float): String = when (rate) {
    1f -> "1×"
    1.5f -> "1.5×"
    else -> "2×"
}

internal fun rateValue(rate: Float): String = when (rate) {
    1f -> L("Normal")
    1.5f -> L("1.5 times")
    else -> L("2 times")
}

internal fun nextRate(rate: Float): Float = if (rate == 1f) 1.5f else if (rate == 1.5f) 2f else 1f

/** Voice clip player used on profiles and in chat. */
@Composable
fun VoicePlayer(
    url: String?,
    duration: Double,
    levels: List<Float>,
    tint: Color = DS.palette.ink,
    track: Color = DS.palette.ink.copy(alpha = 0.22f),
    buttonFill: Color = DS.palette.lime,
    buttonGlyph: Color = DS.palette.onLime,
    showsSpeed: Boolean = false,
    /** Drag on the waveform to scrub (tap to seek always works). */
    scrubbable: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val audio = koinInject<AudioPlayback>()
    val isCurrent = audio.isCurrent(url)
    val playing = isCurrent && audio.isPlaying
    val tick = rememberFrameTick(playing)

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(DS.Space.md), verticalAlignment = Alignment.CenterVertically) {
        PressScaleButton(
            onClick = {
                if (url != null) {
                    Haptics.tap()
                    audio.toggle(url)
                }
            },
            modifier = Modifier.size(44.dp).background(buttonFill, CircleShape),
            contentDescription = if (playing) L("Pause voice message") else L("Play voice message, %d seconds", duration.toInt()),
        ) {
            Crossfade(playing, label = "playPause") { on ->
                DrafftIcon(if (on) "pause" else "play", size = symbol(16f), tint = buttonGlyph)
            }
        }

        WaveformBars(
            levels = levels,
            progress = {
                tick.longValue
                if (audio.isCurrent(url)) audio.liveProgress else 0.0
            },
            played = tint,
            unplayed = track,
            scrubs = scrubbable,
            onSeek = { f -> if (audio.isCurrent(url)) audio.seek(f) },
            modifier = Modifier.weight(1f).height(32.dp),
        )

        RollingText(
            (if (isCurrent && audio.elapsed > 0) audio.elapsed else duration).clock,
            modifier = Modifier.widthIn(min = 34.dp),
            style = TextStyles.footnote.semibold.monospacedDigits.copy(textAlign = TextAlign.End),
            color = tint,
            maxLines = 1,
        )

        AnimatedVisibility(
            showsSpeed && isCurrent,
            enter = scaleIn(Motion.snappy()) + fadeIn(Motion.snappy()),
            exit = scaleOut(Motion.snappy()) + fadeOut(Motion.snappy()),
        ) {
            PressScaleButton(
                onClick = {
                    audio.rate = nextRate(audio.rate)
                    Haptics.select()
                },
                modifier = Modifier
                    .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                    .semantics { stateDescription = rateValue(audio.rate) },
                contentDescription = L("Playback speed"),
            ) {
                Text(
                    rateLabel(audio.rate),
                    Modifier
                        .background(tint.copy(alpha = 0.12f), CircleShape)
                        .defaultMinSize(minHeight = 28.dp)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    style = TextStyles.caption.bold.monospacedDigits,
                    color = tint,
                )
            }
        }
    }
}

/**
 * Voice intro recorder (sign-up and Edit profile). Idle and recording share one layout: a
 * 15-second track that fills with the live waveform, the time, and one round button. Once
 * recorded: listen, record again (starts a new take right away) or delete.
 */
@Composable
fun VoiceIntroRecorder(
    result: VoiceRecorder.Recording?,
    onResultChange: (VoiceRecorder.Recording?) -> Unit,
    /** False when placed inside an existing block, so it doesn't draw a card within a card. */
    framed: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val recorder = koinInject<VoiceRecorder>()
    val audio = koinInject<AudioPlayback>()
    val scope = rememberCoroutineScope()
    val limit = 15.0
    val recording = recorder.state == VoiceRecorder.State.RECORDING
    val currentResult by rememberUpdatedState(result)
    val setResult by rememberUpdatedState(onResultChange)

    fun stop() {
        Haptics.success()
        recorder.finish()?.let { setResult(it) }
    }

    /** Starts a take; the first time, Android's microphone prompt comes first. */
    suspend fun start(): Boolean {
        if (recorder.start()) return true
        if (recorder.state != VoiceRecorder.State.DENIED) return false
        val answer = PermissionPrompter.request(listOf("android.permission.RECORD_AUDIO"))
        return answer == PermissionPrompter.Result.GRANTED && recorder.start()
    }

    suspend fun toggle() {
        if (recording) {
            stop()
        } else {
            Haptics.thump()
            start()
        }
    }

    /** A new take starts straight away (otherwise it would do the same as delete). */
    suspend fun recordAgain() {
        Haptics.thump()
        audio.stop()
        val previous = currentResult
        if (start()) {
            previous?.url?.let { File(it).delete() }
            setResult(null)
        }
    }

    fun discard() {
        audio.stop()
        currentResult?.url?.let { File(it).delete() }
        setResult(null)
    }

    LaunchedEffect(recorder) {
        snapshotFlow { recorder.duration }.collect { if (it >= limit) stop() }
    }
    // Leaving mid-take throws it away, like the iPhone's recorder going with its view.
    DisposableEffect(recorder) {
        onDispose { if (recorder.state == VoiceRecorder.State.RECORDING) recorder.finish(cancel = true) }
    }

    Box(
        modifier
            .fillMaxWidth()
            .then(if (framed) Modifier.background(DS.palette.canvas, RoundedCornerShape(DS.Radius.xl)) else Modifier)
            .padding(if (framed) DS.Space.xl else 0.dp),
    ) {
        val shown = if (!recording) result else null
        Crossfade(shown, animationSpec = Motion.snappy(), label = "voiceIntro") { r ->
            if (r != null) {
                Recorded(r, onRecordAgain = { scope.launch { recordAgain() } }, onDiscard = {
                    Haptics.tap()
                    discard()
                })
            } else {
                Capture(recorder, recording, limit, framed) { scope.launch { toggle() } }
            }
        }
    }
}

@Composable
private fun Capture(recorder: VoiceRecorder, recording: Boolean, limit: Double, framed: Boolean, onToggle: () -> Unit) {
    val p = DS.palette
    Column(
        Modifier.fillMaxWidth().padding(vertical = if (framed) 0.dp else DS.Space.sm),
        verticalArrangement = Arrangement.spacedBy(DS.Space.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
            RecordingTrack(recorder.levels, recorder.duration, limit, active = recording, modifier = Modifier.fillMaxWidth().height(56.dp))
            val style = TextStyles.footnote.semibold.monospacedDigits
            Row(
                Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics {
                        contentDescription = if (recording) {
                            L("Recording, %d of 15 seconds", recorder.duration.toInt())
                        } else {
                            L("Up to 15 seconds")
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedVisibility(recording, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                        Box(Modifier.size(8.dp).background(p.negative, CircleShape))
                    }
                    RollingText(recorder.duration.clock, style = style, color = if (recording) p.ink else p.body)
                }
                Spacer(Modifier.weight(1f))
                Text(limit.clock, style = style, color = p.body)
            }
        }

        val fill by animateColorAsState(if (recording) p.night else p.lime, Motion.snappy(), label = "micFill")
        PressScaleButton(
            onClick = onToggle,
            modifier = Modifier.size(76.dp).background(fill, CircleShape),
            contentDescription = if (recording) L("Stop recording") else L("Record voice intro"),
        ) {
            Crossfade(recording, label = "mic") { on ->
                DrafftIcon(if (on) "stop" else "microphone", size = symbol(26f), tint = if (on) p.accentOnNight else p.onLime)
            }
        }

        if (recorder.state == VoiceRecorder.State.DENIED) {
            Text(
                branded(L("Microphone access is off. Turn it on in Settings › drafft to record."), brandWeight = FontWeight.ExtraBold),
                style = TextStyles.footnote.semibold,
                color = p.negative,
                textAlign = TextAlign.Center,
            )
        } else {
            Crossfade(recording, label = "micHint") { on ->
                Text(
                    if (on) L("Tap to stop") else L("Tap to record"),
                    style = TextStyles.subheadline.semibold,
                    color = p.body,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun Recorded(result: VoiceRecorder.Recording, onRecordAgain: () -> Unit, onDiscard: () -> Unit) {
    val p = DS.palette
    // Quiet recordings produce flat bars; stretch them so the waveform always reads.
    val levels = remember(result.levels) {
        val peak = result.levels.maxOrNull() ?: 1f
        if (peak <= 0.01f) result.levels else result.levels.map { max(0.12f, min(1f, it / peak)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.lg)) {
        VoicePlayer(
            url = result.url,
            duration = result.duration,
            levels = levels,
            modifier = Modifier
                .fillMaxWidth()
                .background(p.canvasSoft, RoundedCornerShape(DS.Radius.lg))
                .padding(DS.Space.md),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.CenterVertically) {
            DrafftButton(onClick = onRecordAgain, modifier = Modifier.weight(1f), kind = DrafftButtonKind.SECONDARY) {
                DrafftIcon("restart", size = symbol(17f), tint = p.ink)
                Text(L("Record again"), maxLines = 2)
            }
            PressScaleButton(
                onClick = onDiscard,
                modifier = Modifier.size(52.dp).background(p.canvasSoft, CircleShape),
                contentDescription = L("Delete recording"),
            ) {
                DrafftIcon("trash-bin-minimalistic", size = symbol(17f), tint = p.negative)
            }
        }
    }
}

/**
 * The 15-second track: one slot per bar across the width. Recorded slots show the live level
 * in ink; the rest are small dots showing the time left.
 */
@Composable
fun RecordingTrack(
    levels: List<Float>,
    elapsed: Double,
    limit: Double,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val ink = DS.palette.ink
    // Seconds between two level samples (VoiceRecorder samples every 0.08 s).
    val sampleEvery = 0.08
    val dotAlpha by animateFloatAsState(if (active) 0.18f else 0.14f, Motion.snappy(), label = "trackDots")
    Canvas(modifier.clearAndSetSemantics { }) {
        val bar = 3.dp.toPx()
        val gap = 3.dp.toPx()
        val slots = max(1, ((size.width + gap) / (bar + gap)).toInt())
        val perSlot = limit / sampleEvery / slots
        val filled = min(slots, ((elapsed / limit) * slots).toInt())
        // Centred like an HStack of fixed-width capsules.
        val used = slots * bar + (slots - 1) * gap
        val x0 = (size.width - used) / 2
        for (i in 0 until slots) {
            val on = i < filled
            val level = if (on) trackLevel(levels, (i * perSlot).toInt(), ((i + 1) * perSlot).toInt()) else 0f
            val h = if (on) max(bar * 2, size.height * level) else bar
            drawRoundRect(
                if (on) ink else ink.copy(alpha = dotAlpha),
                topLeft = Offset(x0 + i * (bar + gap), (size.height - h) / 2),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

private fun trackLevel(values: List<Float>, a: Int, b: Int): Float {
    if (a >= values.size) return 0.12f
    val slice = values.subList(a, min(values.size, max(a + 1, b)))
    // Quiet voices still read.
    return max(0.12f, min(1f, (slice.maxOrNull() ?: 0f) * 1.6f))
}

/** The box an SF Symbol set at [points] takes (Material glyphs carry a small margin). */
internal fun symbol(points: Float): Dp = (points * 1.2f).dp
