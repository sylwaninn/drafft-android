package so.drafft.core.data.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.lang.ref.WeakReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import so.drafft.core.data.AppModel
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.platform.KeyValueStore

/**
 * Ports Drafft/Services/PurchaseCredit.swift.
 *
 * Purchases the store confirmed that the server hasn't credited yet.
 *
 * Right after Google Play confirms, the app asks the backend to credit it at once (`purchase-sync`,
 * which reads the purchase from RevenueCat and returns the wallet and whether that transaction is
 * credited); the webhook does the same on its own. The usual case takes a second or two and the purchase
 * screen shows its confirmation. Past `buttonWait`, the button is free again and a banner at the top
 * says the purchase is being added; it never says the payment went through.
 *
 * Each pending purchase is kept per account (transaction, product, date) until the wallet has it, so a
 * relaunch, a return to the front or a reconnection asks again, with a backoff. Signing out or deleting
 * the account forgets them.
 */
class PurchaseCredit(
    private val backend: Backend,
    private val store: Store,
    private val defaults: KeyValueStore,
    private val scope: CoroutineScope,
) {
    @Serializable
    data class Pending(
        val transactionID: String?,
        val productID: String,
        /** Epoch milliseconds. */
        val date: Long,
        val target: Target,
    ) {
        /** What the wallet shows once it's credited. */
        @Serializable
        sealed interface Target {
            @Serializable @SerialName("boosts")
            data class Boosts(val atLeast: Int) : Target

            @Serializable @SerialName("superLikes")
            data class SuperLikes(val atLeast: Int) : Target

            @Serializable @SerialName("tempo")
            data object Tempo : Target
        }

        fun isCredited(app: AppModel): Boolean = when (target) {
            is Target.Boosts -> app.boosts >= target.atLeast
            is Target.SuperLikes -> app.superLikes >= target.atLeast
            Target.Tempo -> app.isPremium
        }
    }

    enum class Banner { ADDING, CREDITED }

    /** Oldest first. */
    var pending: List<Pending> by mutableStateOf(emptyList())
        private set

    /** What the top banner shows, if anything. */
    var banner: Banner? by mutableStateOf(null)
        private set

    /** Swiped away: it shows again at the next launch only. */
    private var dismissedThisLaunch = false
    private var userID: String? = null
    private var app: WeakReference<AppModel>? = null
    private var sync: Job? = null
    private var hideCredited: Job? = null

    private val _supportRequests = MutableSharedFlow<Pending?>(extraBufferCapacity = 1)

    /**
     * "Contact us" on the banner: the root opens "Get help" above whatever is on screen, filled in for
     * this pending purchase (topic `Purchase`, the store's reference). The iPhone's `PurchaseHelpPresenter`.
     */
    val supportRequests: SharedFlow<Pending?> = _supportRequests.asSharedFlow()

    val oldest: Pending? get() = pending.firstOrNull()

    fun contactSupport() {
        if (app?.get() == null) return
        _supportRequests.tryEmit(oldest)
    }

    // Purchase screens

    /**
     * The store confirmed [purchase]: asks the server to credit it and waits up to 8 s. Whether it's on
     * the account (the screen then shows its confirmation); if not, the banner takes over and the screen
     * frees its button.
     */
    suspend fun confirmed(purchase: Pending, app: AppModel): Boolean {
        attach(app, store.linkedUserID)
        if (purchase.isCredited(app)) return true
        pending = pending + purchase
        save()
        runSync()
        val deadline = TimeSource.Monotonic.markNow() + BUTTON_WAIT
        while (deadline.hasNotPassedNow()) {
            if (purchase !in pending) return true
            delay(250.milliseconds)
        }
        if (purchase !in pending) return true
        // A new purchase shows the banner, even if an earlier one was swiped away.
        dismissedThisLaunch = false
        show(Banner.ADDING)
        return false
    }

    // Lifecycle

    /**
     * Launch, return to the front, Realtime reconnection: asks again for anything still pending on this
     * account, and shows the banner unless it was swiped away since launch.
     */
    fun resume(app: AppModel) {
        val id = backend.userID?.toString()?.lowercase() ?: return
        attach(app, id)
        if (pending.isEmpty()) return
        if (!dismissedThisLaunch) show(Banner.ADDING)
        runSync()
    }

    /** The wallet was read again (`AppModel.loadWallet`): drops whatever it now holds. */
    fun walletChanged() {
        val app = app?.get() ?: return
        if (pending.isEmpty()) return
        val before = pending.size
        pending = pending.filterNot { it.isCredited(app) }
        if (pending.size == before) return
        save()
        if (pending.isEmpty()) {
            sync?.cancel()
            // A purchase screen still waiting shows its own confirmation; otherwise the banner does.
            if (banner == Banner.ADDING) show(Banner.CREDITED)
        }
    }

    fun dismissBanner() {
        dismissedThisLaunch = true
        hideCredited?.cancel()
        banner = null
    }

    /** Sign-out or account deletion: nothing of the account stays on this device. */
    fun forget() {
        sync?.cancel()
        hideCredited?.cancel()
        userID?.let { defaults.remove(key(it)) }
        userID = null
        pending = emptyList()
        banner = null
        dismissedThisLaunch = false
    }

    // Private

    private fun attach(app: AppModel, id: String?) {
        this.app = WeakReference(app)
        if (id == null || id == userID) return
        sync?.cancel()
        userID = id
        pending = load(id)
        banner = null
    }

    private fun show(state: Banner) {
        hideCredited?.cancel()
        banner = state
        if (state != Banner.CREDITED) return
        Haptics.success()
        hideCredited = scope.launch {
            delay(4.seconds)
            if (banner == Banner.CREDITED) banner = null
        }
    }

    /** Asks now, then after 2 s, 5 s, 15 s, 60 s, then every 5 minutes, until nothing is pending. */
    private fun runSync() {
        sync?.cancel()
        sync = scope.launch {
            var step = 0
            while (isActive) {
                if (pending.isEmpty()) return@launch
                val atLeast = syncOnce()
                if (!isActive || pending.isEmpty()) return@launch
                var wait = if (step < BACKOFF.size) BACKOFF[step] else STEADY_RETRY
                if (atLeast != null) wait = maxOf(wait, atLeast)
                step += 1
                delay(wait)
            }
        }
    }

    /** One `purchase-sync` call. The shortest wait before the next one, when the server asked for it. */
    private suspend fun syncOnce(): Duration? {
        val app = app?.get() ?: return null
        val purchase = pending.firstOrNull() ?: return null
        val body = JsonObject(buildMap { purchase.transactionID?.let { put("transaction_id", JsonPrimitive(it)) } })
        return try {
            val data = backend.function("purchase-sync", body)
            // The wallet it returns; a shape it doesn't know reads the row instead.
            if (!app.applyWallet(data)) app.loadWallet()
            // The server says whether this transaction is credited: that answer wins. Without it (an older
            // server, no transaction id), the balances tell.
            val status = purchase.transactionID?.let { status(it, data) }
            if (status != null) {
                if (status == ServerStatus.CREDITED) markCredited(purchase)
            } else {
                walletChanged()
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Backend.BackendError.Http) {
            app.loadWallet()
            // Too many asks, or the store is slow to answer: the webhook credits it meanwhile.
            if (e.status == 429 || e.status == 503) THROTTLED_RETRY else null
        } catch (e: Exception) {
            app.loadWallet()
            null
        }
    }

    private enum class ServerStatus { CREDITED, NOT_YET }

    @Serializable
    private data class Answer(val transaction: Transaction? = null) {
        @Serializable
        data class Transaction(val id: String, val credited: Boolean)
    }

    /**
     * `purchase-sync` answers `{ wallet, transaction: { id, credited } }`: whether [id] is credited, or
     * null when the answer doesn't say (no `transaction`, another id).
     */
    private fun status(id: String, data: ByteArray): ServerStatus? {
        val transaction = runCatching { json.decodeFromString(Answer.serializer(), data.decodeToString()) }
            .getOrNull()?.transaction ?: return null
        if (transaction.id != id) return null
        return if (transaction.credited) ServerStatus.CREDITED else ServerStatus.NOT_YET
    }

    /** The server credited [purchase]: it leaves the list, whatever the balances say. */
    private fun markCredited(purchase: Pending) {
        pending = pending - purchase
        walletChanged()
        save()
        if (pending.isEmpty()) {
            sync?.cancel()
            if (banner == Banner.ADDING) show(Banner.CREDITED)
        }
    }

    private fun load(userID: String): List<Pending> = runCatching {
        defaults.getString(key(userID))?.let { json.decodeFromString(listSerializer, it) } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun save() {
        val id = userID ?: return
        if (pending.isEmpty()) {
            defaults.remove(key(id))
        } else {
            defaults.putString(key(id), json.encodeToString(listSerializer, pending))
        }
    }

    companion object {
        /** Past this, the banner offers to contact support. */
        val slowAfter: Duration = 10.minutes

        private val BUTTON_WAIT = 8.seconds
        private val BACKOFF = listOf(2.seconds, 5.seconds, 15.seconds, 60.seconds)
        private val STEADY_RETRY = 5.minutes

        /** Throttled or down: the webhook will credit it, ask again no sooner than this. */
        private val THROTTLED_RETRY = 60.seconds

        private val json = Json { ignoreUnknownKeys = true }
        private val listSerializer = ListSerializer(Pending.serializer())

        private fun key(userID: String) = "purchaseCredit.pending.$userID"
    }
}
