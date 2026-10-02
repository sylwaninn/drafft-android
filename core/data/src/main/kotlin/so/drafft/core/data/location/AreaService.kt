package so.drafft.core.data.location

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.floor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.backend.asObject
import so.drafft.core.data.backend.attempt
import so.drafft.core.data.backend.attemptOrNull
import so.drafft.core.data.backend.parseJsonOrNull
import so.drafft.core.data.backend.string
import so.drafft.core.data.platform.Coordinate
import so.drafft.core.data.platform.LocationProvider
import so.drafft.core.data.platform.LocationProvider.Authorization
import so.drafft.core.model.L

/** What a profile shows for "where": an area (arrondissement or city), never a street. */
data class Area(
    /** "Lyon 4", "Paris 11", "Annecy" */
    val name: String,
    val city: String,
) {
    val id: String get() = name
}

/**
 * The contract with the backend. The app only ever sends a blurred position; the server
 * answers with an area name. Same answer on iOS, Android and web, and no street can leak.
 */
fun interface AreaResolving {
    suspend fun area(blurred: Coordinate): Area?
}

object LocationPrivacy {
    /** Snap to the centre of a ~1 km grid cell before anything leaves the device. */
    fun blur(c: Coordinate): Coordinate {
        val step = 0.01 // about 1.1 km of latitude, 0.7 to 0.8 km of longitude in France
        return Coordinate(
            latitude = floor(c.latitude / step) * step + step / 2,
            longitude = floor(c.longitude / step) * step + step / 2,
        )
    }

    /** Distances are shown rounded, so they can't be triangulated back to a home. */
    fun rounded(km: Double): String = if (km < 1) L("Less than 1 km") else L("%d km", Math.round(km).toInt())
}

/**
 * The server's answer (`area_at`): the commune or arrondissement the blurred position falls in,
 * from official boundaries, so every app shows the same name without a geocoder (Android without
 * Google Play services has none). Offline, outside France, or before the areas are loaded, the
 * server has none: resolved on the device instead ([fallback]).
 */
class ServerAreaResolver(private val backend: Backend, private val fallback: AreaResolving) : AreaResolving {
    override suspend fun area(blurred: Coordinate): Area? =
        attempt { backend.rpc("area_at", mapOf("p_lat" to blurred.latitude, "p_lng" to blurred.longitude)) }
            ?.let(::areaFrom)
            ?: fallback.area(blurred)

    companion object {
        /** `{"name", "city"}`, or null for the server's `null` and anything it doesn't recognise. */
        fun areaFrom(response: ByteArray): Area? {
            val found = response.parseJsonOrNull().asObject ?: return null
            return attemptOrNull { Area(found.string("name"), found.string("city")) }
        }
    }
}

/**
 * Resolved on the device, when the server has no answer: the arrondissements of Paris, Lyon and
 * Marseille are approximated by their centres, and any other place falls back to the city name
 * from the system geocoder, never the street.
 */
class OnDeviceAreaResolver(private val location: LocationProvider) : AreaResolving {
    private class City(val name: String, val radiusKm: Double, val centres: List<Coordinate>)

    override suspend fun area(blurred: Coordinate): Area? {
        var best: Pair<Area, Double>? = null
        for (city in cities) {
            city.centres.forEachIndexed { i, c ->
                val km = blurred.distanceKm(c)
                if (km <= city.radiusKm && km < (best?.second ?: Double.POSITIVE_INFINITY)) {
                    best = Area("${city.name} ${i + 1}", city.name) to km
                }
            }
        }
        best?.let { return it.first }
        // Elsewhere: the city only. Street fields are never read.
        val city = location.locality(blurred) ?: return null
        return Area(city, city)
    }

