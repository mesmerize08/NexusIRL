// Port of Moblin/StreamingPlatforms/Kick/KickAuth.swift (KickLoginView, KickWebView) @97e26d2.
package io.github.mesmerize08.nexusirl.kick

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.mesmerize08.nexusirl.R

/**
 * Kick's own sign-in page. When Kick sets its `session_token` cookie, the token is returned as the
 * result ([EXTRA_TOKEN]) and the page is closed.
 *
 * Runs in its own process (`:kick` in the manifest) with its own WebView data directory, so Kick's
 * cookies never mix with the browser widgets', and all of its web data is deleted before and after
 * signing in (upstream uses a non-persistent web data store). No JavaScript interfaces; screenshots
 * are blocked, as the user types their password here.
 */
class KickLoginActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var loginButtonClicked = false
    private var done = false

    private val checkForToken = object : Runnable {
        override fun run() {
            if (done) {
                return
            }
            sessionToken()?.let(::finishWithToken) ?: handler.postDelayed(this, 1_000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must come before any WebView in this process; this process runs nothing else.
        runCatching { WebView.setDataDirectorySuffix("kick") }
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        clearWebData()
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                // Only web pages: no app links or other schemes from Kick's page.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.scheme != "https"

                override fun onPageFinished(view: WebView, url: String) {
                    if (!loginButtonClicked) {
                        detectAndClickLoginButton(view)
                    }
                }
            }
        }
        webView = web
        val padding = (16 * resources.displayMetrics.density).toInt()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(
                Button(this@KickLoginActivity).apply {
                    setText(R.string.cancel)
                    setOnClickListener { finish() }
                },
            )
            addView(
                TextView(this@KickLoginActivity).apply {
                    setText(R.string.kick_sign_in_page_help)
                    setPadding(padding, 0, 0, 0)
                },
            )
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(bar)
                addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
        web.loadUrl(LOGIN_URL)
        handler.postDelayed(checkForToken, 1_000)
    }

    /** Upstream `detectAndClickLoginButton`: opens Kick's login form, which the page hides behind a button. */
    private fun detectAndClickLoginButton(view: WebView) {
        val script = """
            (async function() {
                try {
                    for (var attempt = 0; attempt < 50; attempt++) {
                        await new Promise(resolve => setTimeout(resolve, 200));
                        if (document.querySelector('input[name="emailOrUsername"]')) {
                            return true;
                        }
                        var loginButton = document.querySelector('[data-testid="login"]');
                        if (loginButton) {
                            loginButton.click();
                        }
                    }
                    return false;
                } catch (error) {
                    return false;
                }
            })();
        """.trimIndent()
        view.evaluateJavascript(script) { result -> loginButtonClicked = result == "true" }
    }

    private fun sessionToken(): String? {
        val cookies = CookieManager.getInstance().getCookie(LOGIN_URL) ?: return null
        return cookies.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("$SESSION_COOKIE=") }
            ?.substringAfter("=")
            // Percent-encoded in the cookie, as upstream's `removingPercentEncoding` handles.
            ?.let(Uri::decode)
            ?.takeIf { it.isNotBlank() }
    }

    private fun finishWithToken(token: String) {
        done = true
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TOKEN, token))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(checkForToken)
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        clearWebData()
        super.onDestroy()
    }

    private fun clearWebData() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
    }

    companion object {
        const val EXTRA_TOKEN = "token"
        private const val LOGIN_URL = "https://kick.com/login"
        private const val SESSION_COOKIE = "session_token"
    }
}
