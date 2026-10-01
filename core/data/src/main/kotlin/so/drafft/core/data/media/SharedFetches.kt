package so.drafft.core.data.media

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

// Nuke's shared downloads (Drafft/Services/Media/Images.swift: "one download per photo, however many
// views ask for it at once"), which Coil doesn't have: used by the image pipeline's fetcher
// (`installImages` in core:ui).

/**
 * One download per copy (its disk cache key), however many requests ask for it at once: the others wait
 * for it, then read it from the disk cache. So the card in play never downloads its photo a second time
 * beside the window's prefetch of it, and a prefetch is never thrown away as its card comes on screen.
 * If the first one fails or is cancelled, the next one downloads it.
 */
class SharedFetches {
    private val running = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    suspend fun <T> once(key: String, fetch: suspend () -> T): T {
        while (true) {
            val mine = CompletableDeferred<Unit>()
            val other = running.putIfAbsent(key, mine)
            if (other != null) {
                other.await()
                continue
            }
            try {
                return fetch()
            } finally {
                running.remove(key, mine)
                mine.complete(Unit)
            }
        }
    }

    companion object {
        val shared = SharedFetches()
    }
}
