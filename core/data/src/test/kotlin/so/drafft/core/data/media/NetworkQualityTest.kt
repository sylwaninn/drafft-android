package so.drafft.core.data.media

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

// NetworkQuality (Drafft/Services/Media/NetworkQuality.swift): slow by Data Saver or by slow photos,
// limited when slow or not measured yet on this network.
class NetworkQualityTest {
    /** A download of [bytes] in [seconds], read to its end. */
    private fun NetworkQuality.finished(bytes: Long, seconds: Double) {
        val download = download(now = 0.0)
        download.received(bytes, now = seconds)
        download.ended(failed = false, now = seconds)
    }

    @Test
    fun unmeasuredIsLimitedButNotSlow() {
        val quality = NetworkQuality()
        assertTrue(quality.isLimited)
        assertFalse(quality.isSlow)
        quality.finished(bytes = 1_000_000, seconds = 0.2)
        assertFalse(quality.isLimited)
    }

    @Test
    fun dataSaverIsSlow() {
        val quality = NetworkQuality()
        quality.path(network = "wifi", constrained = false)
        quality.finished(bytes = 1_000_000, seconds = 0.2)
        quality.path(network = "wifi", constrained = true)
        assertTrue(quality.isSlow)
        assertTrue(quality.isLimited)
        quality.path(network = "wifi", constrained = false)
        assertFalse(quality.isLimited)
    }

    @Test
    fun anotherNetworkIsMeasuredAgain() {
        val quality = NetworkQuality()
        quality.path(network = "wifi", constrained = false)
        quality.finished(bytes = 100_000, seconds = 1.0)
        assertTrue(quality.isSlow)
        // The same network again (its capabilities changed): what was measured stays.
        quality.path(network = "wifi", constrained = false)
        assertTrue(quality.isSlow)
        quality.path(network = "cellular", constrained = false)
        assertFalse(quality.isSlow)
        assertTrue(quality.isLimited)
    }

    @Test
    fun slowPhotosLimitUntilClearlyFastAgain() {
        val quality = NetworkQuality()
        val changes = mutableListOf<Boolean>()
        quality.onChange { changes += it }
        quality.finished(bytes = 100_000, seconds = 1.0)
        assertTrue(quality.isSlow)
        // 300 kB/s brings the average to 180 kB/s: still slow.
        quality.finished(bytes = 300_000, seconds = 1.0)
        assertTrue(quality.isSlow)
        quality.finished(bytes = 1_000_000, seconds = 0.5)
        assertFalse(quality.isSlow)
        assertFalse(quality.isLimited)
        // Limited from the start (unmeasured), then measured slow (no change), then fast.
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun smallQuickDownloadsSayNothing() {
        val quality = NetworkQuality()
        quality.finished(bytes = 20_000, seconds = 0.9)
        assertFalse(quality.isSlow)
        assertTrue(quality.isLimited)
    }

    @Test
    fun aSecondInIsEnoughToTell() {
        val quality = NetworkQuality()
        val download = quality.download(now = 0.0)
        download.received(30_000, now = 0.5)
        assertFalse(quality.isSlow)
        download.received(20_000, now = 1.2)
        assertTrue(quality.isSlow)
    }

    @Test
    fun aDownloadTooSlowToFinishStillCounts() {
        val quality = NetworkQuality()
        quality.download(now = 0.0).ended(failed = true, now = 3.0)
        assertTrue(quality.isSlow)
    }

    @Test
    fun aDownloadCountsOnce() {
        val quality = NetworkQuality()
        val download = quality.download(now = 0.0)
        download.received(50_000, now = 1.0)
        assertTrue(quality.isSlow)
        // Fast afterwards, but it was recorded already.
        download.received(10_000_000, now = 1.1)
        download.ended(failed = false, now = 1.1)
        assertTrue(quality.isSlow)
    }
}
