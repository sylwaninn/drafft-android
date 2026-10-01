package so.drafft.core.data.media

import kotlin.test.assertEquals
import org.junit.Test

// The widths asked of the media Worker (cloudflare/media-worker in drafft-backend), and the cache key that
// keeps one copy per width.
class PhotoSizesTest {
    private val base = "https://media.getdrafft.com"
    private val key = "u/2b1c6a4e-0b2d-4f6e-8a1c-3d4e5f607182/photos/abc.jpg"
    private val signed = "$base/$key?exp=1790000000&sig=deadbeef"

    @Test
    fun smallestServedWidthThatCovers() {
        assertEquals("$signed&w=160", Images.sized(signed, 128, base))
        assertEquals("$signed&w=320", Images.sized(signed, 256, base))
        assertEquals("$signed&w=640", Images.sized(signed, 512, base))
        assertEquals("$signed&w=1080", Images.sized(signed, 1_080, base))
    }

    @Test
    fun largerThanServedIsTheOriginal() {
        assertEquals(signed, Images.sized(signed, 1_440, base))
        assertEquals(signed, Images.sized("$signed&w=320", 2_048, base))
    }

    @Test
    fun otherLinksAreLeftAlone() {
        val elsewhere = "https://example.com/$key"
        assertEquals(elsewhere, Images.sized(elsewhere, 256, base))
        assertEquals(signed, Images.sized(signed, 256, null))
        assertEquals("$base/logo.png", Images.sized("$base/logo.png", 256, base))
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
