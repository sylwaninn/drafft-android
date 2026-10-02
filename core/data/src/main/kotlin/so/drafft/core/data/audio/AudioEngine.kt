package so.drafft.core.data.audio

import java.io.File

/**
 * The platform's audio: players, recorders, audio focus and where files go. Implemented in src/android
 * (`AndroidAudioEngine` with MediaPlayer and MediaRecorder, `AndroidAudioFocus` with AudioManager);
 * [AudioPlayback], [VoiceRecorder] and [AudioSessionController] hold the behaviour.
 */
interface AudioEngine {
    /** Where clips from the server are kept. */
    val cacheDirectory: File

    /** Where recordings are written before they're sent. */
    val temporaryDirectory: File

    /** RECORD_AUDIO granted (the screens ask for it; the recorder only reports `denied`). */
    val hasRecordPermission: Boolean

    /** A player for a local file, prepared. Blocking: called off the main thread. Throws when it can't play. */
    fun player(file: File): Player

    /** A recorder already recording into [file] (AAC in MPEG-4, mono). Blocking: off the main thread. Throws. */
    fun recorder(file: File): Recorder

    interface Player {
        /** Seconds. */
        val duration: Double

        /** Seconds. */
        var currentTime: Double
        val isPlaying: Boolean

        /** Called once, on the main thread, when the clip plays to its end. */
        var onFinish: (() -> Unit)?

        fun play(rate: Float)
        fun pause()

        /** Applied now while playing, or on the next [play]. */
        fun setRate(rate: Float)
        fun stop()

        /** Frees the player (always called, after [stop] or when it's dropped). */
        fun release()
    }

    interface Recorder {
        /** Seconds since recording started. */
        val currentTime: Double

        /** The level since the last call, in dBFS (-160 silence ... 0 full scale), like `averagePower`. */
        fun averagePower(): Float

        /** Stops and closes the file. False when nothing usable was written (a slip too short to encode). */
        fun stop(): Boolean
    }
}

/**
 * The platform's audio focus, the Android side of AVAudioSession: [activate] asks for focus (other apps'
 * audio pauses or ducks), [deactivate] gives it back so they pick up again. Called on
 * [AudioSessionController]'s own thread, never the main one.
 */
interface AudioFocus {
    enum class Category { PLAYBACK, PLAY_AND_RECORD }

    /** Throws when focus is refused (a call in progress). */
    fun activate(category: AudioFocus.Category)

    /** Sets up what [activate] needs ahead of time, without taking focus (nothing else's audio is cut). */
    fun prepare(category: AudioFocus.Category)
    fun deactivate()

    /** Another app took focus for good or for a moment (a call, a voice note): playback pauses. */
    var onLoss: (() -> Unit)?
}
