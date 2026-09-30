package so.drafft.app.platform

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import so.drafft.core.data.platform.ForegroundReturns

/** The process coming back to the foreground after being stopped (ProcessLifecycleOwner's onStart after onStop). */
class ProcessForegroundReturns : ForegroundReturns, DefaultLifecycleObserver {
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var wasStopped = false

    override val returns: Flow<Unit> get() = events

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        wasStopped = true
    }

    override fun onStart(owner: LifecycleOwner) {
        if (!wasStopped) return
        wasStopped = false
        events.tryEmit(Unit)
    }
}
