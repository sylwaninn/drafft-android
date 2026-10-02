package so.drafft.core.data

import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import so.drafft.core.data.backend.asArray
import so.drafft.core.data.backend.asObject
import so.drafft.core.data.backend.asString
import so.drafft.core.data.backend.parseJsonOrNull
import so.drafft.core.data.backend.serverFilters
import so.drafft.core.data.backend.toBytes
import so.drafft.core.model.DiscoverFilters

/** Discovery's bookkeeping, not observed by the screens. */
class DiscoveryState {
    /** The deck read in flight, and which one (a newer one supersedes it). */
    var load: Job? = null
    var generation = 0

    /** Swipes and undos, one after the other. */
    var chain: Job? = null

    /** Everyone swiped in this session: never shown again by a batch read before the server had the swipe. */
    val swiped: MutableSet<String> = mutableSetOf()

    /** The server's JSON of each card on screen, for the local cache. */
    var raw: Map<String, JsonObject> = emptyMap()

    /** Matches already known: a new one from the channel shows the banner. */
    val knownMatches: MutableSet<String> = mutableSetOf()

    /** Whether the matches were read once (the first read never shows banners). */
    var matchesRead = false

    /** How long reads take and how fast the person swipes: when to read the next batch. */
    val pace = DeckPace()

    /** Swipes sent but not answered yet. */
    var pendingSwipes = 0

    /** How fresh each part is, and which read of it is the newest. */
    val freshness = DiscoveryFreshness()

    /** The last read brought fewer cards than asked: swipes don't ask again until another refresh. */
    var exhausted = false
}

/** The deck as kept on this phone: the filters it was read with and the cards' own JSON. */
object DeckCache {
    /** Media links are signed for at least an hour: an older copy isn't shown. */
    const val MAX_AGE_SECONDS: Long = 45 * 60

    data class Saved(val filters: String, val cards: JsonElement)

    /** The filters as one stable string. */
    fun key(filters: DiscoverFilters): String = sorted(filters.serverFilters).toString()

    private fun sorted(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.toSortedMap().mapValues { sorted(it.value) })
        is JsonArray -> JsonArray(e.map(::sorted))
        else -> e
    }

    fun encode(filters: String, cards: List<JsonObject>): ByteArray =
        JsonObject(mapOf("filters" to JsonPrimitive(filters), "cards" to JsonArray(cards))).toBytes()

    fun decode(data: ByteArray): Saved? {
        val o = data.parseJsonOrNull().asObject ?: return null
        val filters = o["filters"].asString ?: return null
        val cards = o["cards"] ?: return null
        return Saved(filters, cards)
    }

    /** Each card's JSON by id, from a list the server sent. */
    fun rawByID(data: ByteArray): Map<String, JsonObject> = rawByID(data.parseJsonOrNull())

    fun rawByID(list: JsonElement?): Map<String, JsonObject> {
        val cards = list.asArray ?: return emptyMap()
        val out = LinkedHashMap<String, JsonObject>()
        for (card in cards) {
            val o = card.asObject ?: continue
            val id = o["id"].asString?.lowercase() ?: continue
            out[id] = o
        }
        return out
    }
}
