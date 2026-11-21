package com.bepinex.android.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * PC-style XD Google login via login-xdsdk, bypassing GMS Sign-In.
 *
 * Mirrors `XD.SDK.PC` `GoogleAuth` / `WebAuth`:
 * 1. Bind `HttpListener`-equivalent on `http://127.0.0.1:<port>/`
 * 2. Open system browser / Custom Tabs with that `redirect_uri`
 * 3. SPA redirects to loopback with `token` + `grantType` → deliver via
 *    [XdGoogleSignInBridge]
 *
 * Custom Tabs reuse Chrome's Google session (device accounts). In-process
 * WebView is only a last-resort fallback (no shared cookies).
 */
object XdGoogleWebAuth {
    private const val TAG = "BugtopiaXdOAuth"

    const val SCHEME = "bugtopia"
    const val HOST = "oauth"
    /** Legacy custom-scheme fallback (kept for deep-link / WebView paths). */
    const val REDIRECT_URI = "$SCHEME://$HOST"

    const val PREFS_PENDING = "bugtopia_xd_oauth"
    const val KEY_STATE = "pending_state"
    const val KEY_STARTED_AT = "pending_started_at_ms"

    /** Heartopia global — from assets/XDConfig.json */
    const val XD_CLIENT_ID = "l2ui05wrae11bz7zxa"
    const val GOOGLE_CLIENT_ID_FOR_PC =
        "89407921384-827p8gdflhuguaav6j39sji8h761a779.apps.googleusercontent.com"
    const val GAME_NAME = "Heartopia"
    const val LOGIN_PAGE = "https://login-xdsdk.xd.com/"
    const val API_BASE = "https://xdsdk-intnl-6.xd.com"
    const val UNION_PATH = "/api/login/v1/union"

    const val HEARTOPIA_PACKAGE = "com.xd.xdtglobal.gp"

    private val activeServer = AtomicReference<XdOAuthLoopbackServer?>(null)

    fun isHeartopia(packageName: String): Boolean =
        packageName == HEARTOPIA_PACKAGE || packageName.contains("xdtglobal")

    fun buildAuthorizeUri(state: String, redirectUri: String): Uri =
        Uri.parse(LOGIN_PAGE).buildUpon()
            .appendQueryParameter("auth_type", "google")
            .appendQueryParameter("client_id", GOOGLE_CLIENT_ID_FOR_PC)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("state", state)
            .appendQueryParameter("xd_client_id", XD_CLIENT_ID)
            .appendQueryParameter("game_name", GAME_NAME)
            .appendQueryParameter("lang", "en_US")
            .build()

    fun start(context: Context) {
        stopActiveServer()

        val state = "bt_" + UUID.randomUUID().toString().replace("-", "")
        context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATE, state)
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .apply()

        val server = XdOAuthLoopbackServer { uri ->
            deliverCallback(context.applicationContext, uri)
        }
        if (!server.start()) {
            Log.w(TAG, "loopback bind failed — falling back to WebView + custom scheme")
            XdOAuthWebActivity.launch(context, buildAuthorizeUri(state, REDIRECT_URI))
            return
        }
        activeServer.set(server)

        val authUrl = buildAuthorizeUri(state, server.redirectUri)
        Log.i(TAG, "PC-style OAuth: redirect_uri=${server.redirectUri} url=$authUrl")

        if (!launchCustomTabs(context, authUrl)) {
            Log.w(TAG, "Custom Tabs unavailable — WebView fallback (no Android Google cookies)")
            XdOAuthWebActivity.launch(context, authUrl)
        }
    }

    fun consumeExpectedState(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        val state = prefs.getString(KEY_STATE, null)
        prefs.edit().remove(KEY_STATE).remove(KEY_STARTED_AT).apply()
        return state
    }

    fun stopActiveServer() {
        activeServer.getAndSet(null)?.stop()
    }

    private fun deliverCallback(appContext: Context, uri: Uri) {
        stopActiveServer()

        val error = XdOAuthQuery.param(uri, "error")
        if (!error.isNullOrEmpty()) {
            Log.w(TAG, "OAuth error=$error")
            return
        }

        val expected = consumeExpectedState(appContext)
        val state = XdOAuthQuery.param(uri, "state")
        if (expected != null && state != null && expected != state) {
            Log.e(TAG, "OAuth state mismatch expected=$expected got=$state")
            return
        }

        val token = XdOAuthQuery.param(uri, "token")
        val grantType = XdOAuthQuery.param(uri, "grantType")
        if (token.isNullOrEmpty()) {
            Log.e(TAG, "OAuth callback missing token: $uri")
            return
        }
        Log.i(TAG, "token len=${token.length} hasPlus=${token.contains('+')} grantType=$grantType")

        when (XdGoogleSignInBridge.onWebAuthResult(token, grantType)) {
            XdGoogleSignInBridge.DeliverResult.DELIVERED_LIVE ->
                Log.i(TAG, "loopback → signInSuccess delivered")
            XdGoogleSignInBridge.DeliverResult.CACHED ->
                Log.i(TAG, "loopback token cached — tap Google login in-game")
            XdGoogleSignInBridge.DeliverResult.FAILED_EMPTY ->
                Log.e(TAG, "loopback token empty")
            XdGoogleSignInBridge.DeliverResult.FAILED_INVOKE ->
                Log.e(TAG, "loopback deliver invoke failed")
        }
    }

    private fun launchCustomTabs(context: Context, authUrl: Uri): Boolean {
        return try {
            val tabs = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .build()
            tabs.launchUrl(context, authUrl)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "CustomTabs launch failed: ${t.message}")
            // Plain Chrome VIEW as second attempt
            try {
                val view = Intent(Intent.ACTION_VIEW, authUrl)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(view)
                true
            } catch (t2: Throwable) {
                Log.w(TAG, "ACTION_VIEW launch failed: ${t2.message}")
                false
            }
        }
    }
}
