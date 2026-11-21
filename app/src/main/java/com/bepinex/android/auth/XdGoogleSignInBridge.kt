package com.bepinex.android.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.SparseArray
import java.lang.ref.WeakReference
import java.lang.reflect.Field

/**
 * Pipes login-xdsdk web-OAuth tokens into the stock Android Google auth path:
 *
 * `GoogleSignImpl.onActivityResult` success →
 * `AuthorizationCallback.signInSuccess(GOOGLE, SignInToken)` →
 * `AccountSignInPresenterImpl.processLoginByToken`.
 *
 * GMS Sign-In fails with DEVELOPER_ERROR (10) under the launcher signature, so
 * [GameRuntime] intercepts request code [GMS_REQUEST_CODE] and opens web auth
 * instead; the callback then invokes `signInSuccess` on the live stub.
 */
object XdGoogleSignInBridge {
    private const val TAG = "BugtopiaXdOAuth"

    /** [com.xd.third.login.impl.GoogleSignImpl.SIGN_IN_GOOGLE_CODE] */
    const val GMS_REQUEST_CODE = 9001

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var pendingCallback: Any? = null
    @Volatile private var pendingClassLoader: ClassLoader? = null
    @Volatile private var pendingActivity: WeakReference<Activity>? = null

    /** Token from settings / early web auth, consumed on next Google login intercept. */
    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedGrantType: String? = null

    @JvmStatic
    fun isGoogleSignInRequest(intent: Intent?, requestCode: Int): Boolean {
        if (requestCode == GMS_REQUEST_CODE) return true
        return isGoogleSignInIntent(intent)
    }

