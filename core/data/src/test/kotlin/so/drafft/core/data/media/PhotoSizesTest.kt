package so.drafft.core.data.media

import kotlin.test.assertEquals
import org.junit.Test

// The sized photo links: the widths asked of the media Worker
// (cloudflare/media-worker in drafft-backend), and the cache key that keeps one copy per width.
class PhotoSizesTest {
    private val base = "https://media.getdrafft.com"
    private val key = "u/2b1c6a4e-0b2d-4f6e-8a1c-3d4e5f607182/photos/abc.jpg"
    private val signed = "$base/$key?exp=1790000000&sig=deadbeef"

    @Test
    fun signedLinksAskForTheWidth() {
        assertEquals("$signed&w=320", Images.sized(signed, 320))
        assertEquals("$signed&w=1440", Images.sized("$signed&w=320", 1_440))
    }

    @Test
    fun noWidthIsTheOriginal() {
        assertEquals(signed, Images.sized(signed, null))
        assertEquals(signed, Images.sized("$signed&w=320", null))
    }

    @Test
    fun otherLinksAreLeftAlone() {
        // A signed link wherever it points (locally, the app-config host isn't the signed links' host).
        val local = "http://10.0.2.2:8787/$key?exp=1790000000&sig=deadbeef"
        assertEquals("$local&w=640", Images.sized(local, 640))
        assertEquals("$base/$key", Images.sized("$base/$key", 256))
        assertEquals("$base/logo.png?exp=1&sig=2", Images.sized("$base/logo.png?exp=1&sig=2", 256))
    }

    @Test
    fun cacheKeyKeepsTheWidthNotTheSignature() {
        assertEquals("$base/$key", MediaURL.canonical(signed))
        assertEquals("$base/$key?w=320", MediaURL.canonical("$signed&w=320"))
        assertEquals("$base/$key?w=320", MediaURL.canonical("$base/$key?w=320&exp=1&sig=2"))
    }

    @Test
    fun widthCarriesOverToARenewedLink() {
        val renewed = "$base/$key?exp=1790003600&sig=cafe"
        assertEquals("$renewed&w=640", MediaURL.withWidth(renewed, MediaURL.width("$signed&w=640")))
        assertEquals(renewed, MediaURL.withWidth("$renewed&w=640", null))
    }
}
