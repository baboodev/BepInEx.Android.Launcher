package com.bepinex.android.auth

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Receives [XdGoogleWebAuth.REDIRECT_URI] after login-xdsdk finishes Google auth
 * and feeds `token`/`grantType` into the stock Android Google sign-in handler
 * via [XdGoogleSignInBridge] (`AuthorizationCallback.signInSuccess` →
 * `processLoginByToken`). No SharedPreferences injection.
 */
class XdOAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) {
            finishWith("OAuth callback: empty intent data")
            return
        }
        Log.i(TAG, "callback uri=$uri")

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

    companion object {
        private const val TAG = "BugtopiaXdOAuth"
    }
}
