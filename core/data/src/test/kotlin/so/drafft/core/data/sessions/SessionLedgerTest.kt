package so.drafft.core.data.sessions

import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import so.drafft.core.data.sessions.SessionLedger.Change
import so.drafft.core.data.sessions.SessionRecord.Status

class SessionLedgerTest {
    private val match = UUID.randomUUID()
    private val me = UUID.randomUUID()
    private val now = Instant.ofEpochSecond(1_800_000_000)

    private fun row(
        id: UUID = UUID.randomUUID(),
        status: Status = Status.PENDING,
        options: List<Instant>? = null,
        chosen: Instant? = null,
        version: Long = 0,
    ) = SessionRecord(
        id = id, matchID = match, proposerID = me, sportID = "running",
        options = options ?: listOf(now.plusSeconds(86_400), now.plusSeconds(2 * 86_400)),
        chosenAt = chosen, status = status, updatedAt = now.plusSeconds(version),
    )

    // Decoding

    @Test
    fun decodesAnUpcomingSessionWithItsPartner() {
        val json = """
        [{"id":"8f7c1c2e-1b1a-4c55-9a55-0d7c5e0b8a11","match_id":"2b8e3f4a-5c6d-4e7f-8a9b-0c1d2e3f4a5b",
          "proposer_id":"3c9f4a5b-6d7e-4f8a-9b0c-1d2e3f4a5b6c","sport_id":"trail",
          "options":["2026-10-25T07:30:00+01:00","2026-10-26T08:00:00.123456+00:00"],
          "chosen_at":null,"title":"","note":"","tags":["Easy pace"],"discovery":"iTeach","status":"pending",
          "replaces_id":null,"created_at":"2026-09-28T10:00:00.5+00:00","updated_at":"2026-09-28T10:00:00.654321+00:00",
          "with":{"id":"4d0a5b6c-7e8f-4a9b-0c1d-2e3f4a5b6c7d","name":"Maya",
                  "photo":{"key":"u/x/1.jpg","url":"https://media.example/u/x/1.jpg?sig=1"}}}]
        """
        val r = assertNotNull(SessionRecord.decodeList(json).firstOrNull())
        assertEquals("trail", r.sportID)
        assertEquals(Status.PENDING, r.status)
        assertEquals("iTeach", r.discovery)
        assertEquals(listOf("Easy pace"), r.tags)
        assertEquals(ServerDate.parse("2026-10-25T06:30:00Z"), r.options.first())
        val last = r.options.last().toEpochMilli() / 1000.0
        val expected = ServerDate.parse("2026-10-26T08:00:00Z").epochSecond + 0.123
        assertTrue(abs(last - expected) <= 0.001)
        assertEquals("Maya", r.partner?.name)
        assertEquals("https://media.example/u/x/1.jpg?sig=1", r.partner?.photo)
    }

    @Test
    fun decodesAPlainRowWithoutPartner() {
        val json = """
        {"id":"8f7c1c2e-1b1a-4c55-9a55-0d7c5e0b8a11","match_id":"2b8e3f4a-5c6d-4e7f-8a9b-0c1d2e3f4a5b",
         "proposer_id":"3c9f4a5b-6d7e-4f8a-9b0c-1d2e3f4a5b6c","sport_id":"padel","options":["2026-10-25T07:30:00+00:00"],
         "chosen_at":"2026-10-25T07:30:00+00:00","title":"Doubles?","note":"","tags":[],"discovery":null,
         "status":"accepted","replaces_id":null,"updated_at":"2026-09-28T10:00:00+00:00"}
        """
        val r = SessionRecord.decode(json)
        assertEquals(Status.ACCEPTED, r.status)
        assertEquals(r.options.first(), r.chosenAt)
        assertNull(r.partner)
        assertNull(r.discovery)
    }

    @Test
    fun sentTimesRoundTrip() {
        val date = Instant.ofEpochSecond(1_790_000_000)
        assertEquals(date, ServerDate.parse(ServerDate.string(date)))
    }

    // Merging

    @Test
    fun newerRowWinsAndOlderReplyIsIgnored() {
        val ledger = SessionLedger()
        val id = UUID.randomUUID()
        ledger.merge(listOf(row(id, status = Status.ACCEPTED, chosen = now.plusSeconds(86_400), version = 10)))
        ledger.merge(listOf(row(id, status = Status.PENDING, version = 5)))
        assertEquals(Status.ACCEPTED, ledger.visible[id]?.status, "an older read never overwrites a newer one")
        ledger.merge(listOf(row(id, status = Status.CANCELLED, version = 20)))
        assertEquals(Status.CANCELLED, ledger.visible[id]?.status)
    }

