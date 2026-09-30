package so.drafft.core.data.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import so.drafft.core.data.platform.LocationProvider.Authorization

/**
 * [LocationProvider] on the fused location provider, with the system's own providers alongside for
 * phones without Google Play services: one reading at a time, city-block accuracy (coarse
 * permission is enough), only while the app is in use.
 *
 * Asking for the permission needs an activity: the one on screen installs [requester] (a permission
 * launcher for coarse and fine location) and reports the answer with [onPermissionResult].
 */
class AndroidLocationProvider(
    private val context: Context,
    private val defaults: KeyValueStore,
) : LocationProvider {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private val locationManager by lazy { context.getSystemService<LocationManager>() }
    private val state = MutableStateFlow(read())
    private val off = MutableStateFlow(readServicesOff())
    private val prompt = MutableStateFlow(readCanPrompt())

    /** Shows the system prompt; set by the activity on screen. */
    @Volatile
    var requester: (() -> Unit)? = null

    /**
     * Whether Android would still show the prompt after a refusal (`shouldShowRequestPermissionRationale`);
     * set by the activity on screen, as it needs one.
     */
    @Volatile
    var rationale: (() -> Boolean)? = null

    init {
        // The location switch can change without the app leaving the screen (quick settings): followed
        // live, like the iPhone's authorization callback.
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    refreshAuthorization()
                }
            },
            IntentFilter(LocationManager.MODE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override val authorization: StateFlow<Authorization> = state.asStateFlow()
    override val servicesOff: StateFlow<Boolean> = off.asStateFlow()
    override val canPrompt: StateFlow<Boolean> = prompt.asStateFlow()

    override fun refreshAuthorization() {
        state.value = read()
        off.value = readServicesOff()
        prompt.value = readCanPrompt()
    }

    private fun readServicesOff(): Boolean =
        locationManager?.let { !LocationManagerCompat.isLocationEnabled(it) } ?: false

    private fun readCanPrompt(): Boolean = when (read()) {
        Authorization.ALLOWED -> false
        Authorization.NOT_DETERMINED -> true
        Authorization.DENIED -> rationale?.invoke() ?: false
    }

    override fun requestWhenInUseAuthorization() {
        val ask = requester
        if (ask == null) {
            refreshAuthorization()
            return
        }
        ask()
    }

    /** The prompt was answered (whatever the answer): it counts as asked from now on. */
    fun onPermissionResult() {
        defaults.putBoolean(ASKED_KEY, true)
        refreshAuthorization()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun read(): Authorization = when {
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION) -> Authorization.ALLOWED
        defaults.getBoolean(ASKED_KEY) == true -> Authorization.DENIED
        else -> Authorization.NOT_DETERMINED
    }

    /**
     * Like the iPhone's `requestLocation()`: whatever source can answer does, within [TIMEOUT_MS].
     * A recent reading is used as is. Otherwise every source is asked at once and the first answer
     * wins: the fused provider on Wi-Fi and cell towers, and on GPS, which answers when Google
     * Location Accuracy is off; the system's own providers, the only ones on a phone without Google
     * Play services. Nothing fresh in time: a reading from the last [STALE_MS], still the right
     * area for most people.
     */
    override suspend fun currentLocation(): Coordinate? {
        refreshAuthorization()
        if (state.value != Authorization.ALLOWED) return null
        val location = lastKnown(FRESH_MS)
            ?: withTimeoutOrNull(TIMEOUT_MS) { firstOf(sources()) }
            ?: lastKnown(STALE_MS)
        return location?.let { Coordinate(it.latitude, it.longitude) }
    }

    private val hasPlayServices: Boolean
        get() = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    /** The system's providers this app may use and that are on, most useful first. */
    private fun systemProviders(): List<String> {
        val manager = locationManager ?: return emptyList()
        val usable = orNull { manager.getProviders(true) }.orEmpty()
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { it in usable }
    }

    private fun sources(): List<suspend () -> Location?> = buildList {
        if (hasPlayServices) {
            add { fused(Priority.PRIORITY_BALANCED_POWER_ACCURACY) }
            add { fused(Priority.PRIORITY_HIGH_ACCURACY) }
        }
        systemProviders().forEach { provider -> add { system(provider) } }
    }

    @SuppressLint("MissingPermission")
    private suspend fun fused(priority: Int): Location? {
        val request = CurrentLocationRequest.Builder()
            .setPriority(priority)
            .setDurationMillis(TIMEOUT_MS)
            .setMaxUpdateAgeMillis(FRESH_MS)
            .build()
        val token = CancellationTokenSource()
        return orNull {
            try {
                client.getCurrentLocation(request, token.token).await()
            } catch (e: CancellationException) {
                token.cancel()
                throw e
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun system(provider: String): Location? {
        val manager = locationManager ?: return null
        return suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            try {
                LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) {
                    if (cont.isActive) cont.resume(it)
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /** The newest reading any source already has, if younger than [maxAgeMs]. */
    @SuppressLint("MissingPermission")
    private suspend fun lastKnown(maxAgeMs: Long): Location? {
        val fused = if (hasPlayServices) orNull { client.lastLocation.await() } else null
        val system = locationManager?.let { manager ->
            (systemProviders() + LocationManager.PASSIVE_PROVIDER).mapNotNull { orNull { manager.getLastKnownLocation(it) } }
        }.orEmpty()
        return (listOfNotNull(fused) + system)
            .filter { ageMs(it) <= maxAgeMs }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }

    private fun ageMs(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    /** The first reading a source gives; null once they have all answered without one. */
    private suspend fun firstOf(sources: List<suspend () -> Location?>): Location? = coroutineScope {
        val first = CompletableDeferred<Location?>()
        val asks = sources.map { source -> launch { source()?.let(first::complete) } }
        launch {
            asks.joinAll()
            first.complete(null)
        }
        first.await().also { coroutineContext.cancelChildren() }
    }

    /** A source that fails (revoked permission, Play services missing or outdated) gives nothing. */
    private inline fun <T> orNull(block: () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    override suspend fun locality(of: Coordinate): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        // Only the locality is read: street fields never are.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(of.latitude, of.longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) {
                        cont.resume(addresses.firstOrNull()?.locality)
                    }

                    override fun onError(errorMessage: String?) {
                        cont.resume(null)
                    }
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocation(of.latitude, of.longitude, 1)?.firstOrNull()?.locality }.getOrNull()
            }
        }
    }

    private companion object {
        const val ASKED_KEY = "location.permissionAsked"

        /** How long a fresh reading may take: long enough for a GPS fix outdoors. */
        const val TIMEOUT_MS = 20_000L

        /** A reading this recent is used without asking again. */
        const val FRESH_MS = 2 * 60_000L

        /** The oldest reading used when nothing fresh comes in time. */
        const val STALE_MS = 30 * 60_000L
    }
}
