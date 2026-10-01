package so.drafft.core.data.media

import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

class SharedFetchesTest {
    @Test
    fun aSecondAskWaitsForTheFirstDownload() = runTest {
        val fetches = SharedFetches()
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = async {
            fetches.once("photo") {
                events += "download"
                gate.await()
                events += "downloaded"
            }
        }
        yield()
        val second = async { fetches.once("photo") { events += "read from disk" } }
        yield()
        assertEquals(listOf("download"), events)
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("download", "downloaded", "read from disk"), events)
    }

    @Test
    fun otherCopiesDontWait() = runTest {
        val fetches = SharedFetches()
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = async { fetches.once("a") { gate.await(); events += "a" } }
        yield()
        fetches.once("b") { events += "b" }
        gate.complete(Unit)
        first.await()
        assertEquals(listOf("b", "a"), events)
    }
}
