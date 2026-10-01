package so.drafft.core.data.backend

import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

// Ports DrafftTests/ServerMessageTests.swift (the codes added with the offline-aware text).
class ServerMessageTest {
    @Test
    fun sessionAndTargetCodesHaveWords() {
        // An edge function's 401 and a swipe or block on a profile that can't be targeted.
        assertNotNull(ServerMessage.text(forCode = "unauthenticated"))
        assertEquals(ServerMessage.text(forCode = "not_eligible"), ServerMessage.text(forCode = "invalid_target"))
    }

    @Test
    fun connectionAdviceOnlyWhenOffline() {
        val offline = "offline"
        assertEquals(offline, ServerMessage.text(IOException("no route"), offline = offline))
        // The server answered: no connection advice, its words or the generic line.
        assertEquals(ServerMessage.generic, ServerMessage.text(Backend.BackendError.Http(500, "boom"), offline = offline))
        assertEquals(
            ServerMessage.text(forCode = "no_boost"),
            ServerMessage.text(Backend.BackendError.Http(400, "no_boost"), offline = offline),
        )
        assertTrue(ServerMessage.isSignedOut(Backend.BackendError.Http(401, "unauthenticated")))
        assertFalse(ServerMessage.isOffline(Backend.BackendError.Http(503, "unavailable")))
    }
}
