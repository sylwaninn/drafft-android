package so.drafft.core.data

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import so.drafft.core.data.DiscoveryFreshness.Moment
import so.drafft.core.data.DiscoveryFreshness.Part

// Ports DrafftTests/DiscoveryFreshnessTests.swift.
class DiscoveryFreshnessTest {
    @Test
    fun neverReadIsAlwaysDue() {
        val fresh = DiscoveryFreshness()
        assertTrue(fresh.isDue(Part.DECK, Moment.TAB_SHOWN, now = 0.0))
        assertTrue(fresh.isDue(Part.LIKES, Moment.FOREGROUND, now = 0.0))
    }

    @Test
    fun aQuickTripAwayReadsNothingALongOneReadsAgain() {
        val fresh = DiscoveryFreshness()
        val read = fresh.start(Part.DECK, now = 100.0)
        assertTrue(fresh.finish(read, ok = true))
        assertFalse(fresh.isDue(Part.DECK, Moment.FOREGROUND, now = 110.0))
        assertTrue(fresh.isDue(Part.DECK, Moment.FOREGROUND, now = 130.0))
        assertFalse(fresh.isDue(Part.DECK, Moment.TAB_SHOWN, now = 150.0))
        assertTrue(fresh.isDue(Part.DECK, Moment.TAB_SHOWN, now = 160.0))
    }

    @Test
    fun enteringAndReconnectingAlwaysRead() {
        val fresh = DiscoveryFreshness()
        val read = fresh.start(Part.MATCHES, now = 100.0)
        assertTrue(fresh.finish(read, ok = true))
        assertTrue(fresh.isDue(Part.MATCHES, Moment.ENTERED, now = 100.0))
        assertTrue(fresh.isDue(Part.MATCHES, Moment.RECONNECTED, now = 100.0))
    }

    @Test
    fun aReadOnItsWayCountsUntilItLooksLost() {
        val fresh = DiscoveryFreshness()
        fresh.start(Part.LIKES, now = 100.0)
        assertFalse(fresh.isDue(Part.LIKES, Moment.FOREGROUND, now = 105.0))
        assertTrue(fresh.isDue(Part.LIKES, Moment.FOREGROUND, now = 100.0 + DiscoveryFreshness.LOST_AFTER))
    }

    @Test
    fun aFailedReadLeavesThePartDue() {
        val fresh = DiscoveryFreshness()
        val read = fresh.start(Part.LIKES_LEFT, now = 100.0)
        assertFalse(fresh.finish(read, ok = false))
        assertTrue(fresh.isDue(Part.LIKES_LEFT, Moment.FOREGROUND, now = 101.0))
    }

    @Test
    fun aFailedReadKeepsTheLastGoodOne() {
        val fresh = DiscoveryFreshness()
        assertTrue(fresh.finish(fresh.start(Part.MATCHES, now = 100.0), ok = true))
        assertFalse(fresh.finish(fresh.start(Part.MATCHES, now = 120.0), ok = false))
        // Still as fresh as the read that worked.
        assertFalse(fresh.isDue(Part.MATCHES, Moment.FOREGROUND, now = 125.0))
        assertTrue(fresh.isDue(Part.MATCHES, Moment.FOREGROUND, now = 130.0))
    }

    @Test
    fun anOlderAnswerLandingLastIsDropped() {
        val fresh = DiscoveryFreshness()
        val older = fresh.start(Part.MATCHES, now = 100.0)
        val newer = fresh.start(Part.MATCHES, now = 101.0)
        assertTrue(fresh.finish(newer, ok = true))
        assertFalse(fresh.finish(older, ok = true))
        // Fresh as of the newer read.
        assertFalse(fresh.isDue(Part.MATCHES, Moment.FOREGROUND, now = 130.0))
        assertTrue(fresh.isDue(Part.MATCHES, Moment.FOREGROUND, now = 131.0))
    }

    @Test
    fun anOlderAnswerLandingFirstStillShows() {
        val fresh = DiscoveryFreshness()
        val older = fresh.start(Part.LIKES, now = 100.0)
        val newer = fresh.start(Part.LIKES, now = 101.0)
        assertTrue(fresh.finish(older, ok = true))
        assertTrue(fresh.finish(newer, ok = true))
    }

    @Test
    fun anEmptyDeckIsReadAgainWhateverItsAge() {
        val fresh = DiscoveryFreshness()
        val read = fresh.start(Part.DECK, now = 100.0)
        assertTrue(fresh.finish(read, ok = true))
        assertTrue(fresh.isDue(Part.DECK, Moment.FOREGROUND, now = 101.0, empty = true))
        assertTrue(fresh.isDue(Part.DECK, Moment.TAB_SHOWN, now = 101.0, empty = true))
        // With cards on screen, the usual wait.
        assertFalse(fresh.isDue(Part.DECK, Moment.FOREGROUND, now = 101.0))
    }

    @Test
    fun anEmptyDeckWaitsForTheReadOnItsWay() {
        val fresh = DiscoveryFreshness()
        fresh.start(Part.DECK, now = 100.0)
        assertFalse(fresh.isDue(Part.DECK, Moment.FOREGROUND, now = 102.0, empty = true))
        assertFalse(fresh.isDue(Part.DECK, Moment.TAB_SHOWN, now = 102.0, empty = true))
        // Lost: read again.
        assertTrue(fresh.isDue(Part.DECK, Moment.FOREGROUND, now = 100.0 + DiscoveryFreshness.LOST_AFTER, empty = true))
        // The channel rejoined: read again even so (events were maybe missed).
        assertTrue(fresh.isDue(Part.DECK, Moment.RECONNECTED, now = 102.0, empty = true))
    }

    @Test
    fun partsAgeOnTheirOwn() {
        val fresh = DiscoveryFreshness()
        val likes = fresh.start(Part.LIKES, now = 100.0)
        assertTrue(fresh.finish(likes, ok = true))
        assertFalse(fresh.isDue(Part.LIKES, Moment.FOREGROUND, now = 110.0))
        assertTrue(fresh.isDue(Part.DECK, Moment.FOREGROUND, now = 110.0))
    }
}
