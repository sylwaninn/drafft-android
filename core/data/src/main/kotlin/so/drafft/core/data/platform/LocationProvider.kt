package so.drafft.core.data.platform

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.StateFlow

/** A point on Earth (the iPhone's `CLLocationCoordinate2D`). */
data class Coordinate(val latitude: Double, val longitude: Double) {
    /** Great-circle distance in kilometres (`CLLocation.distance(from:)` / 1000). */
    fun distanceKm(to: Coordinate): Double {
        val r = 6_371.0088
        val dLat = Math.toRadians(to.latitude - latitude)
        val dLng = Math.toRadians(to.longitude - longitude)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(latitude)) * cos(Math.toRadians(to.latitude)) * sin(dLng / 2).let { it * it }
        return 2 * r * asin(sqrt(a))
    }
}

/**
 * The phone's location, one reading at a time, reduced accuracy, only while the app is in use (the
 * iPhone's `CLLocationManager` with `kCLLocationAccuracyReduced`). Implemented on Android with the
 * fused location provider (`AndroidLocationProvider`).
 */
interface LocationProvider {
    enum class Authorization {
        /** Never asked. */
        NOT_DETERMINED,

        /** "While using the app" (or more). */
        ALLOWED,

        /** Refused, or restricted. */
        DENIED,
    }

    /** The permission as last read; follows [refreshAuthorization] and the answer to a request. */
    val authorization: StateFlow<Authorization>

    /** Reads the permission again (launch, back from Settings). */
    fun refreshAuthorization()

    /** Asks for "while using the app"; the answer lands in [authorization]. */
    fun requestWhenInUseAuthorization()

    /** One reduced-accuracy reading, or null (not allowed, or it failed). */
    suspend fun currentLocation(): Coordinate?

    /** The city of a place from the system geocoder (never the street), or null. */
    suspend fun locality(of: Coordinate): String?
}
