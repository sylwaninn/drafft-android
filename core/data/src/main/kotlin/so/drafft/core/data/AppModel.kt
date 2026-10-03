package so.drafft.core.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.backend.LikeCard
import so.drafft.core.data.backend.MatchRow
import so.drafft.core.data.backend.ProfileCard
import so.drafft.core.data.backend.ProfileSync
import so.drafft.core.data.backend.Safety
import so.drafft.core.data.backend.SafetyOutbox
import so.drafft.core.data.backend.ServerMessage
import so.drafft.core.data.backend.asArray
import so.drafft.core.data.backend.asBoolean
import so.drafft.core.data.backend.DeckMerge
import so.drafft.core.data.backend.asObject
import so.drafft.core.data.backend.asString
import so.drafft.core.data.backend.attempt
import so.drafft.core.data.backend.attemptOrNull
import so.drafft.core.data.backend.boolean
import so.drafft.core.data.backend.int
import so.drafft.core.data.backend.jsonOf
import so.drafft.core.data.backend.opener
import so.drafft.core.data.backend.optString
import so.drafft.core.data.backend.parseJsonOrNull
import so.drafft.core.data.backend.profile
import so.drafft.core.data.backend.serverFilters
import so.drafft.core.data.cache.LocalCache
import so.drafft.core.data.chat.ChatService
import so.drafft.core.data.location.LocationOnce
import so.drafft.core.data.media.MediaURL
import so.drafft.core.data.notifications.NotificationService
import so.drafft.core.data.notifications.PendingPush
import so.drafft.core.data.notifications.PushRoute
import so.drafft.core.data.platform.AppLifecycle
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.platform.KeyValueStore
import so.drafft.core.data.platform.MonotonicClock
import so.drafft.core.data.platform.PlaybackControl
import so.drafft.core.data.sessions.ServerDate
import so.drafft.core.data.sessions.SessionCalendar
import so.drafft.core.data.sessions.SessionStore
import so.drafft.core.data.store.PurchaseCredit
import so.drafft.core.data.store.Store
import so.drafft.core.data.store.TempoSubscription
import so.drafft.core.data.telemetry.AnalyticsEvent
import so.drafft.core.data.telemetry.Telemetry
import so.drafft.core.data.verification.PhoneCountry
import so.drafft.core.data.moderation.PhotoModeration
import so.drafft.core.model.AppLanguage
import so.drafft.core.model.Conversation
import so.drafft.core.model.DiscoverFilters
import so.drafft.core.model.Icebreaker
import so.drafft.core.model.L
import so.drafft.core.model.Localization
import so.drafft.core.model.Match
import so.drafft.core.model.MessageContent
import so.drafft.core.model.PackPhotos
import so.drafft.core.model.Profile
import so.drafft.core.model.SessionProposal
import so.drafft.core.model.TermsConsent
import so.drafft.core.model.Vitals
import so.drafft.core.model.newestFirst

// One class, one region per domain (Account, Account sync, Live profile, Matches, Discover, Sessions,
// Wallet, Pause, Safety), since extension functions can't reach the model's private state.

