package so.drafft.core.data.media

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

// Ports DrafftTests/RenditionsTests.swift.
class RenditionsTest {
    /** A deck card on a 6.3" iPhone: 361 × 560 pt at 3x. */
    private val card = PixelSize(1_083, 1_680)

    @Test
    fun portraitPhotoFillsTheCardFromTheSmallestCoveringCopy() {
        // 4:5: the card's height decides, 1 344 px wide, the 1 440 copy.
        val needed = Renditions.neededWidth(card, aspect = 0.8)
        assertEquals(1_344.0, needed, 0.5)
        assertEquals(1_440, Renditions.width(covering = needed))
    }

    @Test
    fun landscapePhotoOnATallCardNeedsTheOriginal() {
        val needed = Renditions.neededWidth(card, aspect = 4.0 / 3.0)
        assertNull(Renditions.width(covering = needed))
        assertEquals(listOf<Int?>(null), Renditions.candidates(covering = needed))
    }

    @Test
    fun unknownProportionsCountAsSquare() {
        assertEquals(1_680.0, Renditions.neededWidth(card, aspect = null))
        assertEquals(1_680.0, Renditions.neededWidth(card, aspect = 0.0))
    }

    @Test
    fun aFivePercentUpscaleSavesAStep() {
        assertEquals(1_080, Renditions.width(covering = 1_120.0))
        assertEquals(1_440, Renditions.width(covering = 1_150.0))
    }

    @Test
    fun largerCopiesStandInBestFirst() {
        assertEquals(listOf(640, 1_080, 1_440, null), Renditions.candidates(covering = 600.0))
        assertEquals(listOf(160, 320, 640, 1_080, 1_440, null), Renditions.candidates(covering = 72.0))
    }

    @Test
    fun previewIsAQuarterOfTheWidth() {
        assertEquals(320, Renditions.previewWidth(covering = 1_344.0))
        assertEquals(160, Renditions.previewWidth(covering = 300.0))
    }

    @Test
    fun decodeSizeIsTheFrameRoundedUp() {
        assertEquals(PixelSize(1_088, 1_728), Renditions.decodeSize(card))
        assertEquals(PixelSize(64, 64), Renditions.decodeSize(PixelSize(10, 0)))
    }
}