    @Test
    fun partnerIsKeptWhenARowComesWithoutIt() {
        val ledger = SessionLedger()
        val id = UUID.randomUUID()
        val first = row(id).copy(partner = SessionRecord.Partner(UUID.randomUUID(), "Maya", null))
        ledger.merge(listOf(first))
        ledger.merge(listOf(row(id, status = Status.ACCEPTED, chosen = first.options[0], version = 1)))
        assertEquals("Maya", ledger.visible[id]?.partner?.name)
    }

    // Optimistic changes

    @Test
    fun acceptShowsAtOnceThenSettles() {
        val ledger = SessionLedger()
        val id = UUID.randomUUID()
        val base = row(id)
        ledger.merge(listOf(base))
        val token = ledger.begin(Change.Respond(id, accept = true, pick = base.options[1]))
        assertEquals(Status.ACCEPTED, ledger.visible[id]?.status)
        assertEquals(base.options[1], ledger.visible[id]?.chosenAt)
        assertTrue(ledger.isBusy(id))
        val server = base.copy(status = Status.ACCEPTED, chosenAt = base.options[1], updatedAt = now.plusSeconds(1))
        ledger.settle(token, with = listOf(server))
        assertFalse(ledger.isBusy(id))
        assertEquals(server, ledger.visible[id])
    }

    @Test
    fun refusedChangeIsRolledBackToTheLatestServerState() {
        val ledger = SessionLedger()
        val id = UUID.randomUUID()
        ledger.merge(listOf(row(id)))
        val token = ledger.begin(Change.Cancel(id))
        assertEquals(Status.CANCELLED, ledger.visible[id]?.status)
        // Meanwhile Realtime brought the other person's answer.
        ledger.merge(listOf(row(id, status = Status.DECLINED, version = 3)))
        ledger.fail(token)
        assertEquals(Status.DECLINED, ledger.visible[id]?.status, "rolled back to the server, not to a stale snapshot")
    }

    @Test
    fun counterMarksTheOldOneAndAddsTheNew() {
        val ledger = SessionLedger()
        val old = UUID.randomUUID()
        val new = UUID.randomUUID()
        ledger.merge(listOf(row(old)))
        val token = ledger.begin(Change.Counter(old, row(new)))
        assertEquals(Status.COUNTERED, ledger.visible[old]?.status)
        assertEquals(Status.PENDING, ledger.visible[new]?.status)
        ledger.fail(token)
        assertEquals(Status.PENDING, ledger.visible[old]?.status)
        assertNull(ledger.visible[new])
    }

    @Test
    fun aChangeOnAClosedSessionShowsNothing() {
        val ledger = SessionLedger()
        val id = UUID.randomUUID()
        ledger.merge(listOf(row(id, status = Status.DECLINED)))
        ledger.begin(Change.Cancel(id))
        ledger.begin(Change.Respond(id, accept = true, pick = null))
        assertEquals(Status.DECLINED, ledger.visible[id]?.status)
    }

    @Test
    fun proposalIsReplacedByTheServerRow() {
        val ledger = SessionLedger()
        val local = row()
        val token = ledger.begin(Change.Propose(local))
        assertEquals(listOf(local.id), ledger.upcoming(now).map { it.id })
        val saved = row(version = 1)
        ledger.settle(token, with = listOf(saved))
        assertNull(ledger.visible[local.id])
        assertEquals(saved, ledger.visible[saved.id])
    }

    @Test
    fun copyIsIndependent() {
        val ledger = SessionLedger()
        val a = row()
        ledger.merge(listOf(a))
        val next = ledger.copy()
        next.remove(setOf(a.id))
        assertNotNull(ledger.visible[a.id])
        assertNull(next.visible[a.id])
    }

    // Upcoming

    @Test
    fun upcomingIsOpenAndNotPassedSoonestFirst() {
        val ledger = SessionLedger()
        val later = row(options = listOf(now.plusSeconds(3 * 86_400)))
        val sooner = row(status = Status.ACCEPTED, options = listOf(now.plusSeconds(3600)), chosen = now.plusSeconds(3600))
        val justStarted = row(status = Status.ACCEPTED, options = listOf(now.minusSeconds(3600)), chosen = now.minusSeconds(3600))
        val passed = row(status = Status.ACCEPTED, options = listOf(now.minusSeconds(3 * 3600)), chosen = now.minusSeconds(3 * 3600))
        val cancelled = row(status = Status.CANCELLED)
        ledger.merge(listOf(later, sooner, justStarted, passed, cancelled))
        assertEquals(listOf(justStarted.id, sooner.id, later.id), ledger.upcoming(now).map { it.id })
    }

    @Test
    fun removeForgetsGoneSessions() {
        val ledger = SessionLedger()
        val a = row()
        val b = row()
        ledger.merge(listOf(a, b))
        ledger.remove(setOf(a.id))
        assertNull(ledger.visible[a.id])
        assertNotNull(ledger.visible[b.id])
        ledger.reset()
        assertTrue(ledger.visible.isEmpty())
    }
}
