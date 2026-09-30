package so.drafft.core.data.location

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

// The answers of `area_at` (drafft-backend, migration 20260930000701): an area, or null when the server has
// none, which sends the app to its own resolver.
class ServerAreaResolverTest {
    private fun area(json: String) = ServerAreaResolver.areaFrom(json.encodeToByteArray())

    @Test
    fun readsTheArea() {
        assertEquals(Area("Paris 11", "Paris"), area("""{"city": "Paris", "name": "Paris 11"}"""))
        assertEquals(Area("Annecy", "Annecy"), area("""{"name": "Annecy", "city": "Annecy"}"""))
    }

    @Test
    fun noAreaIsNull() {
        assertNull(area("null"))
        assertNull(area(""))
    }

    @Test
    fun anUnknownShapeIsNull() {
        assertNull(area("""{"name": "Paris 11"}"""))
        assertNull(area("""{"name": 11, "city": "Paris"}"""))
        assertNull(area("""["Paris 11"]"""))
    }
}
