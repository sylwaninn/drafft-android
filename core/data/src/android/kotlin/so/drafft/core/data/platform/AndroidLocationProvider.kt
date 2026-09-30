package so.drafft.core.data.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import so.drafft.core.data.platform.LocationProvider.Authorization

/**
 * [LocationProvider] on the fused location provider: one reading at a time, city-block accuracy
 * (balanced power, coarse permission is enough), only while the app is in use.
 *
 * Asking for the permission needs an activity: the one on screen installs [requester] (a permission
 * launcher for coarse and fine location) and reports the answer with [onPermissionResult].
 */
class AndroidLocationProvider(
    private val context: Context,
    private val defaults: KeyValueStore,
) : LocationProvider {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private val state = MutableStateFlow(read())

    /** Shows the system prompt; set by the activity on screen. */
    @Volatile
    var requester: (() -> Unit)? = null

    override val authorization: StateFlow<Authorization> = state.asStateFlow()

    override fun refreshAuthorization() {
        state.value = read()
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

    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(): Coordinate? {
        refreshAuthorization()
        if (state.value != Authorization.ALLOWED) return null
        val token = CancellationTokenSource()
        return try {
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, token.token).await()
                ?.let { Coordinate(it.latitude, it.longitude) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            token.cancel()
            throw e
        } catch (e: Exception) {
            null
        }
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
    }
}
