// Port of Moblin/VideoEffects/Browser/BrowserEffect.swift @97e26d2: a web page shown as a widget.
package io.github.mesmerize08.nexusirl.scene

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.graphics.drawable.toDrawable
import io.github.mesmerize08.nexusirl.BuildConfig
import io.github.mesmerize08.nexusirl.chat.BrowserChatProtocol
import io.github.mesmerize08.nexusirl.chat.BrowserChatSubscription
import io.github.mesmerize08.nexusirl.chat.ChatPost
import io.github.mesmerize08.nexusirl.media.pipeline.VideoPipeline
import io.github.mesmerize08.nexusirl.settings.SettingsWidget
import io.github.mesmerize08.nexusirl.settings.SettingsWidgetBrowser
import java.util.UUID

/**
 * Hosts browser widgets. Each page lives in a WebView on its own private virtual display, which
 * renders straight into a compositor surface (no screenshots, no permission needed, and it keeps
 * running in the background while live). One CSS pixel is one canvas pixel, like upstream's
 * snapshot size.
 *
 * Security (docs/PORT_PLAN.md §9.8): only https pages (cleartext is blocked app-wide, §9.5), no
 * file or content access, and the
 * `moblin` JavaScript object only when the widget allows it. It exposes no settings or secrets.
 *
 * Call from the main thread.
 */
