package so.drafft.core.data.media

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

// NetworkQuality (Drafft/Services/Media/NetworkQuality.swift): limited by the path or by slow photos.
class NetworkQualityTest {
    /** A download of [bytes] in [seconds], read to its end. */
    private fun NetworkQuality.finished(bytes: Long, seconds: Double) {
        val download = download(now = 0.0)
        download.received(bytes, now = seconds)
        download.ended(failed = false, now = seconds)
    }

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
        quality.finished(bytes = 100_000, seconds = 1.0)
        assertTrue(quality.isLimited)
        // 300 kB/s brings the average to 180 kB/s: still slow.
        quality.finished(bytes = 300_000, seconds = 1.0)
        assertTrue(quality.isLimited)
        quality.finished(bytes = 1_000_000, seconds = 0.5)
        assertFalse(quality.isLimited)
        assertEquals(listOf(false, true, false), changes)
    }

    @Test
    fun smallQuickDownloadsSayNothing() {
        val quality = NetworkQuality()
        quality.finished(bytes = 20_000, seconds = 0.9)
        assertFalse(quality.isLimited)
    }

    @Test
    fun aSecondInIsEnoughToTell() {
        val quality = NetworkQuality()
        val download = quality.download(now = 0.0)
        download.received(30_000, now = 0.5)
        assertFalse(quality.isLimited)
        download.received(20_000, now = 1.2)
        assertTrue(quality.isLimited)
    }

    @Test
    fun aDownloadTooSlowToFinishStillCounts() {
        val quality = NetworkQuality()
        quality.download(now = 0.0).ended(failed = true, now = 3.0)
        assertTrue(quality.isLimited)
    }

    @Test
    fun aDownloadCountsOnce() {
        val quality = NetworkQuality()
        val download = quality.download(now = 0.0)
        download.received(50_000, now = 1.0)
        assertTrue(quality.isLimited)
        // Fast afterwards, but it was recorded already.
        download.received(10_000_000, now = 1.1)
        download.ended(failed = false, now = 1.1)
        assertTrue(quality.isLimited)
    }
}
