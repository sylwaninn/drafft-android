package so.drafft.core.data.media

import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test

class PhotoDownloadsTest {
    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(5)
    }

    @Test
    fun theHighestPriorityGoesFirst() {
        val queue = PhotoDownloads(limit = 1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        queue.acquire(Images.Priority.NORMAL.ordinal) { false }
        fun start(name: String, priority: Images.Priority) = thread {
            if (queue.acquire(priority.ordinal) { false }) {
                order += name
                queue.release()
            }
        }
        val low = start("low", Images.Priority.LOW)
        waitFor { queue.waitingCount == 1 }
        val high = start("high", Images.Priority.VERY_HIGH)
        waitFor { queue.waitingCount == 2 }
        queue.release()
        low.join(5_000)
        high.join(5_000)
        assertEquals(listOf("high", "low"), order)
    }

    @Test
    fun aPhotoMovingUpTheDeckIsRaisedWhileItWaits() {
        val queue = PhotoDownloads(limit = 1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        queue.acquire(Images.Priority.NORMAL.ordinal) { false }
        fun start(name: String, priority: Images.Priority) = thread {
            if (queue.acquire(priority.ordinal, photo = name) { false }) {
                order += name
                queue.release()
            }
        }
        val window = start("window", Images.Priority.LOW)
        waitFor { queue.waitingCount == 1 }
        val behind = start("behind", Images.Priority.VERY_LOW)
        waitFor { queue.waitingCount == 2 }
        // The card behind is now in play.
        queue.prioritize("behind", Images.Priority.HIGH.ordinal)
        queue.release()
        window.join(5_000)
        behind.join(5_000)
        assertEquals(listOf("behind", "window"), order)
    }

    private fun raceTwo(first: Pair<String, Images.Priority>, second: Pair<String, Images.Priority>, between: (PhotoDownloads) -> Unit): List<String> {
        val queue = PhotoDownloads(limit = 1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        queue.acquire(Images.Priority.NORMAL.ordinal) { false }
        fun start(name: String, priority: Images.Priority) = thread {
            if (queue.acquire(priority.ordinal, photo = name) { false }) {
                order += name
                queue.release()
            }
        }
        val a = start(first.first, first.second)
        waitFor { queue.waitingCount == 1 }
        val b = start(second.first, second.second)
        waitFor { queue.waitingCount == 2 }
        between(queue)
        queue.release()
        a.join(5_000)
        b.join(5_000)
        return order
    }

    @Test
    fun aRaiseNeverLowersAPhoto() {
        // A long look's fetch, after the tap opened the profile.
        val order = raceTwo("opened" to Images.Priority.HIGH, "window" to Images.Priority.NORMAL) {
            it.raise("opened", Images.Priority.LOW.ordinal)
        }
        assertEquals(listOf("opened", "window"), order)
    }

    @Test
    fun aRaiseLiftsAPhotoWaitingBehind() {
        val order = raceTwo("window" to Images.Priority.NORMAL, "opened" to Images.Priority.LOW) {
            it.raise("opened", Images.Priority.HIGH.ordinal)
        }
        assertEquals(listOf("opened", "window"), order)
    }

    @Test
    fun aCancelledDownloadLeavesTheQueue() {
        val queue = PhotoDownloads(limit = 1)
        queue.acquire(0) { false }
        assertFalse(queue.acquire(0) { true })
        assertEquals(0, queue.waitingCount)
    }
}
