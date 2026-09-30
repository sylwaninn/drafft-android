package so.drafft.core.data.notifications

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import so.drafft.core.data.platform.InMemoryKeyValueStore

/** Port of DrafftTests/PushTokenRegistrationTests.swift. */
class PushTokenRegistrationTest {
    private val defaults = InMemoryKeyValueStore()
    private val account = UUID.randomUUID()
    private val now = Instant.ofEpochSecond(1_800_000_000)

    private fun registration() = PushTokenRegistration(defaults)

    @Test
    fun firstTokenIsSent() {
        assertTrue(registration().needsSending("a", account, "production", now))
    }

    @Test
    fun sameTokenIsNotSentAgain() {
        registration().markSent("a", account, "production", now)
        assertFalse(registration().needsSending("a", account, "production", now.plusSeconds(60)))
    }

    @Test
    fun changedTokenAccountOrEnvironmentIsSent() {
        registration().markSent("a", account, "production", now)
        val reg = registration()
        assertTrue(reg.needsSending("b", account, "production", now))
        assertTrue(reg.needsSending("a", UUID.randomUUID(), "production", now))
        assertTrue(reg.needsSending("a", account, "sandbox", now))
    }

    @Test
    fun sentAgainAfterADay() {
        registration().markSent("a", account, "production", now)
        assertTrue(
            registration().needsSending("a", account, "production", now.plusSeconds(PushTokenRegistration.REFRESH_INTERVAL_SECONDS)),
        )
    }

    @Test
    fun clockMovedBackSendsAgain() {
        registration().markSent("a", account, "production", now)
        assertTrue(registration().needsSending("a", account, "production", now.minusSeconds(60)))
    }

    @Test
    fun forgetSendsAgain() {
        val reg = registration()
        reg.markSent("a", account, "production", now)
        reg.forget()
        assertTrue(reg.needsSending("a", account, "production", now))
    }
}
