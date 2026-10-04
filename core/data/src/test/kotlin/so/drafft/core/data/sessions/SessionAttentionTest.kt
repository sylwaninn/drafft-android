package so.drafft.core.data.sessions

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import so.drafft.core.data.platform.InMemoryKeyValueStore
import so.drafft.core.data.sessions.SessionRecord.Status

class SessionAttentionTest {
    private val me = UUID.randomUUID()
    private val now = Instant.ofEpochSecond(1_800_000_000)

    private fun row(status: Status, updatedAt: Instant, id: UUID = UUID.randomUUID()) = SessionRecord(
        id = id, matchID = UUID.randomUUID(), proposerID = UUID.randomUUID(), sportID = "running",
        options = listOf(now.plusSeconds(86_400)), status = status, updatedAt = updatedAt,
    )

    @Test
    fun nothingCountsBeforeTheAccountIsKnown() {
        val attention = SessionAttention(InMemoryKeyValueStore())
        assertFalse(attention.needsAttention(row(Status.PENDING, now), mine = false, now = now))
    }

    @Test
    fun anInviteToAnswerNeedsThePersonUntilItIsAnswered() {
        val attention = SessionAttention(InMemoryKeyValueStore()).apply { load(me, now) }
        assertTrue(attention.needsAttention(row(Status.PENDING, now), mine = false, now = now))
        assertFalse(attention.needsAttention(row(Status.PENDING, now), mine = true, now = now))
        assertFalse(attention.needsAttention(row(Status.COUNTERED, now.plusSeconds(10)), mine = false, now = now))
    }

    @Test
    fun aChangeBeforeTheBaselineIsNeverNews() {
        val attention = SessionAttention(InMemoryKeyValueStore()).apply { load(me, now) }
        assertFalse(attention.needsAttention(row(Status.ACCEPTED, now.minusSeconds(60)), mine = true, now = now))
        assertTrue(attention.needsAttention(row(Status.ACCEPTED, now.plusSeconds(60)), mine = true, now = now))
    }

    @Test
    fun openingTheSessionClearsItUntilItChangesAgain() {
        val attention = SessionAttention(InMemoryKeyValueStore()).apply { load(me, now) }
        val id = UUID.randomUUID()
        val confirmed = row(Status.ACCEPTED, now.plusSeconds(60), id)
        attention.markSeen(confirmed, now = now.plusSeconds(61))
        assertFalse(attention.needsAttention(confirmed, mine = true, now = now))
        assertTrue(attention.needsAttention(row(Status.CANCELLED, now.plusSeconds(120), id), mine = true, now = now))
    }

    @Test
    fun seenAndSafetyAreKeptPerAccount() {
        val defaults = InMemoryKeyValueStore()
        val id = UUID.randomUUID()
        val confirmed = row(Status.ACCEPTED, now.plusSeconds(60), id)
        SessionAttention(defaults).apply {
            load(me, now)
            markSeen(confirmed, now = now.plusSeconds(61))
            markSafetyShown(id)
        }

        val again = SessionAttention(defaults).apply { load(me, now.plusSeconds(3_600)) }
        assertFalse(again.needsAttention(confirmed, mine = true, now = now))
        assertTrue(again.hasShownSafety(id))

        val other = SessionAttention(defaults).apply { load(UUID.randomUUID(), now) }
        assertFalse(other.hasShownSafety(id))
    }
}
