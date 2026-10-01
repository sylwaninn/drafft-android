@file:OptIn(coil3.annotation.ExperimentalCoilApi::class)

package so.drafft.core.ui.platform

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.NetworkClient
import coil3.network.NetworkFetcher
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.FileSystem
import okio.ForwardingSource
import okio.buffer
import so.drafft.core.data.media.Images
import so.drafft.core.data.media.NetworkQuality
import so.drafft.core.data.media.PhotoDownloads

// Ports `Images.configure()` (Drafft/Services/Media/Images.swift), its `SignedLinkLoader` timing, and the
// path half of Drafft/Services/Media/NetworkQuality.swift (NWPathMonitor there, ConnectivityManager here).

/**
 * The app's image pipeline, once, at launch, before the first photo is drawn: a memory cache sized to
 * the phone, a capped disk cache, and one HTTP client whose photo downloads go through [PhotoDownloads]
 * (by priority, fewer at once on a limited connection) and are timed for [NetworkQuality].
 *
 * Coil keeps any entry up to the whole memory cache (unlike Nuke's default tenth, which refused a
 * full-screen photo on the iPhone), and empties it when the system asks for memory.
 */
fun installImages(context: Context) {
    val app = context.applicationContext
    val client = OkHttpClient.Builder()
        // The queue below decides how many photos download at once; OkHttp's own per-host cap (5) would
        // admit them in arrival order before their priority is known.
        .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 64 })
        .addInterceptor(PhotoDownloadInterceptor)
        .eventListener(CancelledCalls)
        .build()
    val memory = ActivityManager.MemoryInfo().also { info ->
        app.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
    }.totalMem
    SingletonImageLoader.setSafe { platformContext ->
        ImageLoader.Builder(platformContext)
            .memoryCache { MemoryCache.Builder().maxSizeBytes(Images.memoryLimit(memory)).build() }
            // Coil's default folder, so what's already downloaded stays.
            .diskCache {
                DiskCache.Builder()
                    .directory(FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "coil3_disk_cache")
                    .maxSizeBytes(Images.diskLimit)
                    .build()
            }
            .components { add(NetworkFetcher.Factory(networkClient = { PhotoNetworkClient(client) })) }
            .build()
    }
    // The disk cache reads its journal once, here rather than on the main thread at the first photo
    // (`ImageStore` asks it which copies are already on this phone).
    Thread({ SingletonImageLoader.get(app).diskCache?.size }, "image-cache-warmup").start()
    NetworkQuality.shared.onChange { limited ->
        // Six downloads share a fast line; on a slow one, two, so the photo on screen isn't split six ways.
        PhotoDownloads.shared.limit = if (limited) Images.limitedDownloads else Images.downloads
    }
    watchNetwork(app)
}

/** Which network is in use (another one: its speed is measured again) and Data Saver, as they change. */
private fun watchNetwork(context: Context) {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
    fun update(network: Network?) {
        val saver = connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        NetworkQuality.shared.path(network = network?.networkHandle?.toString(), constrained = saver)
    }
    runCatching {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update(network)
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update(network)
            override fun onLost(network: Network) = update(null)
        })
    }
    ContextCompat.registerReceiver(
        context,
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                update(connectivity.activeNetwork)
            }
        },
        IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED),
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    update(connectivity.activeNetwork)
}

/**
 * Coil's OkHttp client, with one difference: a response that arrives after its request was cancelled is
 * closed (Coil 3.3's own drops it unclosed, and its download slot in [PhotoDownloads] with it: two such
 * leaks on a limited line and no photo loads anywhere in the app).
 */
