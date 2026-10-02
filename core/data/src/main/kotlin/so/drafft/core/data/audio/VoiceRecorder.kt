package so.drafft.core.data.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Microphone recorder that samples levels for a live waveform. One per screen that records (a Koin
 * factory: `remember { get<VoiceRecorder>() }`). Call it from the main thread.
 */
class VoiceRecorder(
    private val engine: AudioEngine,
    private val session: AudioSessionController,
    private val playback: AudioPlayback,
) {
    enum class State { IDLE, RECORDING, DENIED }

    /** A finished recording: its file (a local path), seconds, and 40 bars of waveform. */
    data class Recording(val url: String, val duration: Double, val levels: List<Float>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var state by mutableStateOf(State.IDLE)
        private set
    var levels by mutableStateOf<List<Float>>(emptyList())
        private set

    /** Seconds. */
    var duration by mutableDoubleStateOf(0.0)
        private set

    private var recorder: AudioEngine.Recorder? = null
    private var timer: Job? = null
    private var file: File? = null

    /**
     * Done when a chat opens, so a hold on the mic records sooner: what recording needs is set up in
     * advance (focus itself is only taken on the hold, so music playing isn't cut).
     */
    fun prewarm() {
        if (playback.isPlaying) return
        session.prepare(AudioFocus.Category.PLAY_AND_RECORD)
    }

    /**
     * Starts recording; false when the microphone isn't allowed ([state] becomes `DENIED`: the screen
     * asks for RECORD_AUDIO and explains) or the recorder couldn't start.
     */
    suspend fun start(): Boolean {
        if (!engine.hasRecordPermission) {
            state = State.DENIED
            return false
        }
        playback.stop()
        val file = File(engine.temporaryDirectory, "voice-${UUID.randomUUID()}.m4a")
        // Focus and recorder are set up off the main thread: preparing the recorder can block for a
        // noticeable moment, and the hold on the mic must keep tracking the finger.
        val making = scope.async(Dispatchers.IO) {
            try {
                session.activate(AudioFocus.Category.PLAY_AND_RECORD)
                engine.recorder(file)
            } catch (e: Exception) {
                null
            }
        }
        val made = try {
            making.await()
        } catch (e: CancellationException) {
            // The screen went away while it started: the recording it was making is thrown away.
            scope.launch { making.await()?.let { discard(it, file) } }
            throw e
        }
        if (made == null) {
            state = State.IDLE
            session.release()
            file.delete()
            return false
        }
        recorder = made
        this.file = file
        levels = emptyList()
        duration = 0.0
        state = State.RECORDING
        timer?.cancel()
        timer = scope.launch {
            while (isActive) {
                delay(80)
                sample()
            }
        }
        return true
    }

    private fun discard(recorder: AudioEngine.Recorder, file: File) {
        recorder.stop()
        session.release()
        file.delete()
    }

    private fun sample() {
        val recorder = recorder ?: return
        levels = levels + normalized(recorder.averagePower())
        duration = recorder.currentTime
    }

    /** Stops and returns the recording, or null when cancelled or too short. */
    fun finish(cancel: Boolean = false): Recording? {
        timer?.cancel()
        timer = null
        val recorder = recorder
        val d = recorder?.currentTime ?: 0.0
        val written = recorder?.stop() ?: false
        if (recorder != null) session.release()
        this.recorder = null
        state = State.IDLE
        val file = file
        this.file = null
        val sampled = levels
        levels = emptyList()
        duration = 0.0
        if (cancel || file == null || !written || d < minimumDuration) {
            file?.delete()
            return null
        }
        return Recording(url = file.path, duration = d, levels = downsample(sampled, 40))
    }

    companion object {
        /** Shortest voice message kept: anything held less is a slip, discarded. */
        const val minimumDuration: Double = 0.3

        /** A level in dBFS as a bar height, 0.08...1. */
        fun normalized(db: Float): Float = max(0.08f, min(1f, 10f.pow(db / 40f)))

        fun downsample(values: List<Float>, count: Int): List<Float> {
            if (values.size <= count) return values + List(max(0, count - values.size)) { 0.12f }
            val step = values.size.toFloat() / count
            return List(count) { i ->
                val a = (i * step).toInt()
                val b = min(values.size, ((i + 1) * step).toInt())
                values.subList(a, max(a + 1, b)).maxOrNull() ?: 0.12f
            }
        }
    }
}
