package so.drafft.core.data.platform

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Play Integrity, "standard" requests: the first call warms a token provider up with the Google Cloud
 * project linked in Play Console, the next ones only ask for a token. The server decodes it with Google
 * and keeps the device's Device recall bits (`device-check`).
 *
 * A failure (no Play services, an emulator, a build not from Play, no network) throws: the caller sends
 * nothing and the next launch tries again. A provider that failed is dropped, so the next call warms a new one.
 */
class PlayDeviceIntegrity(context: Context, private val cloudProjectNumber: Long) : DeviceIntegrityProvider {
    private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
    private val lock = Mutex()
    private var provider: StandardIntegrityTokenProvider? = null

    override val isSupported: Boolean = true

    override suspend fun token(requestHash: String): String? {
        val tokenProvider = lock.withLock {
            provider ?: manager.prepareIntegrityToken(
                PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProjectNumber).build(),
            ).await().also { provider = it }
        }
        return try {
            tokenProvider.request(StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()).await().token()
        } catch (error: Exception) {
            lock.withLock { if (provider === tokenProvider) provider = null }
            throw error
        }
    }
}

/** Used when the build has no Play project number (local builds): nothing is attested, nothing is sent. */
object UnsupportedDeviceIntegrity : DeviceIntegrityProvider {
    override val isSupported: Boolean = false
    override suspend fun token(requestHash: String): String? = null
}
