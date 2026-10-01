package so.drafft.core.data.telemetry

/**
 * Which screen is on show, for [Telemetry.screen]. The root sets the [base] (welcome, sign-up, the
 * current tab); a pushed screen, a sheet or a cover [enter]s on top while it's on screen and [leave]s
 * when it goes. The newest one still there is the screen on show. Main thread only.
 */
object ScreenTracker {
    private var base: Screen? = null
    private var baseProperties: Map<String, Any> = emptyMap()
    private val layers = mutableListOf<Layer>()
    private var nextToken = 0L

    private class Layer(val token: Long, val screen: Screen, val properties: Map<String, Any>)

    fun base(screen: Screen, properties: Map<String, Any> = emptyMap()) {
        base = screen
        baseProperties = properties
        publish()
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

    private fun publish() {
        val top = layers.lastOrNull()
        val screen = top?.screen ?: base ?: return
        Telemetry.screen(screen, top?.properties ?: baseProperties)
    }

    /** Unit tests. */
    fun reset() {
        base = null
        layers.clear()
    }
}
