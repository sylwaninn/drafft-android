package so.drafft.core.data.media

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

// NetworkQuality (Drafft/Services/Media/NetworkQuality.swift): limited by the path or by slow photos.
class NetworkQualityTest {
    @Test
    fun meteredOrDataSaverIsLimited() {
        val quality = NetworkQuality()
        assertFalse(quality.isLimited)
        quality.path(expensive = true, constrained = false)
        assertTrue(quality.isLimited)
        quality.path(expensive = false, constrained = true)
        assertTrue(quality.isLimited)
        quality.path(expensive = false, constrained = false)
        assertFalse(quality.isLimited)
    }

    @Test
    fun slowPhotosLimitUntilClearlyFastAgain() {
        val quality = NetworkQuality()
        val changes = mutableListOf<Boolean>()
        quality.onChange { changes += it }
        quality.measured(bytes = 100_000, seconds = 1.0)
        assertTrue(quality.isLimited)
        // 300 kB/s brings the average to 180 kB/s: still slow.
        quality.measured(bytes = 300_000, seconds = 1.0)
        assertTrue(quality.isLimited)
        quality.measured(bytes = 2_000_000, seconds = 1.0)
        assertFalse(quality.isLimited)
        assertEquals(listOf(false, true, false), changes)
    }

    @Test
    fun smallDownloadsSayNothing() {
        val quality = NetworkQuality()
        quality.measured(bytes = 20_000, seconds = 10.0)
        assertFalse(quality.isLimited)
    }
}
