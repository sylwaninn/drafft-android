package so.drafft.core.data.backend

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import so.drafft.core.data.platform.KeyValueStore

/**
 * Where Supabase Auth keeps the session between launches: the
 * app's [KeyValueStore], as JSON. The SDK loads it at start and refreshes it by itself.
 */
class KeyValueSessionManager(
    private val store: KeyValueStore,
    private val key: String = "supabase.session",
) : SessionManager {
    override suspend fun saveSession(session: UserSession) {
        store.putString(key, DrafftJson.encodeToString(UserSession.serializer(), session))
    }

    override suspend fun loadSession(): UserSession? =
        store.getString(key)?.let { runCatching { DrafftJson.decodeFromString(UserSession.serializer(), it) }.getOrNull() }

    override suspend fun deleteSession() {
        store.remove(key)
    }
}