    companion object {
        private fun c(lat: Double, lng: Double) = Coordinate(lat, lng)

        private val cities = listOf(
            City("Paris", 3.0, listOf(
                c(48.8625, 2.3363), c(48.8683, 2.3428), c(48.8630, 2.3600), c(48.8543, 2.3576),
                c(48.8445, 2.3507), c(48.8491, 2.3328), c(48.8562, 2.3121), c(48.8727, 2.3125),
                c(48.8770, 2.3375), c(48.8761, 2.3607), c(48.8591, 2.3800), c(48.8406, 2.3877),
                c(48.8283, 2.3623), c(48.8292, 2.3265), c(48.8401, 2.2929), c(48.8637, 2.2769),
                c(48.8873, 2.3067), c(48.8925, 2.3484), c(48.8871, 2.3848), c(48.8634, 2.4012),
            )),
            City("Lyon", 3.0, listOf(
                c(45.7699, 4.8292), c(45.7489, 4.8270), c(45.7597, 4.8506), c(45.7784, 4.8248),
                c(45.7563, 4.8030), c(45.7727, 4.8520), c(45.7337, 4.8398), c(45.7358, 4.8690),
                c(45.7757, 4.8047),
            )),
            City("Marseille", 5.0, listOf(
                c(43.2999, 5.3846), c(43.3130, 5.3637), c(43.3122, 5.3834), c(43.3063, 5.4007),
                c(43.2926, 5.3979), c(43.2878, 5.3808), c(43.2839, 5.3602), c(43.2412, 5.3801),
                c(43.2505, 5.4400), c(43.2759, 5.4262), c(43.2878, 5.4832), c(43.3067, 5.4432),
                c(43.3496, 5.4318), c(43.3440, 5.3900), c(43.3584, 5.3634), c(43.3610, 5.3228),
            )),
        )

        /** Every arrondissement, for picking an area by hand. */
        val allAreas: List<Area>
            get() = cities.flatMap { c -> c.centres.indices.map { Area("${c.name} ${it + 1}", c.name) } }
    }
}

/**
 * One-shot, reduced-accuracy location, asked only while the app is in use. Made per screen (sign-up
 * keeps one); [scope] is that screen's.
 */
class AreaLocator(
    private val location: LocationProvider,
    private val scope: CoroutineScope,
    private val server: AreaResolving = OnDeviceAreaResolver(location),
) {
    sealed interface State {
        data object Idle : State
        data object Locating : State
        data class Found(val area: Area) : State
        data object Denied : State
        data object Failed : State
    }

    var state: State by mutableStateOf(State.Idle)
        private set

    /** The last position found, already blurred (~1 km): what may be sent to the server. */
    var blurred: Coordinate? by mutableStateOf(null)
        private set

    private var job: Job? = null

    fun locate() {
        state = State.Locating
        job?.cancel()
        job = scope.launch {
            location.refreshAuthorization()
            when (location.authorization.value) {
                Authorization.NOT_DETERMINED -> {
                    location.requestWhenInUseAuthorization()
                    // The answer: located once allowed, denied otherwise.
                    val answer = location.authorization.first { it != Authorization.NOT_DETERMINED }
                    if (state != State.Locating) return@launch
                    if (answer == Authorization.ALLOWED) find() else state = State.Denied
                }
                Authorization.DENIED -> state = State.Denied
                Authorization.ALLOWED -> find()
            }
        }
    }

    private suspend fun find() {
        val c = location.currentLocation() ?: run {
            state = State.Failed
            return
        }
        val blurred = LocationPrivacy.blur(c)
        this.blurred = blurred
        state = server.area(blurred)?.let { State.Found(it) } ?: State.Failed
    }
}

/**
 * Location is required to use drafft: "While using the app" or more, precise or approximate. Watches
 * the permission and the phone's location switch; while either is off (or the permission was never
 * answered), the app shows a blocking screen until it's back on. One for the app.
 */
class LocationGate(private val location: LocationProvider) {
    /** The permission, for screens to observe (`collectAsState`). */
    val authorization: kotlinx.coroutines.flow.StateFlow<Authorization> get() = location.authorization

    /** The phone's location turned off for every app. */
    val servicesOff: kotlinx.coroutines.flow.StateFlow<Boolean> get() = location.servicesOff

    /** Whether the button can still show the system prompt (else it opens Settings). */
    val canPrompt: kotlinx.coroutines.flow.StateFlow<Boolean> get() = location.canPrompt

    val status: Authorization get() = location.authorization.value

    /** The only state the app may be used in. */
    val isAllowed: Boolean get() = status == Authorization.ALLOWED && !location.servicesOff.value

    /**
     * Re-read on launch and each time the app comes back (e.g. from Settings). Never answered: the
     * system prompt shows over the blocking screen.
     */
    fun refresh() {
        location.refreshAuthorization()
        if (status == Authorization.NOT_DETERMINED) location.requestWhenInUseAuthorization()
    }

    /** The system prompt, from the blocking screen's button. */
    fun request() {
        location.requestWhenInUseAuthorization()
    }
}
