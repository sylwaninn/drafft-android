package so.drafft.core.ui.platform

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
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
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()
    }
    // The disk cache reads its journal once, here rather than on the main thread at the first photo
    // (`ImageStore` asks it which copies are already on this phone).
    Thread({ SingletonImageLoader.get(app).diskCache?.size }, "image-cache-warmup").start()
    NetworkQuality.shared.onChange { limited ->
        // Six downloads share a fast line; on a slow one, three, so the photo on screen isn't split six ways.
        PhotoDownloads.shared.limit = if (limited) Images.limitedDownloads else Images.downloads
    }
    watchNetwork(app)
}

/** Metered (cellular) and Data Saver, as they change. */
private fun watchNetwork(context: Context) {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
    fun update(capabilities: NetworkCapabilities?) {
        val metered = capabilities != null &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))
        val saver = connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        NetworkQuality.shared.path(expensive = metered, constrained = saver)
    }
    runCatching {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update(capabilities)
            override fun onLost(network: Network) = update(null)
        })
    }
    ContextCompat.registerReceiver(
        context,
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                update(connectivity.getNetworkCapabilities(connectivity.activeNetwork))
            }
        },
        IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED),
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    update(connectivity.getNetworkCapabilities(connectivity.activeNetwork))
}

/**
 * Each photo download waits for its turn in [PhotoDownloads] (by the priority its request carries),
 * keeps its slot until its body is read or closed, and is timed from there: how fast it arrives tells
 * how fast the line is.
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
        val release = { if (released.compareAndSet(false, true)) PhotoDownloads.shared.release() }
        val start = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (e: Throwable) {
            release()
            throw e
        }
        val body = response.body
        val successful = response.isSuccessful
        return response.newBuilder()
            .body(
                TimedBody(body) { bytes, complete ->
                    release()
                    if (complete && successful) NetworkQuality.shared.measured(bytes, (System.nanoTime() - start) / 1e9)
                },
            )
            .build()
    }
}

/** A response body that reports, once, how many bytes it gave and whether it was read to the end. */
private class TimedBody(
    private val body: ResponseBody,
    private val onEnd: (bytes: Long, complete: Boolean) -> Unit,
) : ResponseBody() {
    private val ended = AtomicBoolean(false)
    private var bytes = 0L

    private fun end(complete: Boolean) {
        if (ended.compareAndSet(false, true)) onEnd(bytes, complete)
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
                if (read == -1L) end(complete = true) else bytes += read
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
