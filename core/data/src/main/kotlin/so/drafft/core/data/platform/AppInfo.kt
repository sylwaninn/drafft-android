package so.drafft.core.data.platform

import java.io.File

/** What the app knows about itself and the device. */
interface AppInfo {
    /** "0.1.0" (`versionName`). */
    val version: String

    /** The build number (`versionCode`). */
    val build: String

    /** "" for production, "staging" or "local" (the build flavor's `ENVIRONMENT`). */
    val environment: String

    /** A debug build (installed from the IDE): development push and device attestation environments. */
    val isDebugBuild: Boolean

    /** The hardware model ("Pixel 9"), not a marketing name. */
    val model: String

    /** The OS version ("15"). */
    val osVersion: String

    /** A stable identifier of this app on this device, if any. */
    val installID: String?

    /** Kept across launches, not shown to people (`Context.filesDir`). */
    val filesDir: File

    /** Kept across launches and never backed up. */
    val noBackupDir: File

    /** Temporary files the system may remove. */
    val cacheDir: File
}

/** Whether the app is on screen right now (an activity is resumed). */
fun interface AppLifecycle {
    fun isActive(): Boolean
}

/**
 * Seconds on a monotonic clock that keeps counting while the phone sleeps: an evening in the
 * background ages what's on screen. Only differences mean anything.
 */
fun interface MonotonicClock {
    fun seconds(): Double
}

/** Stops whatever audio is playing, bound by the audio service. */
fun interface PlaybackControl {
    fun stop()
}

/**
 * A device attestation token for the server (Play Integrity on Android). Null when the device can't give
 * one. [requestHash] binds the token to the account: the server compares it with its own.
 */
interface DeviceIntegrityProvider {
    val isSupported: Boolean
    suspend fun token(requestHash: String): String?
}

/**
 * What the system reports about the app's past runs (the process exit reasons). Each payload comes
 * with its kind (`metrics` or `diagnostics`), its JSON and a one-line summary for the log.
 */
interface DiagnosticsSource {
    fun start(onPayload: (kind: String, json: String, summary: String, isProblem: Boolean) -> Unit)
}

/**
 * Real returns to the app from the background (not a system dialog or the app switcher making it
 * inactive for a moment): the root reads what changed while away on each one.
 */
interface ForegroundReturns {
    val returns: kotlinx.coroutines.flow.Flow<Unit>

    /** The app going to the background (a chat keeps where the person was reading). */
    val leaves: kotlinx.coroutines.flow.Flow<Unit> get() = kotlinx.coroutines.flow.emptyFlow()
}
