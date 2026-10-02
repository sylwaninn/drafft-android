package so.drafft.core.data.platform

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import so.drafft.core.data.telemetry.Telemetry

/**
 * Play Integrity, "standard" requests: the first call warms a token provider up with the Google Cloud
 * project linked in Play Console, the next ones only ask for a token. The server has Google decode it,
 * refuses it unless the app and the device are recognized, then reads and writes the device's Device
 * recall bits (`device-check`).
 *
 * A failure (no Play services, no network, a project Google refuses) throws: the caller sends nothing and the
 * next opening or sign-in tries again. A build or a device Google doesn't recognize (an emulator, a build
 * installed from the IDE) still gets a token, which the server refuses. A provider Google says expired is
 * dropped and warmed again once; any other failure keeps it. The errors that need a fix (a wrong project
 * number, the daily quota, a request hash too long) are reported; the ones a phone has all the time
 * (offline, an old Play Store) are not.
 */
class PlayDeviceIntegrity(context: Context, private val cloudProjectNumber: Long) : DeviceIntegrityProvider {
    private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
    private val lock = Mutex()
    private var provider: StandardIntegrityTokenProvider? = null

    override suspend fun token(requestHash: String): String? = try {
        requestToken(requestHash, retryWhenExpired = true)
    } catch (error: StandardIntegrityException) {
        if (error.errorCode in NEEDS_A_FIX) {
            Telemetry.unexpected(error, "integrity", "token", mapOf("error_code" to error.errorCode))
        }
        throw error
    }

    private suspend fun requestToken(requestHash: String, retryWhenExpired: Boolean): String {
        val tokenProvider = warmedProvider()
        val request = StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()
        return try {
            tokenProvider.request(request).await().token()
        } catch (error: CancellationException) {
            throw error
        } catch (error: StandardIntegrityException) {
            if (error.errorCode != StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID) throw error
            lock.withLock { if (provider === tokenProvider) provider = null }
            if (!retryWhenExpired) throw error
            requestToken(requestHash, retryWhenExpired = false)
        }
    }

    /** The warmed-up provider, prepared once: callers wait on [lock] while it warms. */
    private suspend fun warmedProvider(): StandardIntegrityTokenProvider = lock.withLock {
        provider ?: manager.prepareIntegrityToken(
            PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProjectNumber).build(),
        ).await().also { provider = it }
    }

    private companion object {
        val NEEDS_A_FIX = setOf(
            StandardIntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID,
            StandardIntegrityErrorCode.TOO_MANY_REQUESTS,
            StandardIntegrityErrorCode.REQUEST_HASH_TOO_LONG,
            StandardIntegrityErrorCode.APP_UID_MISMATCH,
            StandardIntegrityErrorCode.INTERNAL_ERROR,
        )
    }
}

/** Used when the build has no Play project number (local builds): nothing is attested, nothing is sent. */
object UnsupportedDeviceIntegrity : DeviceIntegrityProvider {
    override suspend fun token(requestHash: String): String? = null
}
