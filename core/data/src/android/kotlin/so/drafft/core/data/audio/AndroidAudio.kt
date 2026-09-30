package so.drafft.core.data.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.io.IOException
import kotlin.math.log10
import kotlin.math.max

/** Ports the AVFoundation side of Drafft/Services/Audio.swift: MediaPlayer, MediaRecorder, files. */
class AndroidAudioEngine(context: Context) : AudioEngine {
    private val context = context.applicationContext

    override val cacheDirectory: File get() = context.cacheDir
    override val temporaryDirectory: File get() = context.cacheDir

    override val hasRecordPermission: Boolean
        get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun player(file: File): AudioEngine.Player {
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            player.setDataSource(file.path)
            player.prepare()
        } catch (e: Exception) {
            player.release()
            throw e
        }
        return Player(player)
    }

    override fun recorder(file: File): AudioEngine.Recorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44_100)
            recorder.setAudioChannels(1)
            // Voice only: 48 kb/s mono AAC is clear speech at ~6 KB a second, small enough to preload.
            recorder.setAudioEncodingBitRate(48_000)
            recorder.setOutputFile(file.path)
            recorder.prepare()
            recorder.start()
        } catch (e: Exception) {
            recorder.release()
            throw e
        }
        return Recorder(recorder)
    }

    /**
     * MediaPlayer, used from the main thread once prepared. Its completion callback is posted to the
     * main looper. The speed is applied only while playing: setting `playbackParams` on a paused
     * MediaPlayer starts it.
     */
    private class Player(private val player: MediaPlayer) : AudioEngine.Player {
        private val main = Handler(Looper.getMainLooper())
        private var rate = 1f
        private var released = false

        override var onFinish: (() -> Unit)? = null

        init {
            player.setOnCompletionListener { main.post { onFinish?.invoke() } }
        }

        override val duration: Double
            get() = if (released) 0.0 else max(0, player.duration) / 1000.0

        override var currentTime: Double
            get() = if (released) 0.0 else player.currentPosition / 1000.0
            set(value) {
                if (!released) player.seekTo((value * 1000).toInt())
            }

        override val isPlaying: Boolean get() = !released && player.isPlaying

        override fun play(rate: Float) {
            if (released) return
            this.rate = rate
            player.start()
            applyRate()
        }

        override fun pause() {
            if (!released && player.isPlaying) player.pause()
        }

        override fun setRate(rate: Float) {
            this.rate = rate
            if (!released && player.isPlaying) applyRate()
        }

        private fun applyRate() {
            runCatching { player.playbackParams = player.playbackParams.setSpeed(rate) }
        }

        override fun stop() {
            if (!released) runCatching { player.stop() }
        }

        override fun release() {
            if (released) return
            released = true
            onFinish = null
            player.release()
        }
    }

    private class Recorder(private val recorder: MediaRecorder) : AudioEngine.Recorder {
        private val startedAt = SystemClock.elapsedRealtime()
        private var stopped = false

        override val currentTime: Double
            get() = (SystemClock.elapsedRealtime() - startedAt) / 1000.0

        /** The peak since the last call (`getMaxAmplitude`, 0...32767) in dBFS. */
        override fun averagePower(): Float {
            if (stopped) return -160f
            val amplitude = runCatching { recorder.maxAmplitude }.getOrDefault(0)
            if (amplitude <= 0) return -160f
            return (20 * log10(amplitude / 32767.0)).toFloat()
        }

        override fun stop(): Boolean {
            if (stopped) return false
            stopped = true
            // stop() throws when nothing was encoded yet (a slip too short).
            val ok = try {
                recorder.stop()
                true
            } catch (e: RuntimeException) {
                false
            }
            recorder.release()
            return ok
        }
    }
}

/**
 * Audio focus through AudioManager: playback takes it for a moment (other apps duck or pause), recording
 * exclusively. Giving it back lets music from another app pick up again.
 */
class AndroidAudioFocus(context: Context) : AudioFocus {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null
    private val requests = mutableMapOf<AudioFocus.Category, AudioFocusRequest>()

    override var onLoss: (() -> Unit)? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            onLoss?.invoke()
        }
    }

    private fun request(category: AudioFocus.Category): AudioFocusRequest = requests.getOrPut(category) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val gain = when (category) {
            AudioFocus.Category.PLAYBACK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioFocus.Category.PLAY_AND_RECORD -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        }
        AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
            .build()
    }

    override fun activate(category: AudioFocus.Category) {
        val next = request(category)
        request?.takeIf { it !== next }?.let { audio.abandonAudioFocusRequest(it) }
        request = next
        if (audio.requestAudioFocus(next) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
            request = null
            throw IOException("audio focus refused")
        }
    }

    override fun prepare(category: AudioFocus.Category) {
        request(category)
    }

    override fun deactivate() {
        request?.let { audio.abandonAudioFocusRequest(it) }
        request = null
    }
}
