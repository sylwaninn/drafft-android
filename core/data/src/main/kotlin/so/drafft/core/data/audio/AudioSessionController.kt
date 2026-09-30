package so.drafft.core.data.audio

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Every audio focus call, in order on one serial thread, never on the main thread. Focus is let go once
 * playback or recording ends, so music from another app picks up again. The release waits a moment
 * and is dropped if anything asked for focus after it (the next clip, a recording): requests are
 * counted synchronously by the caller, so a release never lands after the activation that follows.
 * Ports `AudioSessionController` (Drafft/Services/Audio.swift).
 */
class AudioSessionController(private val focus: AudioFocus) {
    private val queue: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "so.drafft.audio-session").apply { isDaemon = true }
    }

    /** Bumped by every activation or release request. */
    private val generation = AtomicInteger()

    /** Another app took focus (a call, a voice note): called on any thread. */
    var onLoss: (() -> Unit)?
        get() = focus.onLoss
        set(value) {
            focus.onLoss = value
        }

    /** Blocks the calling (background) thread until focus is held. Throws when it's refused. */
    fun activate(category: AudioFocus.Category) {
        generation.incrementAndGet()
        try {
            queue.submit { focus.activate(category) }.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** Sets up ahead of time, without taking focus (nothing else's audio is cut). */
    fun prepare(category: AudioFocus.Category) {
        queue.execute { runCatching { focus.prepare(category) } }
    }

    /** Playback or recording ended: focus goes back to other apps, unless something asks for it within the next moment. */
    fun release() {
        val mine = generation.incrementAndGet()
        queue.schedule({
            if (generation.get() == mine) runCatching { focus.deactivate() }
        }, 400, TimeUnit.MILLISECONDS)
    }
}
