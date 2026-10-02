package so.drafft.core.data.platform

import java.util.concurrent.ConcurrentHashMap

/**
 * Small values kept on the phone across launches. Structured values are
 * stored as JSON strings. Implemented on Android by SharedPreferences (`SharedPreferencesKeyValueStore`);
 * [InMemoryKeyValueStore] serves tests.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String): Boolean?
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val values = ConcurrentHashMap<String, Any>()
    override fun getString(key: String): String? = values[key] as? String
    override fun putString(key: String, value: String) { values[key] = value }
    override fun getBoolean(key: String): Boolean? = values[key] as? Boolean
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}
