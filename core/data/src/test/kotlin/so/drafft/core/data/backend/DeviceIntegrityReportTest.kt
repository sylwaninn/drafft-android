package so.drafft.core.data.backend

import com.sun.net.httpserver.HttpServer
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import so.drafft.core.data.platform.DeviceIntegrityProvider
import so.drafft.core.data.platform.InMemoryKeyValueStore

// DeviceIntegrity.report: what it sends to `device-check`, and when it sends nothing. A real Backend, with a
// saved session, talks to a loopback server that records the requests.
class DeviceIntegrityReportTest {
    private class Request(val method: String, val path: String, val authorization: String?, val body: String)

    private class RecordingProvider(private val answer: () -> String?) : DeviceIntegrityProvider {
        val hashes = CopyOnWriteArrayList<String>()

        override suspend fun token(requestHash: String): String? {
            hashes += requestHash
            return answer()
        }
    }

    private val account = UUID.fromString("0b6f1f5e-8f0c-4a5e-9d3b-2f8a1c7e4d10")
    private val accessToken = "access-token-of-the-test"

    /** Runs [block] with a Backend signed in as [account] (or signed out) and the requests the server saw. */
    private fun withBackend(
        signedIn: Boolean = true,
        status: Int = 204,
        block: suspend (Backend, List<Request>) -> Unit,
    ) = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            requests += Request(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestBody.readBytes().decodeToString(),
            )
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        val config = BackendConfig(
            url = "http://127.0.0.1:${server.address.port}", publishableKey = "publishable-key", revenueCatAPIKey = "",
        )
        val store = InMemoryKeyValueStore()
        if (signedIn) {
            val session = UserSession(
                accessToken = accessToken,
                refreshToken = "refresh-token",
                expiresIn = 365L * 24 * 3600,
                tokenType = "bearer",
                user = UserInfo(id = account.toString(), aud = "authenticated"),
            )
            KeyValueSessionManager(store).saveSession(session)
        }
        val client = Backend.createClient(config, store)
        val http = HttpClient(OkHttp)
        try {
            val backend = Backend(config, client, http)
            // The saved session is loaded in the background: the test starts once Auth has it.
            if (signedIn) withTimeout(10_000) { while (!backend.hasSession()) delay(10) }
            block(backend, requests)
        } finally {
            http.close()
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun aBuildThatDoesNotAttestSendsNothing() = withBackend { backend, requests ->
        // What UnsupportedDeviceIntegrity answers: no token.
        DeviceIntegrity(backend, RecordingProvider { null }).report()
        assertTrue(requests.isEmpty())
    }

    @Test
    fun signedOutAsksForNoTokenAndSendsNothing() = withBackend(signedIn = false) { backend, requests ->
        val provider = RecordingProvider { "token" }
        DeviceIntegrity(backend, provider).report()
        assertTrue(provider.hashes.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun aTokenThatCouldNotBeGivenSendsNothingAndDoesNotThrow() = withBackend { backend, requests ->
        val provider = RecordingProvider { throw IllegalStateException("no Play services") }
        DeviceIntegrity(backend, provider).report()
        assertEquals(1, provider.hashes.size)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun cancellationIsNotSwallowed() = withBackend { backend, requests ->
        val provider = RecordingProvider { throw CancellationException("the launch scope went away") }
        assertFailsWith<CancellationException> { DeviceIntegrity(backend, provider).report() }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun theTokenGoesToDeviceCheckWithItsPlatformAndTheSessionsBearer() = withBackend { backend, requests ->
        DeviceIntegrity(backend, RecordingProvider { "the-play-integrity-token" }).report()
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("/functions/v1/device-check", request.path)
        assertEquals("Bearer $accessToken", request.authorization)
        val body = Json.parseToJsonElement(request.body).jsonObject
        assertEquals("the-play-integrity-token", body["token"]?.jsonPrimitive?.content)
        assertEquals("android", body["platform"]?.jsonPrimitive?.content)
        assertEquals("production", body["environment"]?.jsonPrimitive?.content)
    }

    @Test
    fun theTokenIsBoundToTheSignedInAccount() = withBackend { backend, _ ->
        val provider = RecordingProvider { "token" }
        DeviceIntegrity(backend, provider).report()
        assertEquals(listOf(DeviceIntegrity.requestHash(account)), provider.hashes.toList())
    }

    @Test
    fun aRefusalFromTheServerDoesNotThrow() = withBackend(status = 500) { backend, requests ->
        DeviceIntegrity(backend, RecordingProvider { "token" }).report()
        assertEquals(1, requests.size)
    }
}
