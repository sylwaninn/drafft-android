package so.drafft.core.data.platform

/**
 * The connection coming back: what waited for the
 * server while offline is read again then.
 */
fun interface NetworkMonitor {
    /** Calls [action] each time the device gets a working connection again (any thread). */
    fun onAvailable(action: () -> Unit)
}
