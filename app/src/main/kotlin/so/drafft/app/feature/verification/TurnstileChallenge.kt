package so.drafft.app.feature.verification

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import org.koin.compose.koinInject
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.backend.asObject
import so.drafft.core.data.backend.asString
import so.drafft.core.data.backend.parseJsonOrNull
import so.drafft.core.model.L
import so.drafft.core.ui.components.SheetBlock
import so.drafft.core.ui.platform.LocalPlatformUi
import so.drafft.core.ui.platform.WebPage
import so.drafft.core.ui.theme.DS

/**
 * Cloudflare Turnstile for the support form sent signed out: the widget runs in a web view the person
 * never sees, and hands back a single-use token the backend `support` verifies. Only when Cloudflare
 * wants an interaction does the web view show, in its own sheet. The page is loaded as getdrafft.com,
 * the domain the site key allows.
 */
@Stable
class TurnstileChallenge(
    /** The web view the widget runs in (`PlatformUi.rememberWebPage`, a WebView on Android). */
    val webView: WebPage,
    private val siteKey: String,
) {
    /** Ready to send; null while the widget works, after a send, or once it expired. */
    var token: String? by mutableStateOf(null)
        private set

    /** Cloudflare wants the person to tick the box: the web view shows in a sheet. */
    var needsInteraction: Boolean by mutableStateOf(false)

    /** The widget itself failed (no network, blocked): the form says so. */
    var failed: Boolean by mutableStateOf(false)
        private set

    private var started = false

    /** Loads the widget once; later calls do nothing. */
    fun start() {
        if (started) return
        started = true
        webView.onMessage = ::receive
        webView.load(page(siteKey), "https://getdrafft.com", BRIDGE)
    }

    /** A token works once: drop it and ask for a new one (after each send, or when the server refused it). */
    fun renew() {
        token = null
        failed = false
        if (!started) return start()
        webView.evaluate("window.renew && window.renew()")
    }

    /** Frees the web view: no handler left to keep this object alive. */
    fun stop() {
        webView.onMessage = null
        webView.clear()
        token = null
        started = false
    }

    private fun receive(body: String) {
        val message = body.encodeToByteArray().parseJsonOrNull().asObject ?: return
        val value = message["token"].asString
        when {
            !value.isNullOrEmpty() -> {
                // Turnstile retries on its own after an error: a token means it recovered.
                token = value
                failed = false
                needsInteraction = false
            }
            message.has("expired") -> token = null
            message.has("interactive") -> needsInteraction = true
            message.has("interactiveDone") -> needsInteraction = false
            message.has("error") -> {
                token = null
                failed = true
                needsInteraction = false
            }
        }
    }

    private fun JsonObject.has(key: String): Boolean = containsKey(key)

    companion object {
        /** The JavaScript interface the page posts through. */
        private const val BRIDGE = "turnstile"

        private fun page(siteKey: String): String = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;background:transparent}#w{display:flex;justify-content:center}</style>
            <script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit&onload=ready" async defer></script>
            </head><body><div id="w"></div><script>
            function post(m){window.$BRIDGE.postMessage(JSON.stringify(m))}
            var id=null;
            function ready(){id=turnstile.render('#w',{sitekey:'$siteKey',appearance:'interaction-only',
              'refresh-expired':'auto',
              callback:function(t){post({token:t})},
              'expired-callback':function(){post({expired:true})},
              'error-callback':function(c){post({error:String(c)})},
              'before-interactive-callback':function(){post({interactive:true})},
              'after-interactive-callback':function(){post({interactiveDone:true})}})}
            window.renew=function(){if(id!==null){turnstile.reset(id)}};
            </script></body></html>
        """.trimIndent()
    }
}

/** A [TurnstileChallenge] for this screen, with its own web view, stopped when the screen goes. */
@Composable
fun rememberTurnstileChallenge(): TurnstileChallenge {
    val page = LocalPlatformUi.current.rememberWebPage()
    val backend = koinInject<Backend>()
    val challenge = remember(page) { TurnstileChallenge(page, backend.config.turnstileSiteKey) }
    DisposableEffect(challenge) { onDispose { challenge.stop() } }
    return challenge
}

/** The widget's web view, placed in Compose (hidden in the form, visible in the check sheet). */
@Composable
fun TurnstileView(challenge: TurnstileChallenge, modifier: Modifier = Modifier) {
    LocalPlatformUi.current.WebPageView(challenge.webView, modifier)
}

/** The rare interactive check, in its own sheet. */
@Composable
fun TurnstileSheet(challenge: TurnstileChallenge, modifier: Modifier = Modifier) {
    // `presentationDetents([.height(220)])`: the sheet fits this block.
    SheetBlock(modifier.padding(DS.Space.lg), title = L("Quick security check")) {
        TurnstileView(challenge, Modifier.fillMaxWidth().height(80.dp))
    }
}
