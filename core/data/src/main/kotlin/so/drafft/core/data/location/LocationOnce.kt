package so.drafft.core.data.location

import so.drafft.core.data.backend.Backend
import so.drafft.core.data.backend.attempt
import so.drafft.core.data.backend.jsonOf
import so.drafft.core.data.platform.LocationProvider

/**
 * The location, once, when the server has none on file (`location_required`): the same blurred,
 * reduced-accuracy area as sign-up, sent with `set_location`.
 */
class LocationOnce(private val location: LocationProvider, private val backend: Backend) {
    /** Whether a location was sent. */
    suspend fun send(): Boolean {
        location.refreshAuthorization()
        if (location.authorization.value != LocationProvider.Authorization.ALLOWED) return false
        val c = location.currentLocation()?.let(LocationPrivacy::blur) ?: return false
        return attempt { backend.rpc("set_location", jsonOf("p_lat" to c.latitude, "p_lng" to c.longitude)) } != null
    }
}
