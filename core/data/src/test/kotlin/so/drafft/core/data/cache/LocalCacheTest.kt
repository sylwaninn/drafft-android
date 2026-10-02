package so.drafft.core.data.cache

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test

class LocalCacheTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File(Files.createTempDirectory("LocalCacheTests").toFile(), UUID.randomUUID().toString())
    }

    @After
    fun tearDown() {
        directory.parentFile.deleteRecursively()
    }

    @Test
    fun savedPayloadIsReadBack() {
        val cache = LocalCache(UUID.randomUUID(), directory)
        val data = """[{"name":"Alex"}]""".encodeToByteArray()
        val date = Instant.ofEpochSecond(1_800_000_000)
        cache.save(data, LocalCache.Kind.PROFILE, at = date)
        assertEquals(LocalCache.Entry(data, date), cache.entry(LocalCache.Kind.PROFILE))
        assertNull(cache.entry(LocalCache.Kind.MATCHES))
    }

    @Test
    fun newerPayloadReplacesTheOldOne() {
        val cache = LocalCache(UUID.randomUUID(), directory)
        cache.save("old".encodeToByteArray(), LocalCache.Kind.DECK)
        cache.save("new".encodeToByteArray(), LocalCache.Kind.DECK)
        assertContentEquals("new".encodeToByteArray(), cache.entry(LocalCache.Kind.DECK)?.data)
    }

    @Test
    fun survivesReopening() {
        val account = UUID.randomUUID()
        LocalCache(account, directory).save("kept".encodeToByteArray(), LocalCache.Kind.SESSIONS)
        assertContentEquals("kept".encodeToByteArray(), LocalCache(account, directory).entry(LocalCache.Kind.SESSIONS)?.data)
    }

    /** Two accounts on one phone never read each other's cache. */
    @Test
    fun accountsAreKeptApart() {
        val alex = LocalCache(UUID.randomUUID(), directory)
        val sam = LocalCache(UUID.randomUUID(), directory)
        alex.save("alex".encodeToByteArray(), LocalCache.Kind.PROFILE)
        assertNull(sam.entry(LocalCache.Kind.PROFILE))
        assertNotEquals(LocalCache.fileURL(alex.account, directory), LocalCache.fileURL(sam.account, directory))
    }

    /** The key doesn't depend on how the id was written. */
    @Test
    fun keyIgnoresCase() {
        val id = UUID.randomUUID()
        val upper = UUID.fromString(id.toString().uppercase())
        val lower = UUID.fromString(id.toString().lowercase())
        assertEquals(LocalCache.fileURL(upper, directory), LocalCache.fileURL(lower, directory))
    }

    /** Sign-out and account deletion leave nothing of the account behind, and only that account. */
    @Test
    fun eraseRemovesOnlyThatAccount() {
        val gone = UUID.randomUUID()
        val kept = UUID.randomUUID()
        LocalCache(gone, directory).save("x".encodeToByteArray(), LocalCache.Kind.PROFILE)
        LocalCache(kept, directory).save("y".encodeToByteArray(), LocalCache.Kind.PROFILE)
        LocalCache.erase(gone, directory)
        assertFalse(LocalCache.fileURL(gone, directory).exists())
        assertNull(LocalCache(gone, directory).entry(LocalCache.Kind.PROFILE))
        assertContentEquals("y".encodeToByteArray(), LocalCache(kept, directory).entry(LocalCache.Kind.PROFILE)?.data)
    }
}
