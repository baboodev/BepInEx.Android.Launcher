package com.bepinex.android.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.os.Build
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast

/**
 * In-process login-xdsdk WebView. Custom Tabs often fail to hand a custom-scheme
 * redirect (`bugtopia://oauth`) back to the app (`ERR_UNKNOWN_URL_SCHEME`); a
 * WebView in `:game` can intercept that navigation and feed
 * [XdGoogleSignInBridge] directly.
 */
class XdOAuthWebActivity : Activity() {
    private var webView: WebView? = null
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val authUrl = intent?.getStringExtra(EXTRA_AUTH_URL)
        if (authUrl.isNullOrEmpty()) {
            finishWith("OAuth WebView: missing auth URL")
            return
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.WHITE)

        val progress = ProgressBar(this).apply {
            isIndeterminate = true
        }
        val progressLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )

        val wv = WebView(this)
        webView = wv
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        val settings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // Google rejects stock WebView UA ("; wv"). Use a plain Chrome mobile UA.
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; Mobile) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    Log.i(
                        TAG,
                        "console[${consoleMessage.messageLevel()}] ${consoleMessage.message()} " +
                            "@${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}",
                    )
                }
                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.visibility =
                    if (newProgress in 1..99) android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val uri = request?.url ?: return false
                Log.i(TAG, "shouldOverride ${uri}")
                return handleUri(uri)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url.isNullOrEmpty()) return false
                Log.i(TAG, "shouldOverride(legacy) $url")
                return handleUri(Uri.parse(url))
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (!url.isNullOrEmpty()) {
                    Log.i(TAG, "visited $url")
                    handleUri(Uri.parse(url))
                }
                super.doUpdateVisitedHistory(view, url, isReload)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (!url.isNullOrEmpty()) {
                    Log.i(TAG, "pageStarted $url")
                    handleUri(Uri.parse(url))
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = android.view.View.GONE
                if (!url.isNullOrEmpty()) {
                    Log.i(TAG, "pageFinished $url")
                    handleUri(Uri.parse(url))
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                val uri = request?.url
                // WebView often reports ERR_UNKNOWN_URL_SCHEME instead of
                // calling shouldOverrideUrlLoading for bugtopia://.
                if (uri != null && handleUri(uri)) return
                Log.w(
                    TAG,
                    "onReceivedError code=${error?.errorCode} desc=${error?.description} url=$uri",
                )
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                if (!failingUrl.isNullOrEmpty() && handleUri(Uri.parse(failingUrl))) return
                Log.w(TAG, "onReceivedError(legacy) code=$errorCode desc=$description url=$failingUrl")
            }
        }

        root.addView(
            wv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(progress, progressLp)
        setContentView(root)

        Log.i(TAG, "loading $authUrl")
        wv.loadUrl(authUrl)
    }

    private fun handleUri(uri: Uri): Boolean {
        val scheme = uri.scheme.orEmpty()
        val host = uri.host.orEmpty()
        // PC-style loopback redirect (server also receives this GET).
        if (scheme.equals("http", ignoreCase = true) &&
            (host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)) &&
            !uri.getQueryParameter("token").isNullOrEmpty()
        ) {
            Log.i(TAG, "intercepted loopback redirect $uri")
            deliver(uri)
            return true
        }
        if (scheme.equals(XdGoogleWebAuth.SCHEME, ignoreCase = true) &&
            host.equals(XdGoogleWebAuth.HOST, ignoreCase = true)
        ) {
            Log.i(TAG, "intercepted redirect $uri")
            deliver(uri)
            return true
        }
        // Also catch intent:// and plain absolute bugtopia links embedded by SPA.
        if (scheme.equals("intent", ignoreCase = true)) {
            val fallback = uri.getQueryParameter("S.browser_fallback_url")
            val data = uri.toString()
            if (data.contains("bugtopia://oauth") || fallback?.contains("bugtopia://") == true) {
                val extracted = Uri.parse(
                    fallback ?: data.substringAfter("bugtopia://").let { "bugtopia://$it" },
                )
                if (extracted.scheme == XdGoogleWebAuth.SCHEME) {
                    deliver(extracted)
                    return true
                }
            }
        }
        return false
    }

    private fun deliver(uri: Uri) {
        if (finished) return
        finished = true

        val error = XdOAuthQuery.param(uri, "error")
        if (!error.isNullOrEmpty()) {
            finishWith("Google login cancelled: $error")
            return
        }

        val expected = XdGoogleWebAuth.consumeExpectedState(this)
        val state = XdOAuthQuery.param(uri, "state")
        if (expected != null && state != null && expected != state) {
            finishWith("OAuth state mismatch")
            return
        }

        val token = XdOAuthQuery.param(uri, "token")
        val grantType = XdOAuthQuery.param(uri, "grantType")
        if (token.isNullOrEmpty()) {
            finishWith("OAuth callback missing token: $uri")
            return
        }

        when (XdGoogleSignInBridge.onWebAuthResult(token, grantType)) {
            XdGoogleSignInBridge.DeliverResult.DELIVERED_LIVE ->
                finishWith("Google web login delivered to XD SDK")
            XdGoogleSignInBridge.DeliverResult.CACHED ->
                finishWith("Google web token ready — tap Google login in-game")
            XdGoogleSignInBridge.DeliverResult.FAILED_EMPTY ->
                finishWith("OAuth token empty")
            XdGoogleSignInBridge.DeliverResult.FAILED_INVOKE ->
                finishWith("Failed to invoke XD signInSuccess — see logcat $TAG")
        }
    }

    private fun finishWith(msg: String) {
        Log.i(TAG, msg)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BugtopiaXdOAuth"
        private const val EXTRA_AUTH_URL = "auth_url"

        fun launch(context: Context, authUrl: Uri) {
            val intent = Intent(context, XdOAuthWebActivity::class.java)
                .putExtra(EXTRA_AUTH_URL, authUrl.toString())
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
