package so.drafft.core.data.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * [NetworkMonitor] on the system's default network: a connection counts once the system has checked it
 * reaches the internet (validated), so a captive portal or a network still coming up isn't taken as
 * back online.
 */
class AndroidNetworkMonitor(private val context: Context) : NetworkMonitor {
    override fun onAvailable(action: () -> Unit) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            /** The default network last seen validated: a change of capabilities on it isn't a return. */
            private var validated: Network? = null

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!usable) {
                    if (validated == network) validated = null
                    return
                }
                if (validated == network) return
                validated = network
                action()
            }

            override fun onLost(network: Network) {
                if (validated == network) validated = null
            }
        })
    }
}
