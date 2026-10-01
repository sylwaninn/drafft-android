package so.drafft.core.data.telemetry

import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.platform.InMemoryKeyValueStore
import so.drafft.core.data.store.Store

class TelemetryTest {
    private class FakeAnalytics : Telemetry.Analytics {
        val calls = mutableListOf<String>()
        val events = mutableListOf<Pair<String, Map<String, Any>>>()
        var sending = true
        override fun capture(name: String, properties: Map<String, Any>) {
            if (sending) events += name to properties
        }
        override fun screen(name: String, properties: Map<String, Any>) { calls += "screen:$name" }
        override fun identify(id: String, properties: Map<String, Any>) { calls += "identify:$id" }
        override fun setPersonProperties(properties: Map<String, Any>) { calls += "person:${properties.keys.sorted()}" }
        override fun register(key: String, value: Any) { calls += "register:$key=$value" }
        override fun reset() { calls += "reset" }
        override fun setEnabled(enabled: Boolean) { sending = enabled }
        override fun flush() = Unit
    }

    private class FakeCrashes : Telemetry.CrashReporter {
        var userID: String? = null
        val captured = mutableListOf<Pair<Throwable, Telemetry.ErrorReport>>()
        val crumbs = mutableListOf<Telemetry.Breadcrumb>()
        val messages = mutableListOf<String>()
        val logs = mutableListOf<String>()
        override fun setUser(id: String?) { userID = id }
        override fun setTag(key: String, value: String?) = Unit
        override fun breadcrumb(crumb: Telemetry.Breadcrumb) { crumbs += crumb }
        override fun capture(error: Throwable, report: Telemetry.ErrorReport) { captured += error to report }
        override fun message(text: String, level: Telemetry.Level, report: Telemetry.ErrorReport) { messages += text }
        override fun log(level: Telemetry.Level, text: String, attributes: Map<String, Any>) { logs += text }
        override fun startSpan(operation: String, description: String): Telemetry.Span = object : Telemetry.Span {
            override fun setData(key: String, value: Any) = Unit
            override fun finish(ok: Boolean) = Unit
        }
    }

    private val analytics = FakeAnalytics()
    private val crashes = FakeCrashes()

    @BeforeTest
    fun install() {
        Telemetry.uninstall()
        Telemetry.analytics = analytics
        Telemetry.crashes = crashes
        PrivacyGuard.strict = false
    }

    @AfterTest
    fun uninstall() {
        Telemetry.uninstall()
        PrivacyGuard.strict = false
    }

    // Identity and consent

    @Test
    fun sentryAlwaysGetsTheAccountPostHogOnlyWithConsent() {
        Telemetry.applyConsent(AnalyticsConsent.UNKNOWN)
        Telemetry.signedIn("4f2c0e0a-0000-4000-8000-000000000001")
        assertEquals("4f2c0e0a-0000-4000-8000-000000000001", crashes.userID)
        assertFalse(analytics.calls.any { it.startsWith("identify") })

        Telemetry.applyConsent(AnalyticsConsent.GRANTED)
        assertEquals(listOf("identify:4f2c0e0a-0000-4000-8000-000000000001"), analytics.calls.filter { it.startsWith("identify") })
    }

    @Test
    fun withdrawingTheConsentStartsANewAnonymousID() {
        Telemetry.applyConsent(AnalyticsConsent.GRANTED)
        Telemetry.signedIn("a")
        Telemetry.applyConsent(AnalyticsConsent.DENIED)
        assertTrue("reset" in analytics.calls)
        Telemetry.track(AnalyticsEvent.LoggedOut())
        assertTrue(analytics.events.isEmpty())
    }

    @Test
    fun signingOutResetsTheAnonymousID() {
        Telemetry.applyConsent(AnalyticsConsent.GRANTED)
        Telemetry.signedIn("a")
        Telemetry.signedIn(null)
        assertEquals(null, crashes.userID)
        assertTrue("reset" in analytics.calls)
        // Another account later is identified afresh.
        Telemetry.signedIn("b")
        assertTrue("identify:b" in analytics.calls)
    }