/**
 * The app's single observable state, read by every screen (`LocalAppModel.current`). Every property a screen shows is
 * Compose snapshot state; all mutation happens on the main thread ([scope] runs on `Dispatchers.Main.immediate`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppModel(
    private val backend: Backend,
    private val chat: ChatService,
    private val store: Store,
    private val purchaseCredit: PurchaseCredit,
    private val notifications: NotificationService,
    private val sessionStore: SessionStore,
    private val sessionCalendar: SessionCalendar,
    /** The account's moderation hold (the hold screen reads it). */
    val moderation: AccountModeration,
    /** Where each of the person's photos stands with moderation (`publicMe` reads it). */
    val photoModeration: PhotoModeration,
    private val profileSync: ProfileSync,
    private val onboarding: OnboardingStore,
    private val safety: Safety,
    private val locationOnce: LocationOnce,
    private val defaults: KeyValueStore,
    /** Where the local cache keeps each account's copy (never backed up). */
    private val cacheDirectory: File,
    private val lifecycle: AppLifecycle,
    /** The age of reads and the swipe pace. */
    private val clock: MonotonicClock,
    private val playback: PlaybackControl? = null,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    enum class Phase { WELCOME, ONBOARDING, MAIN }
    enum class Tab { DISCOVER, LIKES, SESSIONS, CHATS, ME }
    enum class ProfileLoad { LOADING, FAILED, LOADED }

    /** The model's own work, on the main thread. Lives as long as the app. */
    val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

    /** Local cache writes, one after the other, off the main thread. */
    private val cacheWriter = ioDispatcher.limitedParallelism(1)

    var phase by mutableStateOf(Phase.WELCOME)

    /**
     * Changes on sign-out and account deletion: the tabs are rebuilt fresh for the next person
     * (no chat left open, no sheet, back at the top of every tab).
     */
    var sessionID by mutableStateOf(0)
    var tab by mutableStateOf(Tab.DISCOVER)

    /**
     * The signed-in person's own profile, as read from the server (never sample data). Empty until
     * `profileLoad` is `LOADED`: You shows a loading or retry state instead of it until then.
     */
    var me by mutableStateOf(nobody)

    /**
     * `me` as others see it: only the photos moderation approved. A photo being checked, refused or
     * waiting for a person stays in Edit profile, never on the profile.
     */
    val publicMe: Profile get() = photoModeration.showingApprovedPhotos(me)
    var profileLoad by mutableStateOf(ProfileLoad.LOADING)

    /** Why the last read failed, in words (`FAILED`): the connection only when it never got through. */
    var profileLoadFailure by mutableStateOf<String?>(null)

    /** The signed-in account's last known state on this phone (see `LocalCache`, `refreshAccount`). */
    private var localCache: LocalCache? = null

    /** The account read in flight, shared by everyone who asks meanwhile. */
    private var accountRefresh: Deferred<ProfileSync.Account?>? = null

    /**
     * In the tabs without knowing whether sign-up is finished: the next account read that answers
     * decides (`routeWhenAccountRead`), whoever asked for it.
     */
    private var routeOnAccountRead = false

    /** The last account read, and when: a read asked for right after it reuses it. */
    private var lastAccountRead: Pair<Instant, ProfileSync.Account>? = null

    /**
     * drafft tempo's details as the store reports them for this account (length, price, renewal),
     * shown in You. Billed and managed by the store: the app only reads it and links to its
     * management page. Whether it's on comes from the server: `isPremium`.
     */
    var subscription by mutableStateOf<TempoSubscription?>(null)

    /**
     * The account's wallet on the server (`wallets`, credited by the purchase webhook and the weekly
     * boost): the only source of drafft tempo, boosts and super likes. Never credited on the device.
     */
    var premiumUntil by mutableStateOf<Instant?>(null)
    val isPremium: Boolean get() = (premiumUntil ?: Instant.MIN).isAfter(Instant.now())

    // Likes, super likes & boosts

    /** Likes left in the last 24 hours, as the server counts them (`likes_left`). Null until read, and
     * with drafft tempo. */
    var likesLeft by mutableStateOf<Int?>(null)

    /** Bought in packs (one-time purchases); they never expire. From the wallet (`loadWallet`). */
    var superLikes by mutableStateOf(0)
    var boosts by mutableStateOf(0)
    var boostEndsAt by mutableStateOf<Instant?>(null)

    /** Confirmation banner after starting a boost (its id restarts the auto-dismiss). */
    var boostBanner by mutableStateOf<UUID?>(null)

    /** A refusal or failure to show above the tabs (a swipe, an undo or a boost that didn't go through). */
    var notice by mutableStateOf<Notice?>(null)

    data class Notice(val text: String, val id: UUID = UUID.randomUUID())

    /** Sessions the person saved to their calendar, in this launch (the lasting link is `SessionCalendar`). */
    var sessionsInCalendar by mutableStateOf<Set<UUID>>(emptySet())

    fun isBoosting(at: Instant = Instant.now()): Boolean = (boostEndsAt ?: Instant.MIN).isAfter(at)

    enum class Consumable { BOOST, SUPER_LIKE }

    // Account & settings: read from the account at sign-in, emptied at sign-out.
    var email by mutableStateOf("")

    private var languageState by mutableStateOf(Localization.language)

    /** The app's language (the root redraws in it at once). */
    var language: AppLanguage
        get() = languageState
        set(value) {
            Localization.use(value)
            // Kept on the phone so the first screen of the next launch is already in it.
            defaults.putString(LANGUAGE_KEY, value.code)
            notifications.language = value
            if (value != languageState) Telemetry.track(AnalyticsEvent.LanguageChanged(value.code, during = phase.name.lowercase()))
            languageState = value
        }

    /**
     * Verified at sign-up, can be replaced (after verifying the new one), never removed. Read from
     * the account (Supabase Auth keeps the verified number), null until then.
     */
    var phoneNumber by mutableStateOf<String?>(null)

    private var profilePausedState by mutableStateOf(false)

    /**
     * Paused: hidden from everyone, and discovery waits (no like, pass, undo or boost) until it's
     * resumed; Discover shows a lock over the deck (`pausedLock`). Chats, sessions, reports and the
     * profile keep working with current matches.
     */
    var profilePaused: Boolean
        get() = profilePausedState
        set(value) {
            val old = profilePausedState
            profilePausedState = value
            pauseChanged(from = old)
        }

    /** Set while applying the server's own state, so it isn't sent back. */
    private var pauseFromServer = false

    /**
     * Flips of the switch on this phone, and those still being saved: a read of the server that
     * began before the latest flip was saved says the old state, and mustn't put it back on screen.
     */
    private var pauseEdits = 0
    private var pauseSaves = 0

    /** The latest flip's save: null once saved, else why it wasn't (the switch went back). */
    private var pauseSave: Deferred<String?>? = null

    var notifyMatches by mutableStateOf(true)
    var notifyMessages by mutableStateOf(true)
    var notifySessions by mutableStateOf(true)

    /** People you blocked: gone from Discover, Likes and Chats until you unblock them. */
    var blocked by mutableStateOf<List<Profile>>(emptyList())
    val blockedCount: Int get() = blocked.size

    /** Data export: requested here, sent by email as a download link (server side). */
    var dataExportRequestedAt by mutableStateOf<Instant?>(null)

    /**
     * Whether this account's consent to the current terms and to the use of its sensitive data is
     * on record. `REQUIRED`, from a fresh read only: `TermsConsentView` asks at each open until
     * it's accepted, after the location gate. `UNKNOWN` until a read says (account sync).
     */
    var termsConsent by mutableStateOf(TermsConsent.Gate.UNKNOWN)

    /**
     * The account read failed while the consent was unknown: read again after a pause, longer each
     * time (`refreshAccount`).
     */
    private var accountRetry: Job? = null
    private var accountReadFailures = 0

    /** 0...1, with the next thing worth adding. */
    data class ProfileCompletion(val value: Double, val next: String?)

    val profileCompletion: ProfileCompletion
        get() {
            val checks = listOf(
                (publicMe.allPhotos.size >= 4) to L("Add a few more photos"),
                me.bio.isNotEmpty() to L("Write a short bio"),
                (me.voiceIntro != null) to L("Record a voice intro"),
                (me.prompts.size >= 3) to L("Answer a third prompt"),
                me.goal.isNotEmpty() to L("Add what you're training for"),
            )
            val done = checks.count { it.first }
            return ProfileCompletion(done.toDouble() / checks.size, checks.firstOrNull { !it.first }?.second)
        }

    // Discover (the Discover section below): batches from `discover`, kept on this phone between launches.

    /** The cards not swiped yet, best first. Only the server's: never sample people. */
    var queue by mutableStateOf<List<Profile>>(emptyList())
    val deck: List<Profile> get() = queue

    /** Where the deck stands (first load, a refusal like `location_required`). */
    var deckState by mutableStateOf<DeckState>(DeckState.Idle)

    sealed interface DeckState {
        data object Idle : DeckState
        data object Loading : DeckState
        data object Loaded : DeckState
        data class Failed(val text: String) : DeckState
    }

    private var filtersState by mutableStateOf(DiscoverFilters.decode(defaults.getString(DiscoverFilters.STORAGE_KEY)))

    var filters: DiscoverFilters
        get() = filtersState
        set(value) {
            val old = filtersState
            if (value == old) return
            filtersState = value
            defaults.putString(DiscoverFilters.STORAGE_KEY, value.encode())
            if (value.audience != old.audience) emptyPile = PackPhotos.pick(value.audience)
            filtersChanged()
        }

    /**
     * The photos on Discover's empty stack: the people you're looking for. Dealt again only when
     * "Show me" changes, never for distance, age or filters.
     */
    var emptyPile by mutableStateOf<List<String>>(emptyList())

    /**
     * This session's swipes, last one last: what undo can bring back (the server undoes the last one,
     * within 10 minutes, if it didn't make a match).
     */
    var history by mutableStateOf<List<Swiped>>(emptyList())

    data class Swiped(
        val profile: Profile,
        val liked: Boolean,
        val superLike: Boolean,
        val at: Instant,
        /** Answered from Likes (it goes back there on undo). */
        val fromLikes: Boolean = false,
        val matched: Boolean = false,
    )

    private var discovery = DiscoveryState()

    // Likes and matches (the Matches section below)

    private var likedMeState by mutableStateOf<List<Profile>>(emptyList())

    /** Everyone who liked you and is waiting for an answer (`liked_me`), super likes first. */
    var likedMe: List<Profile>
        get() = likedMeState
        set(value) {
            likedMeState = value
            refreshBadges()
        }

    private var blurredLikesState by mutableStateOf<List<BlurredLike>>(emptyList())

    /** Without drafft tempo: who liked you, blurred by the server (`liked_me` without identities). */
    var blurredLikes: List<BlurredLike>
        get() = blurredLikesState
        set(value) {
            blurredLikesState = value
            refreshBadges()
        }

    /** Current matches (`my_matches`), newest first. */
    var matches by mutableStateOf<List<Match>>(emptyList())

    /**
     * Where a list from the server stands before it has anything to show: an empty list means "nobody"
     * only once it was read (or this phone's copy of a read was shown). A first read that failed shows a
     * retry, never an empty state.
     */
    sealed interface ListLoad {
        data object Loading : ListLoad
        data class Failed(val offline: Boolean) : ListLoad
        data object Loaded : ListLoad
    }
    var likesLoad by mutableStateOf<ListLoad>(ListLoad.Loading)
    var matchesLoad by mutableStateOf<ListLoad>(ListLoad.Loading)

    // Chats

    private var conversationsState by mutableStateOf<List<Conversation>>(emptyList())

    /** One per active match (`matches`), with its chat channel (`ChatService`): never sample data. */
    var conversations: List<Conversation>
        get() = conversationsState
        set(value) {
            conversationsState = value
            refreshBadges()
        }

    /** The chat currently on screen (its messages count as read). */
    var openChatID by mutableStateOf<String?>(null)

    /** A request to show a chat from the Chats tab (match screen, banner); consumed by ConversationsView. */
    var chatRequest by mutableStateOf<String?>(null)

    /**
     * Bumped each time a chat is opened from outside it (notification, match banner): the chat
     * jumps to its latest message even if it was already open and scrolled up.
     */
    var latestRequest by mutableStateOf<LatestRequest?>(null)

    data class LatestRequest(val chatID: String, val token: UUID = UUID.randomUUID())

    // Match moments
    var matchScreen by mutableStateOf<Profile?>(null)
    var banner by mutableStateOf<MatchBanner?>(null)

    data class MatchBanner(val profile: Profile, val id: UUID = UUID.randomUUID())

    /**
     * Tab badge: muted chats don't count. Stored, and only written when it changes, like
     * `likedMeCount`: the tab bar reads these, so a new message or a typing dot doesn't redraw it.
     */
    var unreadTotal by mutableStateOf(0)
        private set

    /** Tab badge: people who like you, from the deck (`likedMe`). */
    var likedMeCount by mutableStateOf(0)
        private set

    init {
        refreshBadges()
        // The hold is read with the rest of the account's row: one read, one source.
        moderation.refresh = { refreshAccount(force = true) }
    }

    private fun refreshBadges() {
        val unread = conversations.sumOf { if (it.muted) 0 else it.unread }
        if (unread != unreadTotal) unreadTotal = unread
        // The full list with drafft tempo, the blurred one without.
        val liked = if (isPremium) likedMe.size else blurredLikes.size
        if (liked != likedMeCount) likedMeCount = liked
    }

    fun toggleMute(id: String) {
        if (conversations.none { it.id == id }) return
        conversations = conversations.map { if (it.id == id) it.copy(muted = !it.muted) else it }
        Telemetry.track(AnalyticsEvent.ChatMuted(muted = conversation(id)?.muted == true))
        chat.toggleMute(id)
    }

    // Account (Supabase Auth)

    /**
     * The session ended without the person logging out (revoked, expired, account deleted
     * elsewhere): back on the welcome screen, a message says so.
     */
    var sessionEndedNotice by mutableStateOf(false)

    /**
     * The person is logging out or deleting their account: the session that ends with it (and any
     * revocation that races it) is theirs, so no message. Cleared at the next sign-in.
     */
    var leavingOnPurpose by mutableStateOf(false)

    // Discover

    val topCard: Profile? get() = deck.firstOrNull()

    /** The chat with this person (match screen, banner), by their profile id: a chat's id is its match's. */
    fun openChatWith(person: String) {
        openChat(conversations.firstOrNull { it.profile.id == person }?.id ?: person)
    }

    fun openChat(id: String) {
        matchScreen = null
        banner = null
        tab = Tab.CHATS
        chatRequest = id
        latestRequest = LatestRequest(chatID = id)
    }

    /**
     * Follows a tapped notification once the tabs are on screen (`MainTabs`, from
     * `NotificationService.pendingRoute`), then counts `push_opened`: `routed` only when the tap reached
     * its own place. If this is cancelled (the tabs left the screen) the tap stays pending and is followed
     * again; a failure is logged and counted as not routed, never kept to fail again.
     */
    suspend fun follow(pending: PendingPush) {
        val tap = pending.tap
        val opened = try {
            // Bound to the account that tapped (null: the session wasn't read yet, whoever signed in).
            val sameAccount = pending.account == null || pending.account == backend.userID?.toString()
            sameAccount && open(tap.route)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Telemetry.unexpected(e, "push", "follow")
            false
        }
        notifications.finish(pending)
        Telemetry.track(AnalyticsEvent.PushOpened(tap.kind.code, routed = opened && !tap.fallsBack))
    }

    /** Whether the route's own place is what shows now: false when the chat can't be found or the account changed. */
    private suspend fun open(route: PushRoute): Boolean {
        when (route) {
            is PushRoute.Chat -> return openChatFromPush(route.chatID)
            PushRoute.Chats -> showTab(Tab.CHATS)
            PushRoute.Likes -> showTab(Tab.LIKES)
            PushRoute.Sessions -> showTab(Tab.SESSIONS)
            PushRoute.Discover -> showTab(Tab.DISCOVER)
            PushRoute.Current -> Unit
            // Opened at once by `NotificationService.didReceive`, never pending.
            is PushRoute.PhotoRefusal -> return false
        }
        return true
    }

    /**
     * The chat list first, then the chat once it's there. A match made a moment ago may not be in the
     * list yet: one fresh read. Still missing (the match ended, the read failed): the list stays, never
     * an empty chat.
     */
    private suspend fun openChatFromPush(id: String): Boolean {
        val session = sessionID
        if (conversation(id) == null) {
            showTab(Tab.CHATS)
            loadMatches()
        }
        if (session != sessionID || conversation(id) == null) return false
        openChat(id)
        return true
    }

    private fun showTab(target: Tab) {
        matchScreen = null
        banner = null
        tab = target
    }

    // Chat

    fun conversation(id: String): Conversation? = conversations.firstOrNull { it.id == id }

    fun markRead(id: String) {
        if (conversations.none { it.id == id }) return
        conversations = conversations.map { if (it.id == id) it.copy(unread = 0, markedUnread = false) else it }
        scope.launch { chat.markRead(id) }
    }

    /** "Mark as unread": a dot on the chat until it's opened again. */
    fun markUnread(id: String) {
        if (conversations.none { it.id == id }) return
        conversations = conversations.map { if (it.id == id) it.copy(markedUnread = true) else it }
        Telemetry.track(AnalyticsEvent.ChatMarkedUnread())
        chat.markUnread(id)
    }

    /** Sent at once: the bubble shows before the server has it, the ticks catch up. */
    fun send(content: MessageContent, id: String, replyTo: String? = null) {
        val conversation = conversation(id) ?: return
        Haptics.tap()
        val duration = when (content) {
            is MessageContent.Voice -> content.duration.toInt()
            is MessageContent.Video -> content.duration.toInt()
            else -> null
        }
        Telemetry.track(
            AnalyticsEvent.MessageSent(
                kind = AnalyticsEvent.kind(content), isReply = replyTo != null,
                isFirst = isFirstMessage(conversation), durationSeconds = duration,
            ),
        )
        chat.send(content, id, replyTo)
    }

    /**
     * Whether this is the person's first message in the chat. Only the latest page of messages is on the
     * phone: with a full page and none of theirs, it can't be told (null).
     */
    private fun isFirstMessage(conversation: Conversation): Boolean? = when {
        conversation.messages.any { it.fromMe } -> false
        conversation.messages.size < ChatService.PAGE -> true
        else -> null
    }

    /** Your reaction on one of their messages (never on your own: WhatsApp-style, minus self-reactions). */
    fun react(emoji: String?, messageID: String, id: String) {
        val message = conversation(id)?.messages?.firstOrNull { it.id == messageID } ?: return
        if (message.fromMe) return
        Haptics.select()
        Telemetry.track(AnalyticsEvent.MessageReacted(removed = emoji == null))
        chat.react(emoji, messageID, message.reaction, id)
    }

    fun delete(messageID: String, id: String) {
        if (conversation(id)?.messages?.firstOrNull { it.id == messageID }?.fromMe != true) return
        Telemetry.track(AnalyticsEvent.MessageDeleted())
        chat.delete(messageID, id)
    }

    /** A message that couldn't be sent, tapped: sent again. */
    fun retry(messageID: String, id: String) {
        Haptics.tap()
        Telemetry.track(AnalyticsEvent.MessageRetried())
        chat.retry(messageID, id)
    }

    /** Unknown counts as yes: the server has the last word (`daily_like_limit`). */
    val canLike: Boolean get() = isPremium || (likesLeft ?: 1) > 0

    /** The last swipe, within 10 minutes, if it didn't make a match: the server's rule. */
    val canUndo: Boolean
        get() {
            val last = history.lastOrNull() ?: return false
            return !last.matched && Duration.between(last.at, Instant.now()).seconds < UNDO_WINDOW_SECONDS
        }

    // region Account: signing in and out, and deleting the account.

    /** An unfinished sign-up always resumes, whatever the entry point. `immediately`: at launch,
     * under the splash (no keyboard to put away, nothing to wait for). */
    fun signIn(onboard: Boolean, immediately: Boolean = false) {
        val target = if (onboard || onboarding.hasUnfinished) Phase.ONBOARDING else Phase.MAIN
        leavingOnPurpose = false
        // Purchases follow the account (the webhook credits this id), and its balances are the server's.
        scope.launch {
            store.link()
            loadWallet()
        }
        scope.launch { loadAccount() }
        // A finished profile on the server is the one shown in You (another device, a reinstall),
        // with its pause, hold and settings: one read.
        if (target == Phase.MAIN) {
            scope.launch { refreshAccount() }
            // The last known deck, likes and matches at once, then the server's.
            showCachedDiscovery()
        }
        if (immediately) {
            if (target == Phase.MAIN) tab = Tab.DISCOVER
            phase = target
            return
        }
        // The keyboard goes away first (the screens drop their focus), so the next screen lays out
        // at full height.
        scope.launch {
            delay(250.milliseconds)
            // The tabs were walked through in the background (see RootView): land on Discover.
            if (target == Phase.MAIN) tab = Tab.DISCOVER
            phase = target
        }
    }

    /**
     * Logged in, or a password reset: to sign-up if it isn't finished (another device, a reinstall).
     * Without an answer (offline, a server error), in as far as this phone knows, and to sign-up as soon
     * as a read says it isn't finished: never left in the tabs with a profile that can't open.
     */
    suspend fun enterAfterLogIn() {
        refreshAccount()?.let {
            signIn(onboard = !it.onboarded)
            return
        }
        routeOnAccountRead = true
        signIn(onboard = false)
        scope.launch { routeWhenAccountRead() }
    }

    /**
     * In the tabs without knowing whether sign-up is finished: the account is read again, less often
     * each time, until a read answers (this loop's or any other: Realtime, back at the front), and that
     * read brings sign-up back if it isn't finished ([routeIfUnfinished]).
     */
    suspend fun routeWhenAccountRead() {
        val session = sessionID
        var wait = 2.seconds
        // Waits first: the read that just failed was the first try, and the tabs are on screen by then.
        while (routeOnAccountRead) {
            delay(wait)
            if (session != sessionID || phase != Phase.MAIN || !routeOnAccountRead) return
            refreshAccount()?.let(::routeIfUnfinished)
            wait = minOf(wait * 2, 60.seconds)
        }
    }

    /**
     * The first account read that answers in the tabs after an unknown sign-in: to sign-up if it isn't
     * finished. One that answers before the tabs show (sign-in's own read) leaves it to the loop's next read.
     */
    private fun routeIfUnfinished(account: ProfileSync.Account) {
        if (!routeOnAccountRead || phase != Phase.MAIN) return
        routeOnAccountRead = false
        if (!account.onboarded) phase = Phase.ONBOARDING
    }

    fun finishOnboarding(profile: Profile) {
        onboarding.clear()
        me = profile
        profileLoad = ProfileLoad.LOADED
        // The server's copy (photo URLs) replaces the one sign-up built, and goes to the local cache.
        scope.launch { refreshAccount(force = true) }
        tab = Tab.DISCOVER
        phase = Phase.MAIN
        // The first deck, now that the profile is open.
        refreshDiscovery(DiscoveryFreshness.Moment.ENTERED)
    }

    /**
     * Reads the person's profile from the server (You's retry). Until it's in, You shows a loading
     * state, or a retry if it failed: never another profile, and Edit profile can't save over the
     * real one (`ProfileSync.loadedAccount`), even while a copy from the local cache shows.
     */
    suspend fun loadProfile() {
        if (profileLoad != ProfileLoad.LOADED) profileLoad = ProfileLoad.LOADING
        refreshAccount(force = true)
    }

    /**
     * The account's own email and verified phone: from the saved session straight away, then from
     * the server (a number verified on another device, an email changed elsewhere).
     * It's also where a session restored at launch is checked: one the server no longer accepts
     * (revoked, account deleted elsewhere) ends, with the message.
     */
    suspend fun loadAccount() {
        val session = sessionID
        apply(backend.client.auth.currentUserOrNull())
        try {
            val user = backend.client.auth.retrieveUserForCurrentSession(updateSession = true)
            if (session == sessionID) apply(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Refused by Auth (session revoked, user gone): ended. Offline or a server hiccup: the
            // saved session is the best we know.
            if (Backend.refusesSession(e) && session == sessionID) {
                endSession()
            } else {
                Telemetry.unexpected(e, "account", "load_account")
                accountLog.warning("The account couldn't be read: $e")
            }
        }
    }

    private fun apply(user: UserInfo?) {
        user ?: return
        user.email?.takeIf { it.isNotEmpty() }?.let { email = it }
        val phone = user.phone ?: ""
        phoneNumber = if (user.phoneConfirmedAt != null && phone.isNotEmpty()) PhoneCountry.display(phone) else null
    }

    /**
     * At launch: a saved session goes straight in, to sign-up if it isn't finished, without waiting
     * for the network. The account as this phone last saw it shows at once (local cache), then the
     * server's replaces it; the session itself is checked on the way (`loadAccount`: one the server
     * no longer accepts goes back to the welcome screen, with the message).
     *
     * The first launch of an account on this phone has no copy: the server says whether sign-up is
     * finished, but the splash waits for it [FIRST_READ_LIMIT] at most. Past that it goes in, and
     * moves to sign-up if the answer, when it comes, says it isn't finished.
     */
    suspend fun restoreSession() {
        if (phase != Phase.WELCOME || !backend.hasSession()) return
        email = backend.client.auth.currentUserOrNull()?.email ?: email
        showCachedAccount()?.let {
            signIn(onboard = !it.onboarded, immediately = true)
            return
        }
        val session = sessionID
        val read = scope.async { refreshAccount() }
        // The value, or null if it takes longer than the limit (the read itself goes on).
        val answer = withTimeoutOrNull(FIRST_READ_LIMIT) { read.awaitOrNull() }
        if (session != sessionID || phase != Phase.WELCOME || !backend.hasSession()) return
        if (answer != null) {
            signIn(onboard = !answer.onboarded, immediately = true)
        } else {
            // No answer yet (slow or no network): in, as far as this phone knows, until the server says.
            // The late read, when it answers, decides ([routeIfUnfinished]); else it's tried again.
            routeOnAccountRead = true
            signIn(onboard = false, immediately = true)
            scope.launch {
                read.awaitOrNull()?.let(::routeIfUnfinished)
                routeWhenAccountRead()
            }
        }
    }

    /**
     * Follows the account's session for as long as the app runs: when it ends without the person
     * logging out (a refresh the server refused, sessions revoked, the account deleted on another
     * device), the app goes back to the welcome screen and says why. Logging out or deleting the
     * account sets `leavingOnPurpose` first, so those never show the message.
     */
    suspend fun watchSession() {
        var signedIn = false
        backend.client.auth.sessionStatus.collect { status ->
            when (status) {
                is SessionStatus.Authenticated -> signedIn = true
                is SessionStatus.NotAuthenticated -> {
                    if (!signedIn) return@collect
                    signedIn = false
                    // However the session ended, the next sign-in registers this device's token again.
                    notifications.forgetPushTokenRegistration()
                    if (phase == Phase.WELCOME || leavingOnPurpose) return@collect
                    Telemetry.track(AnalyticsEvent.SessionEnded(reason = "not_authenticated"))
                    resetAccountState()
                    sessionEndedNotice = true
                }
                else -> Unit
            }
        }
    }

    /**
     * The server's app-wide refusals, for as long as the app runs: a paused profile locks discovery at
     * once; a hold, or a pause that may be one (a hold pauses the profile too, and bans it from chats),
     * reads the account's hold.
     */
    suspend fun followServerRefusals() {
        backend.events.collect { event ->
            when (event) {
                Backend.Event.PROFILE_PAUSED_BY_SERVER -> {
                    serverRefusedPaused()
                    scope.launch { moderation.load() }
                }
                Backend.Event.ACCOUNT_HELD_BY_SERVER -> {
                    Telemetry.track(AnalyticsEvent.AccountHeld())
                    scope.launch { moderation.load() }
                }
            }
        }
    }

    /** The saved session is no good any more: dropped from this phone, with the same message. */
    suspend fun endSession() {
        backend.signOut()
        if (!leavingOnPurpose) sessionEndedNotice = true
    }

    /**
     * `session_revoked` on the person's topic (UserChannel): sessions ended on the server (sophros "Sign
     * out everywhere", a sign-out everywhere from another device). If this device's is one of them, it
     * signs out now, while its token still works: the push token is dropped and purchases stop following
     * the account. watchSession then shows "You've been logged out".
     */
    suspend fun sessionsRevoked(ids: List<String>) {
        val mine = backend.sessionID ?: return
        if (mine !in ids) return
        store.unlink()
        notifications.unregisterPushToken()
        backend.signOut()
    }

    fun signOut() {
        Telemetry.track(AnalyticsEvent.LoggedOut())
        leavingOnPurpose = true
        sessionEndedNotice = false
        scope.launch {
            // This device stops getting the account's pushes, and its purchases stop following it.
            store.unlink()
            notifications.unregisterPushToken()
            backend.signOut()
        }
        resetAccountState()
    }

    /**
     * Deletes the account on the server (profile, photos, matches, chats), then resets the app.
     * Without a session nothing can be deleted: it throws, and the sheet says so.
     */
    suspend fun deleteAccount() {
        // The server revokes the session as it deletes: that end is the person's, not a surprise.
        leavingOnPurpose = true
        try {
            backend.function("delete-account")
        } catch (e: Exception) {
            leavingOnPurpose = false
            Telemetry.track(AnalyticsEvent.AccountDeleteFailed(Telemetry.reason(e)))
            Telemetry.unexpected(e, "account", "delete")
            throw e
        }
        Telemetry.track(AnalyticsEvent.AccountDeleted())
        store.unlink()
        onboarding.clear()
        resetAccountState()
        sessionEndedNotice = false
        backend.signOut()
    }

    /**
     * Nothing of the account stays in the app once it signs out or is deleted: the next person who
     * signs in on this phone starts empty, and only sees their own profile once it's read.
     */
    private fun resetAccountState() {
        playback?.stop()
        // A tapped notification meant for the account that left goes nowhere.
        notifications.dropPendingRoute()
        // The chat disconnects, drops this device's push registration and its offline copy.
        scope.launch { chat.stop() }
        conversations = emptyList()
        clearDiscovery()
        openChatID = null
        chatRequest = null
        matchScreen = null
        banner = null
        boostBanner = null
        sessionsInCalendar = emptySet()
        sessionCalendar.forgetAll()
        sessionStore.reset()
        blocked = emptyList()
        // What's still waiting stays on this phone for the account's next sign-in (SafetyOutbox).
        safetyRetry?.cancel()
        safetyAttempts = 0
        dataExportRequestedAt = null
        termsConsent = TermsConsent.Gate.UNKNOWN
        filters = DiscoverFilters()
        clearWallet()
        me = nobody
        profileLoad = ProfileLoad.LOADING
        profileSync.loadedAccount = null
        eraseLocalCache()
        email = ""
        phoneNumber = null
        applyServerPause(false)
        routeOnAccountRead = false
        sessionID += 1
        phase = Phase.WELCOME
        tab = Tab.DISCOVER
    }

    // endregion

    // region Account sync

    // The account's own profile row, from one source: `refreshAccount` reads it in one request and
    // applies it everywhere it shows (You, the pause, a moderation hold, notification settings), and
    // the local cache keeps a copy so the next launch shows it before the network answers.
    //
    // Read again at sign-in, when the account's Realtime channel (re)joins or says it changed
    // (`UserChannel`), and back at the front. Nothing else reads the row on its own.

    /**
     * Reads the account from the server and applies it. Concurrent calls share one request; `force`
     * skips the recent-read shortcut (a live event said it changed). Null if it couldn't be read:
     * what's on screen stays (offline: the last known state).
     */
    suspend fun refreshAccount(force: Boolean = false): ProfileSync.Account? {
        accountRefresh?.let { return it.awaitOrNull() }
        lastAccountRead?.let { (at, account) ->
            if (!force && Duration.between(at, Instant.now()).seconds < FRESH_FOR_SECONDS) return account
        }
        val session = sessionID
        val pauseEdits = pauseEdits
        val task = scope.async {
            try {
                val (account, data) = profileSync.loadAccount() ?: throw Backend.BackendError.SignedOut
                // Signed out while it loaded: it belongs to the previous account.
                if (session != sessionID) return@async null
                openLocalCache()?.let { cache -> write { cache.save(data, LocalCache.Kind.PROFILE) } }
                lastAccountRead = Instant.now() to account
                apply(account, pauseReadAt = pauseEdits)
                applyConsent(fromServer = account.consent)
                routeIfUnfinished(account)
                account
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session != sessionID) return@async null
                Telemetry.unexpected(e, "account", "refresh")
                accountLog.warning("The account couldn't be read: $e")
                if (profileLoad != ProfileLoad.LOADED) {
                    profileLoadFailure = ServerMessage.text(e, offline = L("Check your connection and try again."))
                    profileLoad = ProfileLoad.FAILED
                }
                retryWhileConsentUnknown()
                null
            }
        }
        accountRefresh = task
        task.invokeOnCompletion { if (accountRefresh === task) accountRefresh = null }
        return task.awaitOrNull()
    }

    /** The account as this phone last saw it, applied at once (launch). Null without a copy. */
    fun showCachedAccount(): ProfileSync.Account? {
        val entry = openLocalCache()?.entry(LocalCache.Kind.PROFILE) ?: return null
        val account = ProfileSync.decodeAccount(entry.data, mediaBase = MediaURL.saved) ?: return null
        apply(account)
        // A cached "accepted" holds (the consent is only withdrawn by deleting the account); a
        // cached "required" may be out of date: only the server's read asks.
        if (account.consent == TermsConsent.Gate.ACCEPTED && termsConsent == TermsConsent.Gate.UNKNOWN) termsConsent = TermsConsent.Gate.ACCEPTED
        return account
    }

    /** The gate from a fresh read. A backend without the consent columns leaves it as it was. */
    private fun applyConsent(fromServer: TermsConsent.Gate) {
        accountReadFailures = 0
        accountRetry?.cancel()
        accountRetry = null
        if (fromServer != TermsConsent.Gate.UNKNOWN) termsConsent = fromServer
    }

    /**
     * While the consent is unknown, a failed read is tried again after 5 s, then 10, 20... up to 5
     * minutes, until one succeeds or the account changes: an account that never consented isn't
     * left unasked because the first read failed.
     */
    private fun retryWhileConsentUnknown() {
        if (termsConsent != TermsConsent.Gate.UNKNOWN || accountRetry != null) return
        val delaySeconds = minOf(300L, 5L shl minOf(accountReadFailures, 6))
        accountReadFailures += 1
        val session = sessionID
        accountRetry = scope.launch {
            delay(delaySeconds * 1000)
            if (session != sessionID) return@launch
            accountRetry = null
            refreshAccount(force = true)
        }
    }

    private fun apply(account: ProfileSync.Account, pauseReadAt: Int? = null) {
        // Before the profile: a refused or pending photo must never show as the profile, not even for a frame.
        photoModeration.track(account.photos)
        me = account.profile
        profileLoad = ProfileLoad.LOADED
        applyServerPause(account.paused, readAt = pauseReadAt)
        moderation.apply(account.hold)
        account.notifications?.let(notifications::applyServer)
    }

    /**
     * The signed-in account's cache, opened on first use (null signed out, or if it can't be
     * opened: the app then simply works without it).
     */
    fun openLocalCache(): LocalCache? {
        val id = backend.userID ?: return null
        localCache?.takeIf { it.account == id }?.let { return it }
        localCache = attemptOrNull { LocalCache(id, cacheDirectory) }
        return localCache
    }

    /** Signed out or deleted: nothing of the account stays on this phone. */
    fun eraseLocalCache() {
        accountRefresh?.cancel()
        accountRefresh = null
        accountRetry?.cancel()
        accountRetry = null
        accountReadFailures = 0
        lastAccountRead = null
        localCache = null
        write { LocalCache.eraseAll(cacheDirectory) }
    }

    /** A cache write, after the ones before it, off the main thread. */
    private fun write(block: () -> Unit) {
        scope.launch(cacheWriter) { block() }
    }

    // endregion

    // region Live profile

    // The person's own profile follows the server on every device (pause, notification settings, language,
    // card). The database broadcasts `profile` on `user:<id>` with the names of the columns that changed
    // (`UserChannel`); the app reads the account row again in one request (`refreshAccount`, the single
    // source of the row) and shows the server's version, so the latest write wins whichever device made it.
    // The row is also read again on foreground and on each (re)connection of the channel: a broadcast
    // missed while away or offline is caught up.

    /**
     * A `profile` event. `fields`: the changed columns (null for an unknown payload). Whatever changed,
     * the whole row is read once and applied everywhere it shows.
     */
    suspend fun profileChanged(fields: Set<String>?) {
        if (phase != Phase.MAIN) return
        if (fields != null && fields.isEmpty()) return
        // Preferences that decide who's in the deck: a new deck (decision 3.8).
        preferencesChanged(fields)
        refreshAccount(force = true)
    }

    // endregion

    // region Matches

    // Who liked you (`liked_me`) and your matches (`my_matches`), from the server and kept live by the
    // account's channel (`UserChannel`): `like`, `match` and `match_ended`. Each event only says something
    // changed; the list is read again, so an event missed while offline is caught up on the next read
    // (front, reconnection). The last lists are kept on this phone and shown at launch.

    /**
     * Everyone waiting for an answer, newest first: the one read of `liked_me`. With drafft tempo
     * the server sends their cards; without, only a blurred list (`blurredLikes`, decision 5.5). Read
     * again when Likes opens, on a `like` or `wallet` event (drafft tempo starting or ending) and on
     * each (re)connection, and quietly back at the front once old enough (`refreshDiscovery`).
     * Unchanged if it can't be read, or if a newer read already landed.
     */
    suspend fun loadLikes() {
        val premium = isPremium
        if (phase != Phase.MAIN) return
        val read = startRead(DiscoveryFreshness.Part.LIKES)
        val data = try {
            backend.rpc("liked_me", jsonOf("p_limit" to LIKES_PAGE))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            readLanded(read, ok = false)
            if (read.session != sessionID) return
            Telemetry.unexpected(e, "likes", "load")
            if (isPremium == premium) likesFailed(e)
            return
        }
        if (isPremium != premium) {
            readLanded(read, ok = false)
            return
        }
        if (premium) {
            val likes = attemptOrNull { LikeCard.list(data) }
            if (!readLanded(read, ok = likes != null) || likes == null) return likesReadDropped(read)
            if (blurredLikes.isNotEmpty()) blurredLikes = emptyList()
            applyLikes(likes)
            openLocalCache()?.let { cache -> write { cache.save(data, LocalCache.Kind.LIKES) } }
        } else {
            val fresh = BlurredLike.list(data)
            if (!readLanded(read, ok = fresh != null) || fresh == null) return likesReadDropped(read)
            if (likedMe.isNotEmpty()) likedMe = emptyList()
            if (fresh != blurredLikes) blurredLikes = fresh
        }
        likesLoad = ListLoad.Loaded
    }

    /**
     * A likes read that isn't applied (unreadable, or older than one already shown): a failure only
     * for this account, and only while nothing was read yet (`likesFailed`).
     */
    private fun likesReadDropped(read: StartedRead) {
        if (read.session != sessionID) return
        likesFailed(null)
    }

    /** A read that failed only shows if nothing was read yet: what's on screen stays otherwise. */
    private fun likesFailed(error: Throwable?) {
        if (likesLoad != ListLoad.Loaded) likesLoad = ListLoad.Failed(offline = error?.let(ServerMessage::isOffline) ?: false)
    }

    private fun applyLikes(likes: List<LikeCard>) {
        val hidden = hiddenIDs()
        val fresh = likes
            .filter { it.card.isShowable && it.card.id !in hidden }
            .map { like -> like.card.profile(mediaBase = MediaURL.saved).copy(likedAt = like.likedAt?.let { attemptOrNull { ServerDate.parse(it) } }) }
            .newestFirst(date = { it.likedAt }, id = { it.id })
        if (fresh != likedMe) likedMe = fresh
    }

    private fun hiddenIDs(): Set<String> = discovery.swiped + blocked.map { it.id } + matches.map { it.profile.id }

    /** `like` on the channel. A super like also pins them first in the deck: it's read again. */
    suspend fun likeReceived(superLike: Boolean) {
        loadLikes()
        if (superLike) loadDeck(DeckLoad.REFRESH)
    }

    /**
     * The current matches. The first read of a session only takes them in; a later one that finds a
     * new match (they liked you back) shows the banner. Unchanged if it can't be read, or if a newer
     * read already landed.
     */
    suspend fun loadMatches() {
        if (phase != Phase.MAIN) return
        val read = startRead(DiscoveryFreshness.Part.MATCHES)
        try {
            val data = backend.rpc("my_matches")
            val rows = MatchRow.list(data)
            if (!readLanded(read, ok = true)) return
            applyMatches(rows, announce = discovery.matchesRead)
            discovery.matchesRead = true
            matchesLoad = ListLoad.Loaded
            openLocalCache()?.let { cache -> write { cache.save(data, LocalCache.Kind.MATCHES) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            readLanded(read, ok = false)
            if (read.session != sessionID) return
            Telemetry.unexpected(e, "matches", "load")
            if (matchesLoad != ListLoad.Loaded) matchesLoad = ListLoad.Failed(offline = ServerMessage.isOffline(e))
        }
    }

    private fun applyMatches(rows: List<MatchRow>, announce: Boolean) {
        val fresh = rows.map { row ->
            Match(id = row.matchId, profile = row.profile.profile(mediaBase = MediaURL.saved), matchedAt = serverDate(row.matchedAt) ?: Instant.now())
        }
        val new = fresh.filter { it.id !in discovery.knownMatches }
        discovery.knownMatches += fresh.map { it.id }
        val ids = fresh.map { it.profile.id }.toSet()
        // Matches that ended while this device wasn't listening: their chats (keyed by match id) go too.
        val ended = matches.map { it.id }.toSet() - fresh.map { it.id }.toSet()
        matches = fresh
        if (conversations.any { it.id in ended }) conversations = conversations.filterNot { it.id in ended }
        // A match is never in the deck or Likes.
        if (queue.any { it.id in ids }) queue = queue.filterNot { it.id in ids }
        if (likedMe.any { it.id in ids }) likedMe = likedMe.filterNot { it.id in ids }
        ensureConversations()
        // Someone liked you back (your own swipe shows the match screen instead).
        if (!announce) return
        val first = new.firstOrNull { it.profile.id !in discovery.swiped } ?: return
        if (matchScreen?.id == first.profile.id) return
        Telemetry.track(AnalyticsEvent.MatchCreated(AnalyticsEvent.MatchSource.THEIR_LIKE))
        Haptics.success()
        // Not over its own chat (opened from the match's push before the list had it).
        if (lifecycle.isActive() && openChatID != first.id) banner = MatchBanner(profile = first.profile)
        // In the background, the server's push says it.
    }

    /** `match_ended` on the channel (an unmatch or a block, either side): the match and its chat go. */
    suspend fun matchEnded(matchID: String) {
        val id = matchID.lowercase()
        matches.firstOrNull { it.id == id }?.let {
            Telemetry.track(AnalyticsEvent.MatchEnded())
            endLocally(it)
        }
        loadMatches()
    }

    private fun endLocally(match: Match) {
        matches = matches.filterNot { it.id == match.id }
        conversations = conversations.filterNot { it.id == match.id }
        if (openChatID == match.id) openChatID = null
        if (banner?.profile?.id == match.profile.id) banner = null
        if (matchScreen?.id == match.profile.id) matchScreen = null
    }

    /**
     * Ends the match with this person (`unmatch`): gone at once from Chats; put back with the reason if
     * the server refuses.
     */
    fun unmatch(profile: Profile) {
        val match = matches.firstOrNull { it.profile.id == profile.id } ?: return
        Telemetry.track(AnalyticsEvent.Unmatched())
        endLocally(match)
        scope.launch {
            try {
                backend.rpc("unmatch", jsonOf("p_match" to match.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Already over (the other person unmatched or blocked meanwhile).
                if (ServerMessage.code(e) == "not_found") return@launch
                Telemetry.unexpected(e, "matches", "unmatch")
                if (matches.none { it.id == match.id }) matches = listOf(match) + matches
                ensureConversations()
                Haptics.warning()
                say(e)
            }
        }
    }

    /**
     * Every current match has its chat in Chats (`ChatService`: the match's latest card, its chat
     * channel once connected). A chat's id is its match's id (the server's, as sessions and pushes use it).
     */
    fun ensureConversations() {
        chat.publish()
    }

    /** Likes and matches as this phone last saw them (launch), before the server answers. */
    private fun showCachedLikesAndMatches(cache: LocalCache) {
        // Cards are only kept with drafft tempo (a free account's list is blurred, read live).
        if (isPremium && likedMe.isEmpty()) {
            cache.entry(LocalCache.Kind.LIKES)?.let { entry -> attemptOrNull { LikeCard.list(entry.data) } }
                ?.let {
                    applyLikes(it)
                    likesLoad = ListLoad.Loaded
                }
        }
        if (matches.isEmpty()) {
            cache.entry(LocalCache.Kind.MATCHES)?.let { entry -> attemptOrNull { MatchRow.list(entry.data) } }
                ?.let {
                    applyMatches(it, announce = false)
                    matchesLoad = ListLoad.Loaded
                }
        }
    }

    // endregion

    // region Discover

    // Discover on the server (docs/matching.md in drafft-backend):
    //
    // - Batches from `discover`: the cards still to swipe and 20 new ones (the server ranks from the top,
    //   so it sends both), asked again early enough to arrive before the end at the pace the person swipes
    //   (`DeckPace`, at least 10 cards ahead). Each batch is the server's fresh order: the cards on screen stay in place if the server still has them, and any
    //   card it no longer returns (paused, blocked, swiped on another device, no longer eligible) goes.
    //   The deck is also read again, quietly, back at the front or on Discover shown again once it's
    //   old enough (`DiscoveryFreshness`), when the account's channel (re)joins, when the filters or the
    //   person's own preferences change (from scratch then), and when a pause ends: no card outlives what
    //   the server knows (decision 3.8).
    // - Stale-while-revalidate: the last batch is kept on this phone (`LocalCache`, `DECK`) and shown
    //   at launch while the fresh one loads, only if it's recent (its media links are signed for about an
    //   hour) and was read with the same filters.
    // - Swipes are optimistic: the card leaves at once, the swipe goes to the server in order (undo
    //   undoes the server's last one), and a refusal puts things back as they were, with the server's
    //   reason in the person's language. A like the server turns down as `not_eligible` (or a card gone,
    //   or already swiped elsewhere) just stays gone, without a message.

    /**
     * `REFRESH`: a read asked on screen (a retry, a swipe). `RESTART`: from scratch. `REVALIDATE`: a
     * quiet read nobody asked for (`refreshDiscovery`), which replaces one already on its way.
     */
    enum class DeckLoad { REFRESH, RESTART, REVALIDATE }

    /**
     * Everything discovery shows, read again when [moment] finds it old enough (`DiscoveryFreshness`):
     * the deck, who liked you, the matches and the likes left. Quiet: what's on screen stays until the
     * fresh copy lands, and a failure keeps it. Back at the front on another tab, the deck waits for
     * Discover to show (`TAB_SHOWN`). An empty deck ("no one new", a failure) is read whatever its age:
     * anyone new shows as soon as the person is back.
     */
    fun refreshDiscovery(moment: DiscoveryFreshness.Moment) {
        if (phase != Phase.MAIN) return
        val now = clock.seconds()
        val freshness = discovery.freshness
        fun due(part: DiscoveryFreshness.Part, empty: Boolean = false) = freshness.isDue(part, moment, now, empty)
        if (due(DiscoveryFreshness.Part.DECK, empty = queue.isEmpty()) &&
            (moment != DiscoveryFreshness.Moment.FOREGROUND || tab == Tab.DISCOVER)
        ) {
            loadDeck(DeckLoad.REVALIDATE)
        }
        if (due(DiscoveryFreshness.Part.LIKES)) scope.launch { loadLikes() }
        if (due(DiscoveryFreshness.Part.MATCHES)) scope.launch { loadMatches() }
        if (due(DiscoveryFreshness.Part.LIKES_LEFT)) scope.launch { loadLikesLeft() }
    }

    /** A read of a part, and the account it was started for. */
    private class StartedRead(val read: DiscoveryFreshness.Read, val session: Int, val state: DiscoveryState)

    /** A read of [part] starts (`DiscoveryFreshness`). */
    private fun startRead(part: DiscoveryFreshness.Part) =
        StartedRead(discovery.freshness.start(part, now = clock.seconds()), sessionID, discovery)

    /** A read came back: whether to apply it (still this account's, it worked, and nothing newer was applied). */
    private fun readLanded(started: StartedRead, ok: Boolean): Boolean {
        if (started.session != sessionID || started.state !== discovery) return false
        return discovery.freshness.finish(started.read, ok)
    }

    /** The last known deck, likes and matches, shown at once at launch (then revalidated). */
    fun showCachedDiscovery() {
        val cache = openLocalCache() ?: return
        if (queue.isEmpty()) {
            val entry = cache.entry(LocalCache.Kind.DECK)
            val saved = entry?.takeIf { Duration.between(it.savedAt, Instant.now()).seconds < DeckCache.MAX_AGE_SECONDS }
                ?.let { DeckCache.decode(it.data) }
            if (saved != null && saved.filters == DeckCache.key(filters)) {
                val cards = attemptOrNull { ProfileCard.list(saved.cards) } ?: emptyList()
                queue = cards.filter { it.isShowable }.map { it.profile(mediaBase = MediaURL.saved) }
                discovery.raw = DeckCache.rawByID(saved.cards)
            }
        }
        showCachedLikesAndMatches(cache)
    }

    /**
     * Reads a batch. `RESTART` drops the deck on screen first (new filters or preferences): a card
     * that doesn't fit them any more never shows. A `REFRESH` while one runs waits for it; a `REVALIDATE`
     * replaces it (its caller found it too old or lost). [bySwipe]: the read a swipe asks for as the deck
     * runs low; any other (the front, the channel, the filters) also tries again after the server ran
     * out of new cards.
     */
    fun loadDeck(mode: DeckLoad, bySwipe: Boolean = false) {
        if (phase != Phase.MAIN || profilePaused) return
        if (mode == DeckLoad.REFRESH && discovery.load != null) return
        if (!bySwipe) discovery.exhausted = false
        discovery.load?.cancel()
        discovery.generation += 1
        val generation = discovery.generation
        val state = discovery
        if (mode == DeckLoad.RESTART) {
            queue = emptyList()
            discovery.raw = emptyMap()
            openLocalCache()?.let { cache -> write { cache.remove(LocalCache.Kind.DECK) } }
        }
        // A refresh behind "no one new" keeps that screen: flashing the spinner would replay its
        // entrance for nothing. A quiet read keeps any screen (a failure too) until it lands: the
        // spinner only shows when there's nothing yet.
        if (queue.isEmpty() &&
            (mode == DeckLoad.RESTART || deckState == DeckState.Idle || (mode == DeckLoad.REFRESH && deckState != DeckState.Loaded))
        ) {
            deckState = DeckState.Loading
        }
        val filters = filters
        // The server sends its fresh order from the top, the cards still here included, and may send the
        // swipes not yet on the server (left out): ask for all of those plus a batch of new ones (50 at most).
        val limit = minOf(DECK_MAX_READ, queue.size + discovery.pendingSwipes + DECK_BATCH)
        val read = startRead(DiscoveryFreshness.Part.DECK)
        discovery.load = scope.launch {
            val outcome = fetchDeck(filters, limit)
            // A newer read, or discovery cleared meanwhile (signed out): this one is stale.
            if (state !== discovery || generation != discovery.generation) return@launch
            val ok = outcome is DeckOutcome.Cards
            if (ok) discovery.pace.read(took = clock.seconds() - read.read.startedAt)
            readLanded(read, ok)
            discovery.load = null
            apply(outcome, filters, limit)
            trackDeck(outcome, mode, seconds = clock.seconds() - read.read.startedAt)
        }
    }

    private sealed interface DeckOutcome {
        class Cards(val data: ByteArray) : DeckOutcome
        class Refused(val code: String) : DeckOutcome
        class Failed(val error: Throwable) : DeckOutcome
    }

    /** One `discover` call; with no location on file, the location is sent first and it's asked again once. */
    private suspend fun fetchDeck(filters: DiscoverFilters, limit: Int): DeckOutcome {
        for (attempt in 0 until 2) {
            try {
                return DeckOutcome.Cards(backend.rpc("discover", jsonOf("p_filters" to filters.serverFilters, "p_limit" to limit)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = ServerMessage.code(e) ?: return DeckOutcome.Failed(e)
                if (code == "location_required" && attempt == 0 && locationOnce.send()) continue
                return DeckOutcome.Refused(code)
            }
        }
        return DeckOutcome.Refused("location_required")
    }

    private fun apply(outcome: DeckOutcome, filters: DiscoverFilters, limit: Int) {
        when (outcome) {
            is DeckOutcome.Cards -> {
                val sent = attemptOrNull { ProfileCard.list(outcome.data) } ?: emptyList()
                // Less than asked: the server has no more for now. Swipes stop asking (each would read
                // the same cards again) until another refresh. A full page may have more behind it.
                discovery.exhausted = sent.size < limit
                val cards = sent.filter { it.isShowable }
                val hidden = hiddenIDs()
                val fresh = cards.filter { it.id !in hidden }
                val byID = LinkedHashMap<String, Profile>()
                for (card in fresh) if (card.id !in byID) byID[card.id] = card.profile(mediaBase = MediaURL.saved)
                val order = DeckMerge.merge(current = queue.map { it.id }, fresh = fresh.map { it.id }, keep = DECK_KEEP, exclude = hidden)
                // The fresh copy of each card (new links, a changed profile), in the merged order.
                queue = order.mapNotNull { byID[it] }
                discovery.raw = DeckCache.rawByID(outcome.data)
                deckState = DeckState.Loaded
                saveDeck(filters)
            }
            is DeckOutcome.Refused -> {
                // Paused or on hold: the lock and the hold screen say so.
                if (outcome.code == "paused" || outcome.code == "moderated") {
                    deckState = DeckState.Idle
                    return
                }
                if (queue.isEmpty()) deckState = DeckState.Failed(ServerMessage.text(forCode = outcome.code) ?: ServerMessage.generic)
            }
            is DeckOutcome.Failed ->
                if (queue.isEmpty()) {
                    deckState = DeckState.Failed(
                        ServerMessage.text(outcome.error, offline = L("Couldn't connect. Check your connection and try again.")),
                    )
                }
        }
    }

    private fun trackDeck(outcome: DeckOutcome, mode: DeckLoad, seconds: Double) {
        when (outcome) {
            is DeckOutcome.Cards -> {
                Telemetry.track(AnalyticsEvent.DeckLoaded(queue.size, mode.name.lowercase(), discovery.exhausted, Math.round(seconds * 100) / 100.0))
                if (queue.isEmpty()) Telemetry.track(AnalyticsEvent.DeckEmptyShown(exhausted = discovery.exhausted))
            }
            is DeckOutcome.Refused -> Telemetry.track(AnalyticsEvent.DeckLoadFailed(outcome.code.lowercase()))
            is DeckOutcome.Failed -> {
                Telemetry.track(AnalyticsEvent.DeckLoadFailed(Telemetry.reason(outcome.error)))
                Telemetry.unexpected(outcome.error, "discover", "load_deck", mapOf("mode" to mode))
            }
        }
    }

    /** The deck on screen, for the next launch. */
    private fun saveDeck(filters: DiscoverFilters) {
        val data = DeckCache.encode(filters = DeckCache.key(filters), cards = queue.mapNotNull { discovery.raw[it.id] })
        openLocalCache()?.let { cache -> write { cache.save(data, LocalCache.Kind.DECK) } }
    }

    /** New filters: a new deck, from scratch. */
    fun filtersChanged() {
        Telemetry.track(
            AnalyticsEvent.FiltersChanged(
                maxDistanceKm = filters.maxDistanceKm.toInt(), sports = filters.sportIds.size, sharedSportsOnly = filters.sharedSportsOnly,
            ),
        )
        loadDeck(DeckLoad.RESTART)
    }

    /** The person's own profile changed (any device): preferences that decide the deck start it again. */
    fun preferencesChanged(fields: Set<String>?) {
        val deciding = setOf("interested_in", "gender", "birthdate", "sport_ids", "onboarded_at")
        if (fields == null || fields.any { it in deciding }) loadDeck(DeckLoad.RESTART)
    }

    // Swipes

    /**
     * A like, super like or pass on someone from the deck or from Likes. `opener`: the first message
     * (a super like's note is its text).
     */
    fun swipe(profile: Profile, liked: Boolean, superLike: Boolean = false, opener: MessageContent? = null) {
        if (profilePaused || profile.id in discovery.swiped) return
        val deckIndex = queue.indexOfFirst { it.id == profile.id }.takeIf { it >= 0 }
        val likesIndex = likedMe.indexOfFirst { it.id == profile.id }.takeIf { it >= 0 }
        if (deckIndex == null && likesIndex == null) return
        Telemetry.track(
            AnalyticsEvent.ProfileSwiped(
                action = swipeAction(liked, superLike),
                source = if (deckIndex != null) AnalyticsEvent.SwipeSource.DECK else AnalyticsEvent.SwipeSource.LIKES,
                withOpener = opener != null, premium = isPremium, likesLeft = likesLeft, deckSize = queue.size,
            ),
        )
        queue = queue.filterNot { it.id == profile.id }
        likedMe = likedMe.filterNot { it.id == profile.id }
        discovery.swiped += profile.id
        history = history + Swiped(profile, liked, superLike, Instant.now(), fromLikes = deckIndex == null)
        if (superLike) {
            superLikes = maxOf(0, superLikes - 1)
        } else if (liked && !isPremium) {
            likesLeft?.let { likesLeft = maxOf(0, it - 1) }
        }
        saveDeck(filters)
        discovery.pace.swiped(at = clock.seconds())
        if (queue.size <= discovery.pace.lowWater && !discovery.exhausted) loadDeck(DeckLoad.REFRESH, bySwipe = true)
        // The last card went while the next batch is on its way: that's loading, not "no one new".
        if (queue.isEmpty() && discovery.load != null) deckState = DeckState.Loading

        val target = profile.id
        val state = discovery
        state.pendingSwipes += 1
        enqueue {
            try {
                val data = backend.rpc("swipe", swipeBody(target, liked, superLike, opener))
                val result = data.parseJsonOrNull().asObject
                val id = result?.get("matchId").asString
                if (result?.get("matched").asBoolean == true && id != null) matched(profile, matchID = id.lowercase())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                swipeFailed(Swiped(profile, liked, superLike, Instant.now()), deckIndex, likesIndex, e)
            } finally {
                state.pendingSwipes = maxOf(0, state.pendingSwipes - 1)
            }
            if (liked && !superLike) loadLikesLeft()
        }
    }

    /** The swipe made a match: the match moment, the chat, and the matches from the server. */
    private fun matched(profile: Profile, matchID: String) {
        val i = history.indexOfLast { it.profile.id == profile.id }
        if (i >= 0) history = history.toMutableList().also { it[i] = it[i].copy(matched = true) }
        discovery.knownMatches += matchID
        Telemetry.track(AnalyticsEvent.MatchCreated(AnalyticsEvent.MatchSource.MY_SWIPE))
        if (matches.none { it.id == matchID }) matches = listOf(Match(id = matchID, profile = profile, matchedAt = Instant.now())) + matches
        ensureConversations()
        Haptics.success()
        matchScreen = profile
        scope.launch { loadMatches() }
    }

    private fun swipeFailed(swipe: Swiped, deckIndex: Int?, likesIndex: Int?, error: Exception) {
        val profile = swipe.profile
        val code = ServerMessage.code(error)
        Telemetry.track(AnalyticsEvent.SwipeRefused(swipeAction(swipe.liked, swipe.superLike), Telemetry.reason(error)))
        if (code == "daily_like_limit") Telemetry.track(AnalyticsEvent.DailyLikeLimitReached())
        Telemetry.unexpected(error, "discover", "swipe")
        history = history.filterNot { it.profile.id == profile.id }
        // Not available any more (or swiped on another device): it stays gone, quietly.
        if (code == "not_eligible" || code == "not_found" || code == "already_swiped") return
        // Put it back as it was.
        discovery.swiped -= profile.id
        if (deckIndex != null && queue.none { it.id == profile.id }) {
            queue = queue.toMutableList().also { it.add(minOf(deckIndex, it.size), profile) }
        }
        if (likesIndex != null && likedMe.none { it.id == profile.id }) {
            likedMe = likedMe.toMutableList().also { it.add(minOf(likesIndex, it.size), profile) }
        }
        if (swipe.superLike) {
            superLikes += 1
        } else if (swipe.liked && !isPremium) {
            likesLeft?.let { likesLeft = it + 1 }
        }
        when (code) {
            "daily_like_limit" -> likesLeft = 0
            "no_super_likes" -> superLikes = 0
        }
        saveDeck(filters)
        scope.launch { loadWallet() }
        // Paused or on hold: the lock or the hold screen already says it.
        if (code == "paused" || code == "moderated") return
        Haptics.warning()
        say(error)
    }

    /** Brings the last swipe back (the server undoes its last one, within 10 minutes, without a match). */
    fun undo() {
        if (profilePaused || !canUndo) return
        val last = history.lastOrNull() ?: return
        history = history.dropLast(1)
        Telemetry.track(AnalyticsEvent.SwipeUndone(swipeAction(last.liked, last.superLike)))
        discovery.swiped -= last.profile.id
        if (last.fromLikes) likedMe = listOf(last.profile) + likedMe else queue = listOf(last.profile) + queue
        if (last.superLike) {
            superLikes += 1
        } else if (last.liked && !isPremium) {
            likesLeft?.let { likesLeft = it + 1 }
        }
        Haptics.tap()
        enqueue {
            try {
                backend.rpc("undo_last_swipe")
                saveDeck(filters)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The server still has the swipe: the card goes again.
                discovery.swiped += last.profile.id
                queue = queue.filterNot { it.id == last.profile.id }
                likedMe = likedMe.filterNot { it.id == last.profile.id }
                val code = ServerMessage.code(e)
                Telemetry.unexpected(e, "discover", "undo")
                if (code != "paused" && code != "moderated") {
                    Haptics.warning()
                    say(e)
                }
            }
            loadWallet()
            if (last.liked && !last.superLike) loadLikesLeft()
        }
    }

    /** Swipes and undos reach the server one after the other, in the order they were made. */
    private fun enqueue(work: suspend () -> Unit) {
        val previous = discovery.chain
        val session = sessionID
        discovery.chain = scope.launch {
            previous?.join()
            if (session != sessionID) return@launch
            work()
        }
    }

    /** A refusal or failure, above the tabs, in the person's language. */
    fun say(error: Throwable) {
        val text = ServerMessage.text(error, offline = L("Couldn't connect. Check your connection and try again."))
        Telemetry.breadcrumb("ui", "notice shown", Telemetry.Level.WARNING, mapOf("reason" to Telemetry.reason(error)))
        notice = Notice(text = text)
    }

    // Likes left

    /** Likes left today, from the server (`likes_left`). Unchanged if it can't be read, or if a newer read already landed. */
    suspend fun loadLikesLeft() {
        if (phase != Phase.MAIN) return
        val read = startRead(DiscoveryFreshness.Part.LIKES_LEFT)
        val data = attempt { backend.rpc("likes_left") }
        val row = data?.let {
            attemptOrNull {
                val o = it.parseJsonOrNull().asObject ?: return@attemptOrNull null
                o.boolean("unlimited") to o.int("left")
            }
        }
        if (!readLanded(read, ok = row != null) || row == null) return
        likesLeft = if (row.first) null else row.second
    }

    // Boost

    /**
     * 30 minutes at the top of decks nearby (`start_boost`). Shown at once; a refusal puts the boost
     * back and says why. The wallet then says what the server has.
     */
    fun startBoost() {
        if (profilePaused || boosts <= 0 || isBoosting()) return
        val beforeBoosts = boosts
        val beforeEndsAt = boostEndsAt
        boosts -= 1
        boostEndsAt = Instant.now().plusSeconds(BOOST_DURATION_SECONDS)
        Telemetry.track(AnalyticsEvent.BoostStarted(left = boosts))
        Haptics.success()
        boostBanner = UUID.randomUUID()
        scope.launch {
            try {
                val data = backend.rpc("start_boost")
                data.parseJsonOrNull().asString?.let(::serverDate)?.let { boostEndsAt = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                boosts = beforeBoosts
                boostEndsAt = beforeEndsAt
                boostBanner = null
                Telemetry.track(AnalyticsEvent.BoostFailed(Telemetry.reason(e)))
                Telemetry.unexpected(e, "discover", "boost")
                val code = ServerMessage.code(e)
                if (code != "paused" && code != "moderated") {
                    Haptics.warning()
                    say(e)
                }
            }
            loadWallet()
        }
    }

    // Reset

    /** Signed out: nothing of discovery stays. */
    fun clearDiscovery() {
        discovery.load?.cancel()
        discovery.chain?.cancel()
        discovery = DiscoveryState()
        queue = emptyList()
        deckState = DeckState.Idle
        history = emptyList()
        likedMe = emptyList()
        matches = emptyList()
        likesLoad = ListLoad.Loading
        matchesLoad = ListLoad.Loading
        likesLeft = null
        notice = null
    }

    // endregion

    // region Sessions

    // What the chat and the Sessions tab ask of sessions. The rows live on the server and in
    // `SessionStore` (optimistic, live); the chat's own card is only where the session shows.
    //
    // A proposal also puts its card in the chat at once (taken back if the server refuses). db-events posts
    // the card in the channel (`session-<id>-proposed`, `drafft.sessionId`), which then replaces it: the chat
    // keeps one card per session id (`ChatService`).

    /** Sends an invite in a chat (`chatID` is the match's id). */
    fun proposeSession(proposal: SessionProposal, chatID: String) {
        Haptics.tap()
        Telemetry.track(AnalyticsEvent.SessionProposed(sport = proposal.sport.telemetryID, options = proposal.options.size))
        chat.showPending(proposal, chatID)
        scope.launch {
            if (sessionStore.propose(proposal, chatID)) return@launch
            removeSessionCard(proposal.id, chatID)
        }
    }

    /** Other times for an invite: the old card turns to "Other times suggested", the new one follows. */
    fun counterSession(sessionID: UUID, chatID: String, proposal: SessionProposal) {
        Haptics.tap()
        Telemetry.track(AnalyticsEvent.SessionCountered(options = proposal.options.size))
        chat.showPending(proposal, chatID)
        scope.launch {
            if (sessionStore.counter(sessionID, proposal)) return@launch
            removeSessionCard(proposal.id, chatID)
        }
    }

    /** Accept one of the proposed times, or decline. */
    fun respondToSession(sessionID: UUID, accept: Boolean, pick: Instant? = null) {
        if (accept) Haptics.success() else Haptics.tap()
        Telemetry.track(AnalyticsEvent.SessionResponded(if (accept) AnalyticsEvent.SessionResponse.ACCEPTED else AnalyticsEvent.SessionResponse.DECLINED))
        scope.launch { sessionStore.respond(sessionID, accept, pick) }
    }

    /** Calls a pending or confirmed session off, for both people. */
    fun cancelSession(sessionID: UUID) {
        Haptics.tap()
        Telemetry.track(AnalyticsEvent.SessionCancelled())
        scope.launch { sessionStore.cancel(sessionID) }
    }

    private fun removeSessionCard(sessionID: UUID, chatID: String) {
        chat.dropPending(sessionID, chatID)
    }

    // endregion

    // region Wallet: drafft tempo, boosts and super likes, as the server has them.

    fun balance(of: Consumable): Int = when (of) {
        Consumable.BOOST -> boosts
        Consumable.SUPER_LIKE -> superLikes
    }

    private class WalletRow(val boosts: Int, val superLikes: Int, val premiumUntil: String?, val boostEndsAt: String?) {
        companion object {
            fun from(o: JsonObject?): WalletRow? = o?.let {
                attemptOrNull { WalletRow(it.int("boosts"), it.int("super_likes"), it.optString("premium_until"), it.optString("boost_ends_at")) }
            }
        }
    }

    /**
     * Reads the account's own wallet row (RLS: only its own). At sign-in, on each Realtime
     * (re)connection and `wallet` event, back at the front, and while a purchase waits for its
     * credit (`PurchaseCredit`). A failed read changes nothing (offline: the last known balances
     * stay).
     */
    suspend fun loadWallet() {
        if (!backend.hasSession()) return
        val data = attempt { backend.select("wallets?select=boosts,super_likes,premium_until,boost_ends_at") } ?: return
        val row = WalletRow.from(data.parseJsonOrNull().asArray?.firstOrNull().asObject) ?: return
        apply(row)
        purchaseCredit.walletChanged()
    }

    /**
     * A wallet the server sent back (`purchase-sync`): the row itself, or under `wallet`. Whether
     * it could be read.
     */
    fun applyWallet(data: ByteArray): Boolean {
        val json = data.parseJsonOrNull()
        val row = WalletRow.from(json.asObject)
            ?: WalletRow.from(json.asObject?.get("wallet").asObject)
            ?: WalletRow.from(json.asArray?.firstOrNull().asObject)
            ?: return false
        apply(row)
        return true
    }

    private fun apply(row: WalletRow) {
        boosts = row.boosts
        superLikes = row.superLikes
        premiumUntil = serverDate(row.premiumUntil)
        // A boost started on another device: the one running furthest wins.
        val end = serverDate(row.boostEndsAt)
        if (end != null && end.isAfter(boostEndsAt ?: Instant.MIN)) boostEndsAt = end
    }

    /** Signed out or deleted: nothing of the account's wallet stays on screen. */
    fun clearWallet() {
        purchaseCredit.forget()
        subscription = null
        premiumUntil = null
        superLikes = 0
        boosts = 0
        boostEndsAt = null
        blurredLikes = emptyList()
    }

    // endregion

    // region Pause

    // The pause switch against the server: sent when the person flips it, read back when signing in,
    // and forced on when the server refuses an action because the profile is paused.

    /** The person flipped the switch: send it (the server's own state isn't sent back). */
    private fun pauseChanged(from: Boolean) {
        // Resumed by the server (another device, a lifted hold): discovery reads the deck again. A
        // flip on this phone does it once saved (`syncPause`).
        if (from && !profilePaused && pauseFromServer) refreshDiscovery(DiscoveryFreshness.Moment.ENTERED)
        if (profilePaused == from || pauseFromServer) return
        val paused = profilePaused
        pauseEdits += 1
        val edit = pauseEdits
        pauseSave = scope.async { syncPause(paused, edit) }
    }

    /** Pauses and waits for the server: null once it has it, else what to say (the switch is back). */
    suspend fun pauseNow(): String? {
        profilePaused = true
        return pauseSave?.await()
    }

    /**
     * The server says the profile is paused (or not): shown as is, never sent back. `readAt` is
     * `pauseEdits` when the read began: a flip made since, or still being saved, wins over it.
     */
    fun applyServerPause(paused: Boolean, readAt: Int? = null) {
        if (readAt != null && (readAt != pauseEdits || pauseSaves > 0)) return
        pauseFromServer = true
        profilePaused = paused
        pauseFromServer = false
    }

    /**
     * A request was refused because the profile is paused. While a flip is being saved the refusal
     * may predate it, and the save's own outcome decides.
     */
    fun serverRefusedPaused() {
        if (pauseSaves > 0) return
        applyServerPause(true)
    }

    /**
     * Sends the switch to the server; if it can't be saved (signed out included), the switch goes
     * back to the server's state and a notice says so. Null once saved, else that notice's text.
     */
    suspend fun syncPause(paused: Boolean, edit: Int): String? {
        pauseSaves += 1
        try {
            backend.updateMyProfile(jsonOf("paused" to paused))
            Telemetry.track(AnalyticsEvent.ProfilePaused(paused))
            // Resumed: discovery reads the deck again (nothing was read while paused). Only once the
            // server has it: asked sooner, it answers "paused" and the pause came back on.
            if (!paused && edit == pauseEdits) refreshDiscovery(DiscoveryFreshness.Moment.ENTERED)
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A later flip is on its way: it decides.
            if (edit != pauseEdits) return null
            Telemetry.unexpected(e, "account", "pause")
            Haptics.warning()
            applyServerPause(!paused)
            val offline = generateSequence<Throwable>(e) { it.cause }.any { it is java.io.IOException }
            val text = when {
                !offline -> ServerMessage.text(e) ?: ServerMessage.generic
                paused -> L("Your profile couldn't be paused. Check your connection and try again.")
                else -> L("Your profile couldn't be resumed. Check your connection and try again.")
            }
            notice = Notice(text = text)
            return text
        } finally {
            pauseSaves -= 1
        }
    }

    /** The saved pause, read back when signing in on this device. */
    suspend fun loadPause() {
        val edits = pauseEdits
        val data = attempt { backend.myProfile(select = "paused") } ?: return
        val paused = data.parseJsonOrNull().asArray?.firstOrNull().asObject?.get("paused").asBoolean ?: return
        applyServerPause(paused, readAt = edits)
    }

    // endregion

    // region Safety: blocking and unblocking, at once on the device, then on the
    // server, through an outbox kept on this phone (SafetyOutbox) until the server has it.

    private val safetyOutbox = SafetyOutbox(defaults)
    /** Blocks and unblocks on their way to the server: the send running, the next try. */
    private var safetySending: Job? = null
    private var safetyRetry: Job? = null
    private var safetyAttempts = 0

    /**
     * Block (a report blocks too): they leave your deck, your likes and your chats, and undo
     * can't bring them back.
     */
    fun block(profile: Profile) {
        if (blocked.any { it.id == profile.id }) return
        Telemetry.track(AnalyticsEvent.UserBlocked())
        hide(profile)
        queueSafety(SafetyOutbox.Action.BLOCK, profile)
    }

    /**
     * Unblocking lets them back into Discover (the server forgets the old swipe; they come with a
     * next batch); the old chat doesn't come back.
     */
    fun unblock(profile: Profile) {
        Telemetry.track(AnalyticsEvent.UserUnblocked())
        blocked = blocked.filterNot { it.id == profile.id }
        discovery.swiped -= profile.id
        queueSafety(SafetyOutbox.Action.UNBLOCK, profile)
    }

    /**
     * The blocked list as the server has it, with what this phone hasn't sent yet on top: Blocked people
     * shows it after a relaunch or on another device too. Unchanged if it can't be read.
     */
    suspend fun loadBlocked() {
        val session = sessionID
        val user = backend.userID ?: return
        val server = attempt { safety.blockedPeople() } ?: return
        if (session != sessionID) return
        val pending = safetyOutbox.pending(user)
        val listedIDs = server.map { it.id }.toSet()
        val waiting = pending.filter { it.value.action == SafetyOutbox.Action.BLOCK && it.key !in listedIDs }
            .map { (id, entry) -> blocked.firstOrNull { it.id == id } ?: Safety.blockedProfile(id, entry.name) }
        val listed = server.filter { pending[it.id]?.action != SafetyOutbox.Action.UNBLOCK }
            .map { row -> blocked.firstOrNull { it.id == row.id } ?: row }
        val list = waiting + listed
        for (person in list) if (blocked.none { it.id == person.id }) hide(person)
        blocked = list
    }

    /**
     * Sends what this phone hasn't sent yet. A failure the server may get over (offline, a server error)
     * waits and tries again; [announce] says so once, for an action just taken. Signed in, at launch and
     * back at the front: whatever was left goes too. One send at a time: a block then an unblock reach
     * the server in that order.
     */
    suspend fun sendPendingSafety(announce: Boolean = false) {
        val previous = safetySending
        val job = scope.launch {
            previous?.join()
            flushSafety(announce)
        }
        safetySending = job
        job.join()
    }

    private suspend fun flushSafety(announce: Boolean) {
        val user = backend.userID ?: return
        for ((id, entry) in safetyOutbox.pending(user)) {
            // Signed out (or into another account) meanwhile: the rest waits for this account's sign-in,
            // never sent with someone else's session.
            if (backend.userID != user) return
            try {
                safety.send(entry.action, id)
                safetyOutbox.remove(entry, id, user)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (Safety.isFinal(e)) {
                    safetyLog.warning("${entry.action} refused: $e")
                    // Reported before the entry goes (a refusal the server meant stays a breadcrumb, a contract bug alerts).
                    Telemetry.unexpected(e, "safety", entry.action.name.lowercase())
                    safetyOutbox.remove(entry, id, user)
                    continue
                }
                // Every retry lands here: Telemetry reports the same failure once per 5 minutes at most.
                Telemetry.unexpected(e, "safety", entry.action.name.lowercase())
                if (announce) {
                    notice = Notice(
                        text = if (entry.action == SafetyOutbox.Action.BLOCK) {
                            L("Couldn't reach drafft. The block goes through as soon as you're back online.")
                        } else {
                            L("Couldn't reach drafft. The unblock goes through as soon as you're back online.")
                        },
                    )
                }
                retrySafety()
                return
            }
        }
        safetyAttempts = 0
    }

    private fun hide(profile: Profile) {
        blocked = listOf(profile) + blocked
        queue = queue.filterNot { it.id == profile.id }
        likedMe = likedMe.filterNot { it.id == profile.id }
        matches = matches.filterNot { it.profile.id == profile.id }
        history = history.filterNot { it.profile.id == profile.id }
        conversations = conversations.filterNot { it.profile.id == profile.id }
        chat.publish()
        if (banner?.profile?.id == profile.id) banner = null
    }

    private fun queueSafety(action: SafetyOutbox.Action, profile: Profile) {
        val user = backend.userID ?: return
        safetyOutbox.add(SafetyOutbox.Entry(action, profile.name), profile.id, user)
        safetyAttempts = 0
        scope.launch { sendPendingSafety(announce = true) }
    }

    /** Tries again after 10 s, 30 s, 1 min, then every 5 min while the app is open. */
    private fun retrySafety() {
        safetyRetry?.cancel()
        val delays = listOf(10, 30, 60, 300)
        val wait = delays[minOf(safetyAttempts, delays.size - 1)]
        safetyAttempts += 1
        val session = sessionID
        safetyRetry = scope.launch {
            delay(wait * 1000L)
            if (session == sessionID) sendPendingSafety()
        }
    }

    // endregion

    /** A shared read's value; null if it was cancelled under this caller (signed out), who carries on. */
    private suspend fun <T> Deferred<T?>.awaitOrNull(): T? = try {
        await()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        null
    }

    companion object {
        private val safetyLog = java.util.logging.Logger.getLogger("so.drafft.safety")
        private val accountLog = java.util.logging.Logger.getLogger("so.drafft.account")

        /** The language picked last, read at launch before the first screen (`DrafftApplication`). */
        const val LANGUAGE_KEY = "appLanguage"
        /** No one yet: what `me` holds before the server's profile is read, and after signing out. */
        val nobody = Profile(
            id = "me", name = "", age = 18, neighborhood = "", distanceKm = 0.0, portrait = "", photos = emptyList(),
            sports = emptyList(), voiceIntro = null, voiceDuration = 0.0, icebreaker = Icebreaker.Kind.TWO_TRUTHS.blank,
            favoriteSpot = "", bio = "", goal = "", vitalsOverride = Vitals.blank, promptsOverride = emptyList(),
        )

        /** Free accounts get this many likes in any 24 hours (the server's limit); drafft tempo is unlimited. */
        const val DAILY_LIKES = 20
        const val BOOST_DURATION_SECONDS: Long = 30 * 60
        const val UNDO_WINDOW_SECONDS: Long = 10 * 60

        /** How long the splash waits for the server on an account's first launch on this phone. */
        val FIRST_READ_LIMIT = 3.seconds

        /** A read this recent is reused instead of asking again (sign-in reads it, then the screens it opens ask too). */
        private const val FRESH_FOR_SECONDS: Long = 5

        /** Likes read at a time: the Likes tab shows them all. */
        private const val LIKES_PAGE = 100

        /** New cards per read. */
        const val DECK_BATCH = DeckPace.BATCH

        /** The most `discover` returns at once. */
        private const val DECK_MAX_READ = 50

        /** Cards on screen (the stack shows 4) that a fresh batch doesn't reorder. */
        private const val DECK_KEEP = 4

        private fun swipeAction(liked: Boolean, superLike: Boolean) = when {
            superLike -> AnalyticsEvent.SwipeAction.SUPER_LIKE
            liked -> AnalyticsEvent.SwipeAction.LIKE
            else -> AnalyticsEvent.SwipeAction.PASS
        }

        /** Postgres timestamps (`2026-10-24T10:00:00.123456+00:00`), to the second. */
        fun serverDate(text: String?): Instant? {
            text ?: return null
            val whole = text.replace(Regex("""\.\d+"""), "")
            return attemptOrNull { OffsetDateTime.parse(whole).toInstant() }
        }

        /**
         * `swipe`'s parameters. A super like's text is its note (140 characters); any other opener is
         * the first message.
         */
        private fun swipeBody(target: String, liked: Boolean, superLike: Boolean, opener: MessageContent?): JsonObject {
            val body = linkedMapOf<String, Any?>("p_target" to target, "p_action" to if (superLike) "superlike" else if (liked) "like" else "pass")
            if (liked) {
                if (superLike && opener is MessageContent.Text) {
                    body["p_note"] = opener.text.take(140)
                } else {
                    opener?.opener?.let { body["p_opener"] = it }
                }
            }
            return jsonOf(*body.toList().toTypedArray())
        }
    }
}
