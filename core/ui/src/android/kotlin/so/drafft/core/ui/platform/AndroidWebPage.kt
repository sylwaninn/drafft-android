package so.drafft.core.ui.platform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import java.lang.ref.WeakReference

// The web view behind TurnstileChallenge (Drafft/Features/Verification/TurnstileChallenge.swift): the
// iPhone's WKWebView with a script message handler becomes a WebView with a JavaScript interface.

/** A page kept alive in one web view, shown wherever [AndroidPlatformUi.WebPageView] places it. */
@SuppressLint("SetJavaScriptEnabled")
internal class AndroidWebPage(context: Context) : WebPage {
    val webView: WebView = WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = WebView.OVER_SCROLL_NEVER
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Nothing kept from one check to the next (the iPhone's non-persistent data store).
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
    }
    private val main = Handler(Looper.getMainLooper())
    private var bridge: String? = null
    private var destroyed = false

    override var onMessage: ((String) -> Unit)? = null

    override fun load(html: String, baseUrl: String, bridge: String) {
        if (destroyed) return
        this.bridge?.let(webView::removeJavascriptInterface)
        this.bridge = bridge
        webView.addJavascriptInterface(Bridge(this), bridge)
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
    }

    override fun evaluate(script: String) {
        if (destroyed) return
        webView.evaluateJavascript(script, null)
    }

    override fun clear() {
        if (destroyed) return
        webView.stopLoading()
        bridge?.let(webView::removeJavascriptInterface)
        bridge = null
        webView.loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
    }

    /** Leaves the view it sits in, so another place can take it. */
    fun detach() {
        (webView.parent as? ViewGroup)?.removeView(webView)
    }

    fun destroy() {
        clear()
        detach()
        destroyed = true
        webView.destroy()
    }

    /** Called on the web view's JavaScript thread; points back weakly, like the iPhone's handler. */
    private class Bridge(page: AndroidWebPage) {
        private val page = WeakReference(page)

        @JavascriptInterface
        fun postMessage(message: String) {
            val target = page.get() ?: return
            target.main.post { target.onMessage?.invoke(message) }
        }
    }
}
