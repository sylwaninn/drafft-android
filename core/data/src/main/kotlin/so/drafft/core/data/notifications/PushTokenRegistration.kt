package so.drafft.core.data.notifications

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import so.drafft.core.data.platform.KeyValueStore

/**
 * Remembers which device token the server already holds for which account, so a return to the app
 * (which asks FCM for the token again, and gets the same one) doesn't write `push_tokens` every time.
 * The token is sent when it, the account or the push environment changed, and once a day otherwise (the
 * server's copy is refreshed if it was dropped). Forgotten on sign-out.
 */
class PushTokenRegistration(
    private val defaults: KeyValueStore,
    private val key: String = "pushTokenRegistration",
) {
    @Serializable
    data class Record(
        val account: String,
        val token: String,
        val environment: String,
        /** Epoch milliseconds. */
        val sentAt: Long,
    )

    private val record: Record?
        get() = defaults.getString(key)?.let { runCatching { json.decodeFromString<Record>(it) }.getOrNull() }

    fun needsSending(token: String, account: UUID, environment: String, now: Instant = Instant.now()): Boolean {
        val last = record ?: return true
        if (last.token != token || last.account != account.toString() || last.environment != environment) return true
        val age = now.toEpochMilli() - last.sentAt
        return age < 0 || age >= REFRESH_INTERVAL_SECONDS * 1000
    }

    fun markSent(token: String, account: UUID, environment: String, at: Instant = Instant.now()) {
        val new = Record(account.toString(), token, environment, at.toEpochMilli())
        defaults.putString(key, json.encodeToString(Record.serializer(), new))
    }

    fun forget() = defaults.remove(key)

    companion object {
        /** How long a sent token is trusted before it's sent again anyway (seconds). */
        const val REFRESH_INTERVAL_SECONDS: Long = 24 * 60 * 60
        private val json = Json { ignoreUnknownKeys = true }
    }
}
