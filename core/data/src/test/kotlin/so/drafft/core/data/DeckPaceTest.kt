package so.drafft.core.data

import kotlin.test.assertEquals
import org.junit.Test

class DeckPaceTest {
    @Test
    fun atFirstTenCardsLeftAskForMore() {
        assertEquals(10, DeckPace().lowWater)
    }

    @Test
    fun fastSwipesOnASlowLineAskEarlier() {
        val pace = DeckPace()
        pace.read(took = 3.0)
        pace.read(took = 3.0)
        for (i in 0 until 20) pace.swiped(at = i * 0.25)
        // Reads average 2.6 s by now, swipes 0.25 s: 11 swipes during a read, plus the photo window.
        assertEquals(17, pace.lowWater)
        pace.read(took = 3.0)
        pace.read(took = 3.0)
        // Slower still: capped short of a whole batch.
        assertEquals(18, pace.lowWater)
    }

    @Test
    fun aPauseIsNotAPace() {
        val pace = DeckPace()
        pace.swiped(at = 0.0)
        pace.swiped(at = 60.0)
        assertEquals(0.8, pace.swipeSeconds)
    }

    @Test
    fun aQuickReadAtAnEasyPaceKeepsTheFloor() {
        val pace = DeckPace()
        pace.read(took = 0.2)
        pace.read(took = 0.2)
        for (i in 0 until 10) pace.swiped(at = i * 2.0)
        assertEquals(10, pace.lowWater)
    }
}
