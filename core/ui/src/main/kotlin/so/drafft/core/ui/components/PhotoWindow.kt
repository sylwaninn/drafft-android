package so.drafft.core.ui.components

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import so.drafft.core.data.media.Images
import so.drafft.core.data.media.NetworkQuality
import so.drafft.core.model.Profile

/**
 * The deck's photos fetched ahead, as a window that moves with every swipe (the way a list prefetches
 * the rows about to scroll in). The cards on screen load their own photo, the one in play first
 * (`SwipeCard`'s priority); the window covers what comes after them:
 *
 * - on a good connection, the portraits of the cards on screen and of the next 6, to disk, at the copy
 *   their card needs, then the other photos of the card in play (its profile, if opened, at the copy it
 *   asks for);
 * - on a limited one ([NetworkQuality]), a small copy of the portraits on screen and of the next 8
 *   instead ([ImageStore.preview], about 20 kB each): a full copy can't keep up with fast swipes on a
 *   slow line, a small one can, so no card shows only its blurred preview. They come ahead of the card in
 *   play's full copy too, and the cards behind it get their full copy last (`DiscoverView`'s photo
 *   priority).
 *
 * Whatever leaves the window is cancelled: a fast run of swipes never leaves downloads running for cards
 * already gone, which would take the line from the card in play. Its requests start after the cards on
 * screen have asked for theirs (an effect runs after the frame they're composed in), and wait behind
 * them in the download queue (`PhotoDownloads`, by priority).
 */
class PhotoWindow private constructor() {
    /**
     * Two at a time each, under the six (two when limited) downloads the pipeline allows: the cards on
     * screen always have room. Small copies are decoded ahead too (under a megabyte each), with the
     * request the card's own small-copy layer makes: shown on arrival, from memory.
     */
    private val portraits = Prefetcher(maxConcurrent = 2)
    private val previews = Prefetcher(maxConcurrent = 2)
    private val extras = Prefetcher(maxConcurrent = 1)

    private class Aim(val context: PlatformContext, val deck: List<Profile>, val onScreen: Int, val width: Int, val height: Int)

    /**
     * The last aim, taken again when the connection changes ([NetworkQuality]): the full copies a limited
     * line can't afford stop at once, not at the next swipe.
     */
    private var last: Aim? = null

    /** The requests being built for the last aim (off the main thread: they look on disk). */
    private var building: Job? = null

    init {
        NetworkQuality.shared.onChange {
            mainScope.launch { last?.let { aim(it.context, it.deck, it.onScreen, it.width, it.height) } }
        }
    }

    /**
     * Aims the window after the [onScreen] cards of [deck] (portraits, then each profile's other photos),
     * drawn in a frame of [width] × [height] pixels. Main thread; the requests are built off it (what's
     * already on this phone is left out), then the window moves.
     */
    fun aim(context: PlatformContext, deck: List<Profile>, onScreen: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        last = Aim(context, deck, onScreen, width, height)
        building?.cancel()
        building = mainScope.launch {
            val limited = NetworkQuality.shared.isLimited
            val (ahead, small, more) = withContext(Dispatchers.IO) {
                // The cards ahead first, then the ones on screen (their own request shares the download,
                // `SharedFetches`, so a prefetch is never cancelled as its card arrives): the permits go to
                // the real look-ahead, not to waiting behind downloads already running.
                val ahead = if (limited) emptyList() else (deck.drop(onScreen).take(6) + deck.take(onScreen)).map { it.portrait }
                Triple(
                    ahead.mapNotNull { ImageStore.prefetchRequest(context, it, width, height, Images.Priority.LOW) },
                    if (limited) {
                        deck.take(onScreen + 8).map { it.portrait }
                            .mapNotNull { ImageStore.preview(context, it, width, height) }
                            .filterNot { ImageStore.isInMemory(context, it) }
                    } else {
                        emptyList()
                    },
                    if (limited) {
                        emptyList()
                    } else {
                        deck.firstOrNull()?.photos.orEmpty().mapNotNull {
                            ImageStore.prefetchRequest(context, it, width, height, Images.Priority.VERY_LOW, detail = true)
                        }
                    },
                )
            }
            portraits.set(ahead)
            previews.set(small)
            extras.set(more)
        }
    }

    /** Everything stops (the deck is gone, another screen). */
    fun clear() {
        last = null
        building?.cancel()
        building = null
        for (prefetcher in listOf(portraits, previews, extras)) prefetcher.set(emptyList())
    }

    companion object {
        private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        val deck = PhotoWindow()
    }
}

/**
 * A prefetcher: fetches its requests in order, [maxConcurrent] at a time. [set] replaces
 * them: one no longer asked for is cancelled, one still asked for keeps running (or stays done).
 */
private class Prefetcher(maxConcurrent: Int) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val slots = Semaphore(maxConcurrent)
    private val jobs = LinkedHashMap<String, Job>()

    fun set(requests: List<ImageRequest>) {
        val wanted = LinkedHashMap<String, ImageRequest>()
        for (request in requests) wanted.putIfAbsent(key(request), request)
        val gone = jobs.keys.filter { it !in wanted }
        for (key in gone) jobs.remove(key)?.cancel()
        for ((key, request) in wanted) {
            if (key in jobs) continue
            // In order: the semaphore lets them through first come, first served.
            jobs[key] = scope.launch {
                val result = slots.withPermit { SingletonImageLoader.get(request.context).execute(request) }
                // Failed (offline): tried again the next time the window is aimed.
                if (result is ErrorResult && jobs[key] === coroutineContext[Job]) jobs.remove(key)
            }
        }
    }

    private fun key(request: ImageRequest): String = request.diskCacheKey ?: request.data.toString()
}
