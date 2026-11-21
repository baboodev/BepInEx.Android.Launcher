package com.bepinex.android.auth

import android.net.Uri
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Query parsing that keeps '+' intact. Android [Uri.getQueryParameter] and
 * form-urlencoded decoding treat '+' as space, which corrupts CryptoJS
 * `U2FsdGVkX1…` tokens from login-xdsdk (HTTP 500 / Unknown Error on union).
 */
object XdOAuthQuery {
    fun param(uri: Uri, key: String): String? {
        val encoded = uri.encodedQuery ?: return uri.getQueryParameter(key)
        return paramFromEncodedQuery(encoded, key)
    }

    fun paramFromEncodedQuery(encodedQuery: String, key: String): String? {
        for (part in encodedQuery.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val rawKey = if (eq < 0) part else part.substring(0, eq)
            val rawVal = if (eq < 0) "" else part.substring(eq + 1)
            if (percentDecode(rawKey) != key) continue
            // Preserve '+' then decode %XX only.
            return percentDecode(rawVal.replace("+", "%2B"))
        }
        return null
    }

    private fun percentDecode(s: String): String =
        URLDecoder.decode(s, StandardCharsets.UTF_8.name())
}