    @Test
    fun aRefusalStillKeepsBreadcrumbsForCrashReports() {
        Telemetry.applyConsent(AnalyticsConsent.DENIED)
        Telemetry.track(AnalyticsEvent.MatchCreated(AnalyticsEvent.MatchSource.MY_SWIPE))
        assertTrue(analytics.events.isEmpty())
        assertTrue(crashes.crumbs.any { it.message == "match_created" })
    }

    @Test
    fun consentIsKeptOnThePhone() {
        val store = InMemoryKeyValueStore()
        assertEquals(AnalyticsConsent.UNKNOWN, AnalyticsConsent.load(store))
        AnalyticsConsent.save(AnalyticsConsent.GRANTED, store)
        assertEquals(AnalyticsConsent.GRANTED, AnalyticsConsent.load(store))
    }

    // Events

    @Test
    fun eventsCarryEnumsAsCodesAndTheScreen() {
        Telemetry.screen(Screen.DISCOVER)
        Telemetry.track(
            AnalyticsEvent.ProfileSwiped(
                AnalyticsEvent.SwipeAction.SUPER_LIKE, AnalyticsEvent.SwipeSource.DECK, withOpener = true, premium = false,
                likesLeft = null, deckSize = 12,
            ),
        )
        val (name, props) = analytics.events.single()
        assertEquals("profile_swiped", name)
        assertEquals("super_like", props["action"])
        assertEquals("deck", props["source"])
        assertEquals("discover", props["screen"])
        assertFalse("likes_left" in props)
    }

    @Test
    fun theSameScreenTwiceIsOneView() {
        Telemetry.screen(Screen.CHATS)
        Telemetry.screen(Screen.CHATS)
        assertEquals(listOf("screen:chats"), analytics.calls.filter { it.startsWith("screen") })
    }

    @Test
    fun everyEventNameIsSnakeCase() {
        val samples = listOf(
            AnalyticsEvent.SignUpStarted(AnalyticsEvent.AuthMethod.EMAIL), AnalyticsEvent.OnboardingCompleted(3, 2, 1, true, false, true, false, true, 9),
            AnalyticsEvent.PurchaseCompleted(AnalyticsEvent.ProductKind.TEMPO, "so.drafft.app.tempo.monthly:base", "EUR"),
            AnalyticsEvent.ProfileEdited(listOf("photos", "bio")), AnalyticsEvent.UserReported("harassment"),
        )
        for (event in samples) {
            assertTrue(Regex("^[a-z]+(_[a-z]+)*$").matches(event.name), event.name)
            PrivacyGuard.strict = true
            PrivacyGuard.properties(event.name, event.properties)
        }
    }

    @Test
    fun theNewestScreenOnTopIsTheOneOnShow() {
        ScreenTracker.reset()
        ScreenTracker.base(Screen.CHATS)
        val chat = ScreenTracker.enter(Screen.CHAT)
        val profile = ScreenTracker.enter(Screen.PROFILE_DETAIL)
        assertEquals(Screen.PROFILE_DETAIL, ScreenTracker.current)
        // Popping back: the profile leaves, the chat is on show again.
        ScreenTracker.leave(profile)
        assertEquals(Screen.CHAT, ScreenTracker.current)
        ScreenTracker.leave(chat)
        assertEquals(Screen.CHATS, ScreenTracker.current)
        assertEquals(
            listOf("screen:chats", "screen:chat", "screen:profile_detail", "screen:chat", "screen:chats"),
            analytics.calls.filter { it.startsWith("screen") },
        )
        ScreenTracker.reset()
    }

    // Privacy

    @Test
    fun sensitivePropertiesNeverLeave() {
        val out = PrivacyGuard.properties("x", mapOf("gender" to "woman", "interested_in" to "men", "bio" to "hi", "email" to "a@b.co", "kind" to "text"))
        assertEquals(mapOf<String, Any>("kind" to "text"), out)
    }

