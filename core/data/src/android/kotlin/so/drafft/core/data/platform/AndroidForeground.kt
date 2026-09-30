package so.drafft.core.data.platform

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * The app coming back to the front (the iPhone's `didBecomeActive`), from Settings included: the system
 * permissions are read again then, so screens never refresh them themselves.
 */
object AndroidForeground {
    /** Calls [action] on the main thread each time the app comes back to the front. */
    fun observe(action: () -> Unit) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = action()
        }
        val add = { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) }
        if (Looper.myLooper() == Looper.getMainLooper()) add() else Handler(Looper.getMainLooper()).post(add)
    }
}