    @JvmStatic
    fun isGoogleSignInIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val component = intent.component?.className.orEmpty()
        if (component.contains("google", ignoreCase = true) &&
            component.contains("signin", ignoreCase = true)
        ) {
            return true
        }
        val action = intent.action.orEmpty()
        return action.contains("google.android.gms.auth", ignoreCase = true)
    }

    /**
     * @return true if the GMS launch was swallowed and web OAuth (or cached
     * token delivery) was started instead.
     */
    @JvmStatic
    fun tryIntercept(who: Context?, target: Any?, intent: Intent?, requestCode: Int): Boolean {
        if (!isGoogleSignInRequest(intent, requestCode)) return false

        val activity = resolveActivity(who, target) ?: run {
            Log.w(TAG, "Google SignIn intercept: no Activity")
            return false
        }

        val task = findGoogleSignTask(activity)
        val callback = task?.let { readField(it, "mCallback") }
        if (callback == null) {
            Log.w(TAG, "Google SignIn intercept: no AuthorizationCallback on SignInFragment")
            return false
        }

        pendingCallback = callback
        pendingClassLoader = task!!.javaClass.classLoader
        pendingActivity = WeakReference(activity)

        val cached = cachedToken
        if (!cached.isNullOrEmpty()) {
            val grant = cachedGrantType
            cachedToken = null
            cachedGrantType = null
            Log.i(TAG, "Google SignIn intercept: delivering cached web token")
            deliverToPending(cached, grant)
            return true
        }

        Log.i(TAG, "Google SignIn intercept: launching web OAuth (skip GMS)")
        XdGoogleWebAuth.start(activity)
        return true
    }

    /**
     * Called from [XdOAuthCallbackActivity] after login-xdsdk returns
     * `token` + `grantType`. Invokes stock `signInSuccess` when a login is
     * in flight; otherwise caches for the next intercept.
     */
    @JvmStatic
    fun onWebAuthResult(token: String, grantType: String?): DeliverResult {
        if (token.isEmpty()) return DeliverResult.FAILED_EMPTY

        if (pendingCallback != null) {
            return if (deliverToPending(token, grantType)) {
                DeliverResult.DELIVERED_LIVE
            } else {
                DeliverResult.FAILED_INVOKE
            }
        }

        cachedToken = token
        cachedGrantType = grantType
        Log.i(TAG, "web token cached — open Google login in-game to apply")
        return DeliverResult.CACHED
    }

    enum class DeliverResult {
        DELIVERED_LIVE,
        CACHED,
        FAILED_EMPTY,
        FAILED_INVOKE,
    }

    private fun deliverToPending(token: String, grantType: String?): Boolean {
        val callback = pendingCallback ?: return false
        val cl = pendingClassLoader ?: callback.javaClass.classLoader ?: return false
        pendingCallback = null
        pendingClassLoader = null

        return try {
            val entryCls = cl.loadClass("com.xd.intl.common.entities.LoginEntryType")
            val google = entryCls.getField("GOOGLE").get(null)
            val type = entryCls.getMethod("getType").invoke(google) as Int

            val tokenCls = cl.loadClass("com.xd.intl.account.entity.SignInToken")
            // SignInToken(type, token, secret, code, scope, grantType, origDid, subType).
            // GoogleSignImpl uses the 3-arg form (type, idToken, /*secret*/ "").
            // login-xdsdk returns an encrypted authorization_code — put it in grantType
            // (6th), NOT secret (3rd). Passing grantType as the 3rd arg caused HTTP 500.
            val grant = grantType.orEmpty().ifEmpty { "authorization_code" }
            val signInToken = if (grant.isEmpty() || grant == "id_token") {
                tokenCls.getConstructor(
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                ).newInstance(type, token, "")
            } else {
                tokenCls.getConstructor(
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                ).newInstance(type, token, "", "", "", grant)
            }
            Log.i(TAG, "SignInToken type=$type grantType=$grant tokenLen=${token.length}")

            val method = callback.javaClass.methods.firstOrNull { m ->
                m.name == "signInSuccess" && m.parameterTypes.size == 2
            } ?: run {
                Log.e(TAG, "signInSuccess not found on ${callback.javaClass.name}")
                return false
            }

            val invoke = Runnable {
                try {
                    method.invoke(callback, google, signInToken)
                    Log.i(TAG, "signInSuccess(GOOGLE, SignInToken) delivered")
                } catch (t: Throwable) {
                    Log.e(TAG, "signInSuccess invoke failed", t)
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                invoke.run()
            } else {
                mainHandler.post(invoke)
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "deliver SignInToken failed", t)
            false
        }
    }

    private fun resolveActivity(who: Context?, target: Any?): Activity? {
        (who as? Activity)?.let { return it }
        (target as? Activity)?.let { return it }
        pendingActivity?.get()?.let { return it }
        return null
    }

    private fun findGoogleSignTask(activity: Activity): Any? {
        // Prefer the tagged stock fragment (SignInFragment.TAG = "XDG_SignInFragment").
        try {
            val tagged = activity.fragmentManager.findFragmentByTag("XDG_SignInFragment")
            taskFromFragment(tagged)?.let { return it }
        } catch (t: Throwable) {
            Log.w(TAG, "findFragmentByTag failed: ${t.message}")
        }
        for (fragment in collectFragments(activity)) {
            taskFromFragment(fragment)?.let { return it }
        }
        return null
    }

    private fun taskFromFragment(fragment: Any?): Any? {
        if (fragment == null) return null
        if (!fragment.javaClass.name.contains("SignInFragment")) return null
        val task = readField(fragment, "mSignIn") ?: return null
        val name = task.javaClass.name
        if (name.contains("GoogleSign") || name.contains("Google")) return task
        // request was already identified as Google — accept any bound task
        return task
    }

    private fun collectFragments(activity: Activity): List<Any> {
        val out = ArrayList<Any>()
        try {
            out.addAll(fragmentsFromManager(activity.fragmentManager))
        } catch (t: Throwable) {
            Log.w(TAG, "android.app.FragmentManager scan failed: ${t.message}")
        }
        try {
            val getter = activity.javaClass.methods.firstOrNull {
                it.name == "getSupportFragmentManager" && it.parameterTypes.isEmpty()
            }
            if (getter != null) {
                out.addAll(fragmentsFromManager(getter.invoke(activity)))
            }
        } catch (_: Throwable) {
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun fragmentsFromManager(fm: Any?): List<Any> {
        if (fm == null) return emptyList()
        try {
            val getFragments = fm.javaClass.methods.firstOrNull {
                it.name == "getFragments" && it.parameterTypes.isEmpty()
            }
            if (getFragments != null) {
                val list = getFragments.invoke(fm) as? List<*>
                if (list != null) return list.filterNotNull()
            }
        } catch (_: Throwable) {
        }

        var cls: Class<*>? = fm.javaClass
        while (cls != null) {
            for (fieldName in arrayOf("mAdded", "mActive")) {
                try {
                    val field = cls.getDeclaredField(fieldName)
                    field.isAccessible = true
                    when (val value = field.get(fm)) {
                        is List<*> -> return value.filterNotNull()
                        is Array<*> -> return value.filterNotNull()
                        is SparseArray<*> -> {
                            val list = ArrayList<Any>(value.size())
                            for (i in 0 until value.size()) {
                                value.valueAt(i)?.let { list.add(it) }
                            }
                            return list
                        }
                    }
                } catch (_: NoSuchFieldException) {
                } catch (t: Throwable) {
                    Log.w(TAG, "fragment field $fieldName: ${t.message}")
                }
            }
            cls = cls.superclass
        }
        return emptyList()
    }

    private fun readField(obj: Any, name: String): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                val field: Field = cls.getDeclaredField(name)
                field.isAccessible = true
                return field.get(obj)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        return null
    }
}