    @Test
    fun typedTextIsDropped() {
        val out = PrivacyGuard.properties("x", mapOf("reason" to "Hello there!", "product_id" to "so.drafft.app.boost.5", "count" to 3))
        assertEquals(mapOf<String, Any>("product_id" to "so.drafft.app.boost.5", "count" to 3), out)
    }

    @Test
    fun strictModeNamesTheMistake() {
        PrivacyGuard.strict = true
        val error = assertFailsWith<IllegalArgumentException> { PrivacyGuard.properties("profile_edited", mapOf("bio" to "x")) }
        assertTrue(error.message!!.contains("profile_edited.bio"))
    }

    @Test
    fun scrubTakesOutWhatIdentifiesSomeone() {
        val text = "maya@example.com +33 6 12 34 56 78 0612345678 Bearer abc.def-123 " +
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc 48.8566, 2.3522 profile 4f2c0e0a-0000-4000-8000-000000000001 at 1727000000000"
        val scrubbed = PrivacyGuard.scrub(text)
        listOf("maya@", "12 34", "0612345678", "abc.def", "eyJ", "48.8566", "4f2c0e0a").forEach {
            assertFalse(scrubbed.contains(it), "$it in $scrubbed")
        }
        assertTrue(scrubbed.contains("1727000000000"), scrubbed)
    }

    @Test
    fun pathsLoseTheirQuery() {
        assertEquals("rest/v1/profiles", PrivacyGuard.path("rest/v1/profiles?id=eq.4f2c&select=paused"))
    }

    // Errors

    @Test
    fun onlyWhatNeedsAFixIsReported() {
        Telemetry.unexpected(IOException("offline"), "discover")
        Telemetry.unexpected(CancellationException("gone"), "discover")
        Telemetry.unexpected(Backend.BackendError.Http(400, "daily_like_limit"), "discover")
        Telemetry.unexpected(Backend.BackendError.Http(429, "rate"), "discover")
        Telemetry.unexpected(Store.StoreError.Failed(Store.PurchaseProblem.PENDING), "purchase")
        assertTrue(crashes.captured.isEmpty())

        Telemetry.unexpected(Backend.BackendError.Http(503, "upstream"), "discover", "load_deck")
        Telemetry.unexpected(Backend.BackendError.Http(400, "column x does not exist"), "discover")
        Telemetry.unexpected(IllegalStateException("bug"), "chat")
        Telemetry.unexpected(Store.StoreError.Failed(Store.PurchaseProblem.UNCONFIRMED), "purchase")
        assertEquals(listOf("server", "client_contract", "unexpected", "store_unconfirmed"), crashes.captured.map { it.second.extra["kind"] })
        assertEquals("load_deck", crashes.captured.first().second.action)
    }

    @Test
    fun reasonIsTheServerCodeOrTheKind() {
        assertEquals("daily_like_limit", Telemetry.reason(Backend.BackendError.Http(400, "daily_like_limit")))
        assertEquals("offline", Telemetry.reason(IOException("x")))
        assertEquals("server", Telemetry.reason(Backend.BackendError.Http(500, "boom happened")))
    }

    @Test
    fun appLogsReachSentryScrubbed() {
        TelemetryLogHandler.install()
        TelemetryLogHandler.install()
        assertEquals(1, Logger.getLogger("").handlers.count { it is TelemetryLogHandler })
        Logger.getLogger("so.drafft.chat").log(Level.WARNING, "send failed for maya@example.com")
        assertEquals(listOf("send failed for [email]"), crashes.logs)
        Logger.getLogger("io.ktor.client").log(Level.SEVERE, "not ours")
        assertTrue(crashes.messages.isEmpty())
        Logger.getLogger("safety").log(Level.SEVERE, "BLOCK refused")
        assertEquals(listOf("BLOCK refused"), crashes.messages)
    }
}
