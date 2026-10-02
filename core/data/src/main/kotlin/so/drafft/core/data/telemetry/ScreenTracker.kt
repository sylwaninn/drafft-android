package so.drafft.core.data.telemetry

/**
 * Which screen is on show, for [Telemetry.screen]. The root sets the [base] (welcome, sign-up, the
 * current tab); a pushed screen, a sheet or a cover [enter]s on top while it's on screen and [leave]s
 * when it goes. The newest one still there is the screen on show. Main thread only.
 *
 * What changes is published once per UI pass, after it: on a tab switch the old tab's screens [leave]
 * while the base is still the old tab, and a publish at that moment would send the old tab as a screen
 * nobody opens. [post] defers the publish past the pass (the main looper on Android), and the one that
 * runs reads the state as it is by then.
 */
object ScreenTracker {
    private var base: Screen? = null
    private var baseProperties: Map<String, Any> = emptyMap()
    private val layers = mutableListOf<Layer>()
    private var nextToken = 0L
    private var scheduled = false

    /**
     * Runs a block after the current UI pass. At once by default (unit tests, which swap in their own to
     * hold it); the app installs the main looper's at launch (`AndroidTelemetry`).
     */
    var post: (Runnable) -> Unit = { it.run() }

    private class Layer(val token: Long, val screen: Screen, val properties: Map<String, Any>)

    fun base(screen: Screen, properties: Map<String, Any> = emptyMap()) {
        base = screen
        baseProperties = properties
        // A screen on top of it is the one on show: nothing changes for the person.
        if (layers.isEmpty()) publish()
    }

    /** A screen comes on top. Keep the token for [leave]. */
    fun enter(screen: Screen, properties: Map<String, Any> = emptyMap()): Long {
        val token = nextToken++
        layers += Layer(token, screen, properties)
        publish()
        return token
    }

    fun leave(token: Long) {
        if (layers.removeAll { it.token == token }) publish()
    }

    val current: Screen? get() = layers.lastOrNull()?.screen ?: base

    /** Where something happens, as a code for an event (`onboarding`, `phone_verification`...). */
    val currentID: String get() = current?.id ?: "unknown"

    /** One publish per pass, however many changes it holds. */
    private fun publish() {
        if (scheduled) return
        scheduled = true
        post(Runnable { flush() })
    }

    /** Publishes the screen on show now (a repeat of the one already published is ignored by [Telemetry]). */
    private fun flush() {
        scheduled = false
        val top = layers.lastOrNull()
        val screen = top?.screen ?: base ?: return
        Telemetry.screen(screen, top?.properties ?: baseProperties)
    }

    /** Unit tests. */
    fun reset() {
        base = null
        baseProperties = emptyMap()
        layers.clear()
        scheduled = false
        post = { it.run() }
    }
}
