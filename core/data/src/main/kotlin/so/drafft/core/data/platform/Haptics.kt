package so.drafft.core.data.platform

/**
 * Haptic feedback, callable from anywhere (screens and AppModel), like the iPhone's `Haptics`.
 * The Android implementation ([engine]) is installed at launch and keeps its vibrator warm, so the
 * tick lands with the visual change.
 */
object Haptics {
    interface Engine {
        fun tap()
        fun thump()
        fun success()
        fun warning()
        fun select()
    }

    @Volatile
    var engine: Engine = object : Engine {
        override fun tap() = Unit
        override fun thump() = Unit
        override fun success() = Unit
        override fun warning() = Unit
        override fun select() = Unit
    }

    /** Light impact: a button, a send. */
    fun tap() = engine.tap()

    /** Medium impact: a swipe decided, a card landing. */
    fun thump() = engine.thump()

    fun success() = engine.success()
    fun warning() = engine.warning()

    /** Selection tick: a chip, a picker step. */
    fun select() = engine.select()
}
