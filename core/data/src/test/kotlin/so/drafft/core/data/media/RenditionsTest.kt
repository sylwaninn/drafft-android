package so.drafft.core.data.media

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class RenditionsTest {
    /** A deck card on a 6.3" phone: 361 × 560 dp at 3x density. */
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
    fun everydayPhotosStopAt1080AndAnOpenProfileGoesFurther() {
        assertEquals(1_080.0, Renditions.asked(1_344.0, detail = false))
        assertEquals(1_344.0, Renditions.asked(1_344.0, detail = true))
        assertEquals(600.0, Renditions.asked(600.0, detail = false))
        assertEquals(1_080, Renditions.width(covering = Renditions.asked(1_344.0, detail = false)))
    }

    @Test
    fun aLimitedLineAsksAStepLighter() {
        assertEquals(1_080, Renditions.width(covering = 1_344.0 * Renditions.limitedShare))
    }

    @Test
    fun previewIsAQuarterOfTheWidth() {
        assertEquals(320, Renditions.previewWidth(covering = 1_344.0))
        assertEquals(160, Renditions.previewWidth(covering = 300.0))
    }

    @Test
    fun decodeSizeKeepsTheFramesProportions() {
        val sharp = Renditions.decodeSize(card)
        assertEquals(PixelSize(1_088, 1_688), sharp)
        // The small copy, a third of the pixels: the same proportions, so it's cropped like the sharp one.
        val small = Renditions.decodeSize(PixelSize(card.width / 3, card.height / 3))
        assertEquals(sharp.width / sharp.height, small.width / small.height, 0.002)
        assertEquals(PixelSize(64, 64), Renditions.decodeSize(PixelSize(10, 0)))
    }

    /** The gallery, 393 × 440 dp at 3x density, for a 4:5 photo: 1 179 px needed, the 1 440 copy. */
    private val gallery = Renditions.neededWidth(PixelSize(1_179, 1_320), aspect = 0.8)

    @Test
    fun theCardsCopyStandsInForAnOpenProfilesWiderOne() {
        // The card left its 1 080 on this phone.
        assertEquals(1_080, Renditions.standIn(gallery) { it == 1_080 })
        // The widest one here wins.
        assertEquals(1_080, Renditions.standIn(gallery) { it == 640 || it == 1_080 })
        // 640 is just enough.
        assertEquals(640, Renditions.standIn(gallery) { it == 640 })
    }

    @Test
    fun noStandInWhenACoveringCopyIsHere() {
        assertNull(Renditions.standIn(gallery) { it == 1_440 })
        assertNull(Renditions.standIn(gallery) { it == 1_080 || it == 1_440 })
        // The original covers everything.
        assertNull(Renditions.standIn(gallery) { it == null || it == 1_080 })
        // A larger copy than the covering one.
        assertNull(Renditions.standIn(1_000.0) { it == 1_440 })
    }

    @Test
    fun noStandInWhenNothingWorthShowingIsHere() {
        // A 320 copy is too soft to stand in.
        assertNull(Renditions.standIn(gallery) { it == 320 })
        assertNull(Renditions.standIn(gallery) { false })
        // A small frame: its covering copy is the 640 or narrower.
        assertNull(Renditions.standIn(600.0) { it == 320 })
    }

    @Test
    fun theFivePercentToleranceDecidesBetweenCoveringAndStandingIn() {
        assertNull(Renditions.standIn(1_136.0) { it == 1_080 })
        assertEquals(1_080, Renditions.standIn(1_137.0) { it == 1_080 })
        assertNull(Renditions.standIn(1_515.0) { it == 1_440 })
        assertEquals(1_440, Renditions.standIn(1_516.0) { it == 1_440 })
    }

    @Test
    fun anyLadderCopyStandsInForTheOriginal() {
        assertEquals(1_440, Renditions.standIn(1_800.0) { it == 1_440 })
        assertNull(Renditions.standIn(1_800.0) { it == null || it == 1_440 })
    }

    @Test
    fun onASlowLineTheCardsCopyIsTheGallerysOwn() {
        val slow = gallery * Renditions.limitedShare
        assertEquals(1_080, Renditions.width(slow))
        assertNull(Renditions.standIn(slow) { it == 1_080 })
        assertEquals(640, Renditions.standIn(slow) { it == 640 })
    }

    @Test
    fun aStandInIsNeverACopyTheRequestWouldPick() {
        val widths: List<Int?> = Renditions.ladder + listOf(null)
        for (mask in 0 until (1 shl widths.size)) {
            val here = widths.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            var needed = 1.0
            while (needed <= 2_500.0) {
                val candidates = Renditions.candidates(needed)
                val copy = Renditions.standIn(needed) { it in here }
                if (copy != null) {
                    assertTrue(copy in here)
                    assertTrue(copy >= Renditions.standInMinimum)
                    assertFalse(copy in candidates)
                    assertFalse(candidates.any { it in here })
                }
                needed += 7.0
            }
        }
    }
}
