package so.drafft.core.data.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import so.drafft.core.data.media.MediaURL

/**
 * One shared player: starting a clip stops whatever else was playing. A Koin singleton (the iPhone's
 * `AudioPlayback.shared`); screens read its state directly, like SwiftUI reads the `@Observable`.
 * Ports `AudioPlayback` (Drafft/Services/Audio.swift). Call it from the main thread.
 */
class AudioPlayback(
    private val engine: AudioEngine,
    private val session: AudioSessionController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val downloads = AudioDownloads()

    /** The clip playing or starting: a local path (or file: URI) or a link on the server. */
    var currentURL by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var progress by mutableDoubleStateOf(0.0)
        private set

    /** Seconds. */
    var elapsed by mutableDoubleStateOf(0.0)
        private set

    private var _rate by mutableFloatStateOf(1f)

    /** 1, 1.5 or 2 (the voice bubbles' speed button). */
    var rate: Float
        get() = _rate
        set(value) {
            _rate = value
            player?.setRate(value)
        }

    private var player: AudioEngine.Player? = null
    private var timer: Job? = null

    /** The clip being loaded; a newer tap (or a stop) makes an older load land nowhere. */
    private var loadToken = Any()

    init {
        // A call or another app's audio took over: pause, like AVAudioPlayer on an interruption.
        session.onLoss = { scope.launch { if (isPlaying && player != null) pause() } }
    }

    fun isCurrent(url: String?): Boolean = url != null && url == currentURL

    /**
     * Read straight from the player, for frame-by-frame progress (read it inside a frame loop,
     * `withFrameNanos`, the way the iPhone reads it in a TimelineView).
     */
    val liveProgress: Double
        get() {
            val player = player ?: return progress
            val duration = player.duration
            return if (duration > 0) player.currentTime / duration else progress
        }

    fun toggle(url: String) {
        if (currentURL == url) {
            val player = player
            if (player != null) {
                if (player.isPlaying) pause() else resume()
            } else {
                stop() // still starting: a second tap cancels it
            }
            return
        }
        play(url)
    }

    /**
     * The button flips to pause at once; audio focus and the player are set up off the main thread
     * (preparing can block for a noticeable moment, which would freeze touches right after tapping play).
     */
    fun play(url: String) {
        stop()
        currentURL = url
        isPlaying = true
        val token = Any()
        loadToken = token
        scope.launch {
            // A clip on the server is played from its copy in the cache (null when it couldn't be fetched).
            val file = localFile(url) ?: localCopy(url)
            val made = withContext(Dispatchers.IO) {
                if (file == null) return@withContext null
                try {
                    session.activate(AudioFocus.Category.PLAYBACK)
                    engine.player(file)
                } catch (e: Exception) {
                    null
                }
            }
            // Stopped, paused or switched to another clip meanwhile.
            if (loadToken !== token || currentURL != url || !isPlaying) {
                if (made != null) {
                    made.release()
                    session.release()
                }
                return@launch
            }
            if (made == null) {
                currentURL = null
                isPlaying = false
                session.release()
                return@launch
            }
            made.onFinish = { finished(made) }
            made.play(rate)
            player = made
            startTimer()
        }
    }

    fun seek(fraction: Double) {
        val player = player ?: return
        player.currentTime = player.duration * fraction
        tick()
    }

    fun pause() {
        player?.pause()
        isPlaying = false
        timer?.cancel()
    }

    fun resume() {
        player?.play(rate)
        isPlaying = true
        startTimer()
    }

    fun stop() {
        loadToken = Any()
        // Something was playing or starting: focus goes back to other apps (dropped if a clip starts
        // right after, as `play` does).
        if (currentURL != null) session.release()
        player?.let {
            it.stop()
            it.release()
        }
        player = null
        timer?.cancel()
        isPlaying = false
        progress = 0.0
        elapsed = 0.0
        currentURL = null
    }

    private fun startTimer() {
        timer?.cancel()
        timer = scope.launch {
            while (isActive) {
                delay(1000L / 30)
                tick()
            }
        }
    }

    private fun tick() {
        val player = player ?: return
        val duration = player.duration
        if (duration <= 0) return
        elapsed = player.currentTime
        progress = player.currentTime / duration
    }

    private fun finished(finished: AudioEngine.Player) {
        if (player !== finished) return
        timer?.cancel()
        isPlaying = false
        progress = 0.0
        elapsed = 0.0
        currentURL = null
        player = null
        finished.release()
        session.release()
    }

    /**
     * The player plays files only: a clip on the server is downloaded once into the cache (keys are
     * immutable, so the copy never goes stale). A signed link that expired is renewed first; null when
     * the clip can't be fetched (offline, or no longer visible).
     */
    suspend fun localCopy(remote: String): File? {
        val file = cacheFile(remote) ?: return null
        if (withContext(Dispatchers.IO) { file.exists() }) return file
        return downloads.fetch(remote, file)
    }

    /** Where a clip on the server is kept: named after its key (the link's path), never its signature. */
    private fun cacheFile(remote: String): File? {
        val path = runCatching { URI(remote).path }.getOrNull() ?: return null
        val name = path.split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("-")
        if (name.isEmpty()) return null
        return File(File(engine.cacheDirectory, "audio"), name)
    }

    companion object {
        /** A voice intro saved on a profile: a picked file ("/...") or a link on the server. */
        fun url(voice: String): String? = when {
            voice.startsWith("/") || voice.startsWith("file:") -> voice
            voice.startsWith("http") -> voice
            else -> null
        }

        /** The local file a clip names, or null for one on the server. */
        fun localFile(url: String): File? = when {
            url.startsWith("/") -> File(url)
            url.startsWith("file:") -> runCatching { File(URI(url)) }.getOrNull()
            else -> null
        }
    }
}

/** Clip downloads, streamed to disk (never held in memory), one per clip however many taps ask for it. */
private class AudioDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val running = mutableMapOf<File, Deferred<File?>>()

    suspend fun fetch(remote: String, file: File): File? {
        val task = mutex.withLock { running.getOrPut(file) { scope.async { download(remote, file) } } }
        val result = task.await()
        mutex.withLock { if (running[file] === task) running.remove(file) }
        return result
    }

    private suspend fun download(remote: String, file: File): File? {
        val link = MediaURL.fresh(remote)
        return withContext(Dispatchers.IO) {
            val connection = try {
                URL(link).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                return@withContext null
            }
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                if (connection.responseCode != 200) return@withContext null
                val directory = file.parentFile ?: return@withContext null
                if (!directory.isDirectory && !directory.mkdirs()) return@withContext null
                val temp = File.createTempFile("clip-", ".part", directory)
                try {
                    connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
                    // Another download may have landed first: either copy is the same immutable clip.
                    when {
                        file.exists() -> file
                        temp.renameTo(file) -> file
                        else -> null
                    }
                } finally {
                    temp.delete()
                }
            } catch (e: IOException) {
                null
            } finally {
                connection.disconnect()
            }
        }
    }
}