class BrowserWidgets(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val displayManager = this.context.getSystemService(DisplayManager::class.java)

    private class Entry(val settings: SettingsWidgetBrowser) {
        var display: VirtualDisplay? = null
        var presentation: Presentation? = null
        var webView: WebView? = null
        var released = false
        val bridge = MoblinBridge()
    }

    private val entries = HashMap<UUID, Entry>()

    init {
        // Debug builds only: lets automated tests inspect widget pages over DevTools.
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }
    private var pipeline: VideoPipeline? = null

    /** Shows exactly [widgets] (the selected scene's enabled browser widgets) on [pipeline]. */
    fun update(pipeline: VideoPipeline?, widgets: List<SettingsWidget>) {
        if (pipeline !== this.pipeline) {
            entries.keys.toList().forEach { release(it) }
            this.pipeline = pipeline
        }
        if (pipeline == null) {
            return
        }
        val wanted = widgets.filter { isAllowedUrl(it.browser.url) }.associateBy { it.id }
        for (id in entries.keys.toList()) {
            val widget = wanted[id]
            if (widget == null || widget.browser != entries[id]?.settings) {
                release(id)
            }
        }
        for ((id, widget) in wanted) {
            if (id !in entries) {
                create(pipeline, id, widget.browser)
            }
        }
    }

    private fun create(pipeline: VideoPipeline, id: UUID, settings: SettingsWidgetBrowser) {
        val entry = Entry(settings)
        entries[id] = entry
        val width = settings.width.coerceIn(1, 4096)
        val height = settings.height.coerceIn(1, 4096)
        pipeline.createSurfaceOverlay(overlayId(id), width, height) { surface ->
            main.post {
                if (entry.released) {
                    return@post
                }
                val display = displayManager.createVirtualDisplay("nexusirl-browser-$id", width, height, DENSITY_DPI, surface, 0)
                entry.display = display
                val presentation = Presentation(context, display.display)
                entry.presentation = presentation
                // The presentation's context has the virtual display's density (1 CSS px = 1 px).
                val webView = createWebView(presentation.context, settings, entry.bridge)
                entry.webView = webView
                presentation.apply {
                    window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
                    setContentView(webView)
                    show()
                }
                webView.loadUrl(settings.url)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(displayContext: Context, settings: SettingsWidgetBrowser, bridge: MoblinBridge): WebView =
        WebView(displayContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            this.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = false
            }
            if (settings.moblinAccess) {
                addJavascriptInterface(bridge, BRIDGE_NAME)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !isAllowedUrl(request.url.toString())

                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(setupScript(settings), null)
                }
            }
        }

    /** Viewport and style sheet like upstream's user scripts, plus the `moblin` object when allowed. */
    private fun setupScript(settings: SettingsWidgetBrowser): String = buildString {
        append(
            "(function(){var m=document.createElement('meta');m.name='viewport';" +
                "m.content='width=${settings.width}, initial-scale=1';document.head&&document.head.appendChild(m);",
        )
        if (settings.styleSheet.isNotEmpty()) {
            val encoded = Base64.encodeToString(settings.styleSheet.toByteArray(), Base64.NO_WRAP)
            append("var s=document.createElement('style');s.innerHTML=atob('$encoded');document.head&&document.head.appendChild(s);")
        }
        append("})();")
        if (settings.moblinAccess) {
            // Upstream's moblin.js talks to window.webkit.messageHandlers.moblin; route it to Android.
            append(
                "if(!window.moblin){window.webkit={messageHandlers:{moblin:{postMessage:function(m){$BRIDGE_NAME.postMessage(m);}}}};" +
                    MOBLIN_JS + "window.moblin=moblin;}",
            )
        }
    }

    /**
     * Upstream `BrowserEffectServer.sendChatMessage`: [post] goes to every page that subscribed to
     * chat (pages only get the `moblin` object with Moblin access). Main thread.
     */
    fun sendChat(post: ChatPost) {
        var encoded: String? = null
        for (entry in entries.values) {
            val subscription = entry.bridge.chat ?: continue
            if (!BrowserChatProtocol.matches(subscription, post)) {
                continue
            }
            val message = encoded ?: Base64.encodeToString(BrowserChatProtocol.encodeChatMessage(post).toByteArray(), Base64.NO_WRAP)
            encoded = message
            entry.webView?.evaluateJavascript("window.moblin&&moblin.handleMessage(\"$message\")", null)
        }
    }

    /** Messages from pages using the `moblin` object (upstream BrowserEffectServer). Called on a WebView thread. */
    private class MoblinBridge {
        @Volatile
        var chat: BrowserChatSubscription? = null

        @JavascriptInterface
        fun postMessage(message: String) {
            BrowserChatProtocol.parseChatSubscription(message)?.let {
                chat = it
                return
            }
            // Speech-to-text and publish (logging, video playing) come later; log them meanwhile.
            if (!message.contains("\"ping\"")) {
                Log.d("BrowserWidget", "moblin message: ${message.take(200)}")
            }
        }
    }

    private fun release(id: UUID) {
        val entry = entries.remove(id) ?: return
        entry.released = true
        entry.presentation?.dismiss()
        entry.webView?.destroy()
        entry.display?.release()
        pipeline?.releaseSurfaceOverlay(overlayId(id))
    }

    companion object {
        private const val DENSITY_DPI = 160
        private const val BRIDGE_NAME = "nexusirlMoblin"

        fun overlayId(widgetId: UUID): String = "browser:$widgetId"

        fun isAllowedUrl(url: String): Boolean = url.startsWith("https://", ignoreCase = true)

        /** Upstream Moblin/VideoEffects/Browser/moblin.js (MIT). */
        private val MOBLIN_JS = """
            class Moblin {
              constructor() {
                this.timer = setInterval(() => { this.send({ ping: {} }); }, 2000);
                this.onmessage = null;
                this.textDecoder = new TextDecoder();
              }
              publish(message) { this.send({ publish: { message: message } }); }
              log(message) { this.publish({ log: { message: String(message) } }); }
              subscribe(topic) { this.send({ subscribe: { topic: topic } }); }
              handleMessage(message) {
                if (this.onmessage) {
                  const json = this.textDecoder.decode(Uint8Array.from(atob(message), c => c.charCodeAt(0)));
                  this.onmessage(JSON.parse(json).message.data);
                }
              }
              send(message) { window.webkit.messageHandlers.moblin.postMessage(JSON.stringify(message)); }
            }
            const moblin = new Moblin();
        """.trimIndent().replace("\n", " ")
    }
}
