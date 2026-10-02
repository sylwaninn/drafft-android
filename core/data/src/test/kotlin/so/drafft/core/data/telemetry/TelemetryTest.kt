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
import kotlinx.coroutines.runBlocking
import so.drafft.core.data.backend.ProfileSync
import so.drafft.core.data.media.MediaUploadError
import so.drafft.core.data.platform.InMemoryKeyValueStore
import so.drafft.core.data.store.Store
import so.drafft.core.data.verification.VerificationError

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
        val reports = mutableListOf<Telemetry.ErrorReport>()
        val logs = mutableListOf<String>()
        val finished = mutableListOf<Boolean>()
        override fun setUser(id: String?) { userID = id }
        override fun setTag(key: String, value: String?) = Unit
        override fun breadcrumb(crumb: Telemetry.Breadcrumb) { crumbs += crumb }
        override fun capture(error: Throwable, report: Telemetry.ErrorReport) { captured += error to report }
        override fun message(text: String, level: Telemetry.Level, report: Telemetry.ErrorReport) {
            messages += text
            reports += report
        }
        override fun log(level: Telemetry.Level, text: String, attributes: Map<String, Any>) { logs += text }
        override fun startSpan(operation: String, description: String): Telemetry.Span = object : Telemetry.Span {
            override fun setData(key: String, value: Any) = Unit
            override fun finish(ok: Boolean) { finished += ok }
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
    fun superPropertiesSurviveANewAnonymousID() {
        Telemetry.register("app_environment", "production")
        Telemetry.signedIn("a")
        Telemetry.signedIn(null)
        val afterReset = analytics.calls.dropWhile { it != "reset" }
        assertTrue("register:app_environment=production" in afterReset, afterReset.toString())
    }

    @Test
    fun personPropertiesDescribedBeforeIdentifyingAreSentOnceIdentified() {
        // Described first (the model's state settles before the consent and the session are known).
        Telemetry.describeAccount(mapOf("is_premium" to true, "language" to "fr"))
        assertFalse(analytics.calls.any { it.startsWith("person") })
        Telemetry.signedIn("a")
        Telemetry.applyConsent(AnalyticsConsent.GRANTED)
        assertEquals(listOf("identify:a", "person:[is_premium, language]"), analytics.calls.filter { it.startsWith("identify") || it.startsWith("person") })
        // Identified: described straight away.
        Telemetry.describeAccount(mapOf("language" to "en"))
        assertEquals("person:[language]", analytics.calls.last())
    }

    @Test
    fun anotherAccountDoesNotInheritThePreviousOnesProperties() {
        Telemetry.applyConsent(AnalyticsConsent.GRANTED)
        Telemetry.signedIn("a")
        Telemetry.describeAccount(mapOf("is_premium" to true))
        Telemetry.signedIn("b")
        assertEquals(listOf("identify:a", "person:[is_premium]", "identify:b"), analytics.calls.filter { it.startsWith("identify") || it.startsWith("person") })
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
            AnalyticsEvent.AccountCreated(AnalyticsEvent.AuthMethod.EMAIL), AnalyticsEvent.OnboardingCompleted(3, 2, 1, true, false, true, false, true, 9),
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

    @Test
    fun everyEventPassesThePrivacyGuardUntouched() {
        // Every event of the catalog, with sample values: a forbidden or free-text property fails here.
        val all = listOf(
            AnalyticsEvent.AccountCreated(AnalyticsEvent.AuthMethod.entries.first()),
            AnalyticsEvent.SignUpFailed("some_code"),
            AnalyticsEvent.EmailConfirmed(),
            AnalyticsEvent.EmailCodeResent(),
            AnalyticsEvent.LoggedIn(AnalyticsEvent.AuthMethod.entries.first()),
            AnalyticsEvent.LogInFailed("some_code"),
            AnalyticsEvent.PasswordResetRequested(),
            AnalyticsEvent.PasswordResetCompleted(),
            AnalyticsEvent.LoggedOut(),
            AnalyticsEvent.SessionEnded("some_code"),
            AnalyticsEvent.AccountDeleted(),
            AnalyticsEvent.AccountDeleteFailed("some_code"),
            AnalyticsEvent.EmailChanged(),
            AnalyticsEvent.PasswordChanged(),
            AnalyticsEvent.DataExportRequested(),
            AnalyticsEvent.TermsAccepted("some_code", "some_code"),
            AnalyticsEvent.AnalyticsConsentChanged(AnalyticsConsent.GRANTED),
            AnalyticsEvent.AccountHeld(),
            AnalyticsEvent.OnboardingStepViewed("some_code", "some_code", 3, true),
            AnalyticsEvent.OnboardingStepCompleted("some_code", "some_code", 3, true, 3),
            AnalyticsEvent.OnboardingStepBlocked("some_code", "some_code"),
            AnalyticsEvent.OnboardingResumed("some_code", 3),
            AnalyticsEvent.OnboardingCompleted(3, 3, 3, true, true, true, true, true, 3),
            AnalyticsEvent.OnboardingFailed("some_code"),
            AnalyticsEvent.PhoneCodeSent("some_code", true),
            AnalyticsEvent.PhoneCodeFailed("some_code", "some_code"),
            AnalyticsEvent.PhoneVerified("some_code"),
            AnalyticsEvent.PhoneVerificationFailed("some_code", "some_code"),
            AnalyticsEvent.DeckLoaded(3, "some_code", true, 1.5),
            AnalyticsEvent.DeckLoadFailed("some_code"),
            AnalyticsEvent.DeckEmptyShown(true),
            AnalyticsEvent.ProfileSwiped(AnalyticsEvent.SwipeAction.entries.first(), AnalyticsEvent.SwipeSource.entries.first(), true, true, 3, 3),
            AnalyticsEvent.SwipeRefused(AnalyticsEvent.SwipeAction.entries.first(), "some_code"),
            AnalyticsEvent.SwipeUndone(AnalyticsEvent.SwipeAction.entries.first()),
            AnalyticsEvent.DailyLikeLimitReached(),
            AnalyticsEvent.ProfileViewed("some_code", true, 3),
            AnalyticsEvent.FiltersChanged(3, 3, true),
            AnalyticsEvent.BoostStarted(3),
            AnalyticsEvent.BoostFailed("some_code"),
            AnalyticsEvent.VoiceIntroPlayed("some_code"),
            AnalyticsEvent.IcebreakerAnswered(),
            AnalyticsEvent.LikesViewed(3, true),
            AnalyticsEvent.MatchCreated(AnalyticsEvent.MatchSource.entries.first()),
            AnalyticsEvent.MatchScreenAction("some_code"),
            AnalyticsEvent.Unmatched(),
            AnalyticsEvent.MatchEnded(),
            AnalyticsEvent.ChatOpened(3, 3),
            AnalyticsEvent.MessageSent(AnalyticsEvent.MessageKind.entries.first(), true, true, 3),
            AnalyticsEvent.MessageFailed(AnalyticsEvent.MessageKind.entries.first(), "some_code"),
            AnalyticsEvent.MessageRetried(),
            AnalyticsEvent.MessageReacted(true),
            AnalyticsEvent.MessageDeleted(),
            AnalyticsEvent.ChatMuted(true),
            AnalyticsEvent.ChatMarkedUnread(),
            AnalyticsEvent.SessionProposed("some_code", 3),
            AnalyticsEvent.SessionCountered(3),
            AnalyticsEvent.SessionResponded(AnalyticsEvent.SessionResponse.entries.first()),
            AnalyticsEvent.SessionCancelled(),
            AnalyticsEvent.SessionActionFailed("some_code", "some_code"),
            AnalyticsEvent.SessionAddedToCalendar(),
            AnalyticsEvent.PaywallViewed(AnalyticsEvent.ProductKind.entries.first(), Screen.DISCOVER),
            AnalyticsEvent.PaywallDismissed(AnalyticsEvent.ProductKind.entries.first(), true),
            AnalyticsEvent.ProductsLoadFailed(),
            AnalyticsEvent.PurchaseStarted(AnalyticsEvent.ProductKind.entries.first(), "some_code"),
            AnalyticsEvent.PurchaseCompleted(AnalyticsEvent.ProductKind.entries.first(), "some_code", "some_code"),
            AnalyticsEvent.PurchaseCancelled(AnalyticsEvent.ProductKind.entries.first(), "some_code"),
            AnalyticsEvent.PurchaseFailed(AnalyticsEvent.ProductKind.entries.first(), "some_code", "some_code"),
            AnalyticsEvent.PurchaseCredited(3),
            AnalyticsEvent.PurchasesRestored(true),
            AnalyticsEvent.RestoreFailed(),
            AnalyticsEvent.SubscriptionManageOpened(),
            AnalyticsEvent.ProfileEdited(listOf("photos", "bio")),
            AnalyticsEvent.ProfileEditFailed("some_code"),
            AnalyticsEvent.PhotoUploadStarted("some_code", true),
            AnalyticsEvent.PhotoUploadFailed("some_code"),
            AnalyticsEvent.PhotoRemoved(),
            AnalyticsEvent.PhotoModerated("some_code"),
            AnalyticsEvent.PhotoReviewRequested(),
            AnalyticsEvent.VoiceIntroRecorded(3, "some_code"),
            AnalyticsEvent.ProfilePaused(true),
            AnalyticsEvent.SelfieVerificationStarted(),
            AnalyticsEvent.SelfieVerificationSubmitted(),
            AnalyticsEvent.SelfieVerificationFailed("some_code"),
            AnalyticsEvent.UserBlocked(),
            AnalyticsEvent.UserUnblocked(),
            AnalyticsEvent.UserReported("some_code"),
            AnalyticsEvent.ReportFailed("some_code"),
            AnalyticsEvent.LanguageChanged("some_code", "some_code"),
            AnalyticsEvent.PermissionRequested(AnalyticsEvent.Permission.entries.first(), AnalyticsEvent.PermissionResult.entries.first(), "some_code"),
            AnalyticsEvent.NotificationSettingChanged("some_code", true),
            AnalyticsEvent.PushOpened("some_code"),
            AnalyticsEvent.PushReceived("some_code", true),
            AnalyticsEvent.LegalDocOpened("some_code"),
            AnalyticsEvent.SupportContacted("some_code", true),
            AnalyticsEvent.ShareTapped("some_code"),
        )
        PrivacyGuard.strict = true
        val names = all.map { it.name }
        assertEquals(names.size, names.toSet().size, "two events share a name")
        for (event in all) {
            assertTrue(Regex("^[a-z]+(_[a-z]+)*$").matches(event.name), event.name)
            val kept = PrivacyGuard.properties(event.name, event.properties)
            assertEquals(event.properties.filterValues { it != null }.keys, kept.keys, event.name)
        }
    }

    @Test
    fun everyEnumValueIsACode() {
        val values = AnalyticsEvent.SwipeAction.entries.map { it.name } + AnalyticsEvent.MessageKind.entries.map { it.name } +
            AnalyticsEvent.ProductKind.entries.map { it.name } + AnalyticsEvent.PermissionResult.entries.map { it.name } +
            AnalyticsEvent.MatchSource.entries.map { it.name } + AnalyticsEvent.Permission.entries.map { it.name } +
            AnalyticsEvent.SwipeSource.entries.map { it.name } + AnalyticsEvent.SessionResponse.entries.map { it.name } +
            Screen.entries.map { it.id } + ErrorKind.entries.map { it.id }
        for (value in values) assertTrue(PrivacyGuard.isCode(value.lowercase()), value)
    }

    @Test
    fun aCodeEndsWhereItEnds() {
        assertTrue(PrivacyGuard.isCode("daily_like_limit"))
        assertFalse(PrivacyGuard.isCode("daily_like_limit\n"))
        assertTrue(PrivacyGuard.isCode(VerificationError.EmailUnconfirmed.reason))
        assertFalse(PrivacyGuard.properties("x", mapOf("reason" to "ok\n")).containsKey("reason"))
    }

    @Test
    fun productKindFromTheStoreID() {
        assertEquals(AnalyticsEvent.ProductKind.BOOST, AnalyticsEvent.ProductKind.of("so.drafft.app.boost.5"))
        assertEquals(AnalyticsEvent.ProductKind.SUPER_LIKE, AnalyticsEvent.ProductKind.of("so.drafft.app.superlike.3"))
        assertEquals(AnalyticsEvent.ProductKind.TEMPO, AnalyticsEvent.ProductKind.of("so.drafft.app.tempo.monthly"))
    }

    @Test
    fun aSportIsACodeEvenWhenItsIDIsCamelCase() {
        assertEquals("mountain_biking", so.drafft.core.model.Sport.MOUNTAIN_BIKING.telemetryID)
        for (sport in so.drafft.core.model.Sport.entries) assertTrue(PrivacyGuard.isCode(sport.telemetryID), sport.id)
    }

    @Test
    fun aCancelledTaskIsNotAFailureToCount() {
        Telemetry.track(AnalyticsEvent.MessageFailed(AnalyticsEvent.MessageKind.TEXT, Telemetry.reason(CancellationException("gone"))))
        Telemetry.track(AnalyticsEvent.SwipeRefused(AnalyticsEvent.SwipeAction.LIKE, "cancelled"))
        assertTrue(analytics.events.isEmpty())
        assertTrue(crashes.crumbs.none { it.category == "product" })
        Telemetry.track(AnalyticsEvent.MessageFailed(AnalyticsEvent.MessageKind.TEXT, "offline"))
        assertEquals(listOf("message_failed"), analytics.events.map { it.first })
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
        // And the drop itself is a log line, so it shows in Sentry (once: no breadcrumb besides).
        assertEquals(listOf("property dropped: x.reason: value isn't a number, a boolean or a short code"), crashes.logs)
        assertTrue(crashes.crumbs.isEmpty())
    }

    @Test
    fun onlyListsOfCodesPass() {
        val out = PrivacyGuard.properties("x", mapOf("fields" to listOf("photos", "bio"), "words" to listOf("Hello there"), "numbers" to listOf(1, 2)))
        assertEquals(mapOf<String, Any>("fields" to listOf("photos", "bio")), out)
        assertEquals("photos,bio", Telemetry.render(out.getValue("fields")))
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
        assertTrue(scrubbed.contains("Bearer [token]"), scrubbed)
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
        // Codes the server meant, even without words in the app, and an expired token.
        Telemetry.unexpected(Backend.BackendError.Http(404, "not_found"), "matches")
        Telemetry.unexpected(Backend.BackendError.Http(401, "JWT expired"), "account")
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
    fun reasonReadsTheAppsOwnErrors() {
        assertEquals("email_taken", Telemetry.reason(Backend.EmailAlreadyRegistered()))
        assertEquals("phone_taken", Telemetry.reason(Backend.PhoneAlreadyRegistered()))
        assertEquals("photo_required", Telemetry.reason(ProfileSync.SyncError.Refused("PHOTO_REQUIRED")))
        // A verification error's name, in snake case.
        assertEquals("too_many_codes", Telemetry.reason(VerificationError.TooManyCodes))
        assertEquals("wrong_code", Telemetry.reason(VerificationError.WrongCode))
        assertEquals("not_linked", Telemetry.reason(Store.StoreError.NotLinked))
        assertEquals("pending", Telemetry.reason(Store.StoreError.Failed(Store.PurchaseProblem.PENDING)))
        assertEquals("not_charged", Telemetry.reason(Store.StoreError.Failed(Store.PurchaseProblem.NOT_CHARGED)))
        // Never words: a message-like code, or a too long one, falls back to the kind.
        assertEquals("refused", Telemetry.reason(ProfileSync.SyncError.Refused("Invalid login credentials")))
        assertEquals("refused", Telemetry.reason(ProfileSync.SyncError.Refused("a".repeat(61))))
        // Not a code: the kind says what happened.
        assertEquals("signed_out", Telemetry.reason(MediaUploadError.TicketExpired))
        assertEquals("server", Telemetry.reason(MediaUploadError.Http(502)))
        assertEquals("offline", Telemetry.reason(Store.StoreError.Offline()))
    }

    @Test
    fun theAppsOwnErrorsAreClassified() {
        assertEquals(ErrorKind.OFFLINE, ErrorKind.of(Store.StoreError.NotLinked))
        assertEquals(ErrorKind.OFFLINE, ErrorKind.of(Store.StoreError.Offline(IOException("RevenueCat unreachable"))))
        assertEquals(ErrorKind.OFFLINE, ErrorKind.of(VerificationError.Network))
        assertEquals(ErrorKind.SERVER, ErrorKind.of(VerificationError.SendFailed))
        assertEquals(ErrorKind.SERVER, ErrorKind.of(VerificationError.CheckUnavailable))
        assertEquals(ErrorKind.REFUSED, ErrorKind.of(VerificationError.WrongCode))
        assertEquals(ErrorKind.REFUSED, ErrorKind.of(ProfileSync.SyncError.Refused("paused")))
        assertEquals(ErrorKind.SIGNED_OUT, ErrorKind.of(MediaUploadError.TicketExpired))
        assertEquals(ErrorKind.SERVER, ErrorKind.of(MediaUploadError.Http(503)))
        assertEquals(ErrorKind.RATE_LIMITED, ErrorKind.of(MediaUploadError.Http(429)))
        assertEquals(ErrorKind.CLIENT_CONTRACT, ErrorKind.of(MediaUploadError.Http(400)))
        assertEquals(ErrorKind.REFUSED, ErrorKind.of(MediaUploadError.Rejected("media_limit")))
        // A call the HTTP client cancelled says so with an IOException.
        assertEquals(ErrorKind.CANCELLED, ErrorKind.of(IOException("Canceled")))
        assertEquals(ErrorKind.OFFLINE, ErrorKind.of(IOException("Unable to resolve host")))
    }

    @Test
    fun aRefusalOfTheStoreOrTheNetworkIsNeverAnAlert() {
        Telemetry.unexpected(Store.StoreError.Offline(), "purchase")
        Telemetry.unexpected(IOException("Canceled"), "discover")
        assertTrue(crashes.captured.isEmpty())
    }

    @Test
    fun problemsAndLogsAreScrubbed() {
        Telemetry.log(Telemetry.Level.WARNING, "send failed for maya@example.com")
        assertEquals(listOf("send failed for [email]"), crashes.logs)
        Telemetry.problem("purchase credited late for maya@example.com", "purchase", extra = mapOf("seconds_to_credit" to 900))
        assertEquals(listOf("purchase credited late for [email]"), crashes.messages)
        // Grouped by the scrubbed words: the fingerprint never holds what the text did.
        assertEquals(listOf("purchase", "purchase credited late for [email]"), crashes.reports.single().fingerprint)
    }

    @Test
    fun traceFinishesTheSpanAndKeepsTheValue() = runBlocking {
        val value = Telemetry.trace("http.client", "POST rest/v1/rpc/discover") { 42 }
        assertEquals(42, value)
        assertEquals(listOf(true), crashes.finished)
        assertFailsWith<IllegalStateException> { Telemetry.trace("media.upload", "profile photo") { error("bug") } }
        assertEquals(listOf(true, false), crashes.finished)
    }

    @Test
    fun appLogsReachSentryScrubbed() {
        TelemetryLogHandler.install()
        TelemetryLogHandler.install()
        assertEquals(1, Logger.getLogger("").handlers.count { it is TelemetryLogHandler })
        Logger.getLogger("so.drafft.chat").log(Level.WARNING, "send failed for maya@example.com")
        assertEquals(listOf("send failed for [email]"), crashes.logs)
        // A warning is a breadcrumb and a log line, never an issue.
        assertTrue(crashes.messages.isEmpty())
        assertEquals(listOf(Telemetry.Level.WARNING), crashes.crumbs.filter { it.category == "log" }.map { it.level })
        Logger.getLogger("io.ktor.client").log(Level.SEVERE, "not ours")
        assertTrue(crashes.messages.isEmpty())
    }

    @Test
    fun aSevereLineIsOneIssueGroupedByItsWords() {
        TelemetryLogHandler.install()
        val record = java.util.logging.LogRecord(Level.SEVERE, "BLOCK refused for maya@example.com").apply {
            loggerName = "so.drafft.safety"
            thrown = IllegalStateException("what someone typed")
        }
        Logger.getLogger("so.drafft.safety").log(record)
        assertEquals(listOf("BLOCK refused for [email]"), crashes.messages)
        // Also a log line, but no breadcrumb of its own and never `unexpected` (no second event).
        assertEquals(listOf("BLOCK refused for [email]"), crashes.logs)
        assertTrue(crashes.crumbs.none { it.category == "log" })
        assertTrue(crashes.captured.isEmpty())
        val report = crashes.reports.single()
        assertEquals("safety", report.area)
        assertEquals("illegal_state_exception", report.extra["exception"])
    }

    // Config

    @Test
    fun onlyEURegionsAreUsed() {
        var config = TelemetryConfig(
            sentryDSN = "https://abc123@o42.ingest.de.sentry.io/7", postHogKey = "phc_x", postHogHost = "https://eu.i.posthog.com",
            environment = "production", version = "1.0", build = "3", isDebugBuild = false,
        )
        assertTrue(config.hasSentry)
        assertTrue(config.hasPostHog)
        assertEquals("so.drafft.app@1.0+3", config.release)
        config = config.copy(sentryDSN = "https://abc123@o42.ingest.us.sentry.io/7", postHogHost = "https://us.i.posthog.com")
        assertFalse(config.hasSentry)
        assertFalse(config.hasPostHog)
        // Look-alikes: the host has to be exactly PostHog's EU cloud, the DSN exactly Sentry's EU ingest.
        for (host in listOf("https://eu.i.posthog.com.example.org", "https://eu.evil.com", "https://eu.i.posthog.com@evil.com", "http://eu.i.posthog.com", "eu.i.posthog.com", "https://eu.i.posthog.com:8443", "https://eu.i.posthog.com/x", "https://eu.i.posthog.com?a=b", "https://us.i.posthog.com", "")) {
            assertFalse(config.copy(postHogHost = host).hasPostHog, host)
        }
        for (dsn in listOf("https://abc123@o42.ingest.de.sentry.io.evil.com/7", "https://abc123@evil.com/o42.ingest.de.sentry.io/7", "https://abc123@o42.ingest.de.sentry.io/7\n", "https://abc123@o42.ingest.sentry.io/7")) {
            assertFalse(config.copy(sentryDSN = dsn).hasSentry, dsn)
        }
        assertTrue(config.copy(postHogHost = "https://eu.i.posthog.com/").hasPostHog)
        config = config.copy(sentryDSN = "", postHogKey = "")
        assertFalse(config.hasSentry)
        assertFalse(config.hasPostHog)
    }

    @Test
    fun productionSamplesStagingKeepsEverything() {
        val production = TelemetryConfig("", "", "", "production", "", "", false)
        val staging = production.copy(environment = "staging")
        assertEquals(0.02, production.requestSampleRate)
        assertEquals(0.2, production.tracesSampleRate)
        assertEquals(1.0, staging.requestSampleRate)
        assertEquals(1.0, staging.tracesSampleRate)
    }
}