private class PhotoNetworkClient(private val client: OkHttpClient) : NetworkClient {
    override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (response: NetworkResponse) -> T): T {
        val call = client.newCall(request.toOkHttp())
        val response = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, dropped, _ -> runCatching { dropped.close() } }
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            })
        }
        return response.use { block(it.toNetworkResponse()) }
    }

    private suspend fun NetworkRequest.toOkHttp(): Request {
        val bytes = body?.let { body -> Buffer().also { body.writeTo(it) }.readByteString() }
        val headers = Headers.Builder()
        for ((key, values) in this.headers.asMap()) for (value in values) headers.addUnsafeNonAscii(key, value)
        return Request.Builder().url(url).method(method, bytes?.toRequestBody()).headers(headers.build()).build()
    }

    private fun Response.toNetworkResponse(): NetworkResponse {
        val headers = NetworkHeaders.Builder()
        for ((key, value) in this.headers) headers.add(key, value)
        return NetworkResponse(
            code = code,
            requestMillis = sentRequestAtMillis,
            responseMillis = receivedResponseAtMillis,
            headers = headers.build(),
            body = NetworkResponseBody(body.source()),
        )
    }
}

/** The slot each running download holds, by call: released when the call is cancelled, whatever else happens. */
private val heldSlots = ConcurrentHashMap<Call, () -> Unit>()

/** A cancelled call gives its slot back, and whoever waits for one checks again at once. */
private object CancelledCalls : EventListener() {
    override fun canceled(call: Call) {
        heldSlots.remove(call)?.invoke()
        PhotoDownloads.shared.wake()
    }
}

/**
 * Each photo download waits for its turn in [PhotoDownloads] (by the priority its request carries),
 * keeps its slot until its body is read or closed or its call is cancelled, and is timed: how fast it
 * arrives tells how fast the line is.
 */
private object PhotoDownloadInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val asked = chain.request()
        val rank = asked.header(Images.PRIORITY_HEADER)?.toIntOrNull() ?: Images.Priority.NORMAL.ordinal
        val photo = asked.header(Images.PHOTO_HEADER)
        val request = asked.newBuilder().removeHeader(Images.PRIORITY_HEADER).removeHeader(Images.PHOTO_HEADER).build()
        val call = chain.call()
        if (!PhotoDownloads.shared.acquire(rank, photo) { call.isCanceled() }) throw IOException("Canceled")
        val released = AtomicBoolean(false)
        val release = {
            if (released.compareAndSet(false, true)) {
                heldSlots.remove(call)
                PhotoDownloads.shared.release()
            }
        }
        heldSlots[call] = release
        // Cancelled between the turn and the line above: [CancelledCalls] may have looked already.
        if (call.isCanceled()) {
            release()
            throw IOException("Canceled")
        }
        val timing = NetworkQuality.shared.download()
        val response = try {
            chain.proceed(request)
        } catch (e: Throwable) {
            release()
            timing.ended(failed = true)
            throw e
        }
        val body = response.body
        val successful = response.isSuccessful
        return response.newBuilder()
            .body(
                TimedBody(body, onBytes = timing::received) { complete ->
                    release()
                    timing.ended(failed = !(complete && successful))
                },
            )
            .build()
    }
}

/** A response body that reports each chunk it gives, then, once, whether it was read to the end. */
private class TimedBody(
    private val body: ResponseBody,
    private val onBytes: (Long) -> Unit,
    private val onEnd: (complete: Boolean) -> Unit,
) : ResponseBody() {
    private val ended = AtomicBoolean(false)

    private fun end(complete: Boolean) {
        if (ended.compareAndSet(false, true)) onEnd(complete)
    }

    private val source: BufferedSource by lazy {
        object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = try {
                    super.read(sink, byteCount)
                } catch (e: IOException) {
                    end(complete = false)
                    throw e
                }
                if (read == -1L) end(complete = true) else onBytes(read)
                return read
            }

            override fun close() {
                end(complete = false)
                super.close()
            }
        }.buffer()
    }

    override fun contentType(): MediaType? = body.contentType()

    override fun contentLength(): Long = body.contentLength()

    override fun source(): BufferedSource = source
}
