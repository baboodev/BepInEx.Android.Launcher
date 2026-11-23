package com.bepinex.android.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ModSettingsClient {
    companion object {
        private const val TAG = "BugtopiaModSettingsClient"
        // Schema build + JNI pack on the mod side can exceed a few seconds on first open.
        private const val TIMEOUT_MS = 30_000L
    }

    private val thread = HandlerThread("ModSettingsPanel").also { it.start() }
    private val replyMessenger = Messenger(Handler(thread.looper) { msg -> onReply(msg); true })
    private var serviceMessenger: Messenger? = null
    private var bound = false
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Message>>()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError
    var onPushState: ((String) -> Unit)? = null
    var onSchema: ((String) -> Unit)? = null
    var onValues: ((String) -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceMessenger = Messenger(binder)
            bound = true
            _connected.value = true
            Log.i(TAG, "service connected")
            sendHello()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
            bound = false
            _connected.value = false
            Log.w(TAG, "service disconnected")
        }
    }

    fun bind(context: Context) {
        val intent = Intent(context, ModSettingsService::class.java).apply {
            action = ModSettingsProtocol.ACTION_BIND
        }
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind(context: Context) {
        try {
            sendSimple(ModSettingsProtocol.MSG_GOODBYE) {
                putString(ModSettingsProtocol.KEY_ROLE, ModSettingsProtocol.ROLE_PANEL)
            }
        } catch (_: Throwable) {}
        if (bound) {
            try { context.unbindService(connection) } catch (_: Throwable) {}
        }
        bound = false
        _connected.value = false
        serviceMessenger = null
    }

    private fun sendHello() {
        sendSimple(ModSettingsProtocol.MSG_HELLO) {
            putString(ModSettingsProtocol.KEY_ROLE, ModSettingsProtocol.ROLE_PANEL)
        }
    }

    suspend fun getSchema(): String {
        val msg = request(ModSettingsProtocol.MSG_GET_SCHEMA, ModSettingsProtocol.MSG_SCHEMA) {}
        val json = msg.data.getString(ModSettingsProtocol.KEY_SCHEMA_JSON).orEmpty()
        if (json.isEmpty()) {
            Log.w(TAG, "getSchema empty")
        } else {
            Log.i(TAG, "getSchema bytes=${json.length}")
        }
        onSchema?.invoke(json)
        return json
    }

    suspend fun getValues(keysJson: String? = null): String {
        val msg = request(ModSettingsProtocol.MSG_GET_VALUES, ModSettingsProtocol.MSG_VALUES) {
            if (keysJson != null) putString(ModSettingsProtocol.KEY_KEYS_JSON, keysJson)
        }
        val json = msg.data.getString(ModSettingsProtocol.KEY_VALUES_JSON).orEmpty()
        onValues?.invoke(json)
        return json
    }

    suspend fun setValue(key: String, kind: String, valueJson: String): Pair<Boolean, String> {
        val msg = request(ModSettingsProtocol.MSG_SET_VALUE, ModSettingsProtocol.MSG_SET_RESULT) {
            putString(ModSettingsProtocol.KEY_KEY, key)
            putString(ModSettingsProtocol.KEY_KIND, kind)
            putString(ModSettingsProtocol.KEY_VALUE_JSON, valueJson)
        }
        val ok = msg.data.getBoolean(ModSettingsProtocol.KEY_OK, false)
        val err = msg.data.getString(ModSettingsProtocol.KEY_ERROR).orEmpty()
        return ok to err
    }

    suspend fun invokeAction(key: String, argsJson: String? = null): Pair<Boolean, String> {
        val msg = request(ModSettingsProtocol.MSG_INVOKE_ACTION, ModSettingsProtocol.MSG_ACTION_RESULT) {
            putString(ModSettingsProtocol.KEY_KEY, key)
            if (argsJson != null) putString(ModSettingsProtocol.KEY_ARGS_JSON, argsJson)
        }
        return msg.data.getBoolean(ModSettingsProtocol.KEY_OK, false) to
            msg.data.getString(ModSettingsProtocol.KEY_ERROR).orEmpty()
    }

    suspend fun getLiveList(listId: String): Pair<Boolean, String> {
        val msg = request(ModSettingsProtocol.MSG_GET_LIVE_LIST, ModSettingsProtocol.MSG_LIVE_LIST) {
            putString(ModSettingsProtocol.KEY_LIST_ID, listId)
        }
        val err = msg.data.getString(ModSettingsProtocol.KEY_ERROR).orEmpty()
        if (err.isNotBlank() && msg.what == ModSettingsProtocol.MSG_ERROR) {
            return false to err
        }
        return true to msg.data.getString(ModSettingsProtocol.KEY_ITEMS_JSON).orEmpty()
    }

    private suspend fun request(sendWhat: Int, expectWhat: Int, fill: Bundle.() -> Unit): Message {
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Message>()
        pending[reqId] = deferred
        val outgoing = Message.obtain(null, sendWhat)
        outgoing.replyTo = replyMessenger
        outgoing.data = Bundle().apply {
            putString(ModSettingsProtocol.KEY_REQ_ID, reqId)
            fill()
        }
        val svc = serviceMessenger ?: run {
            pending.remove(reqId)
            error("not bound")
        }
        svc.send(outgoing)
        return try {
            withTimeout(TIMEOUT_MS) { deferred.await() }
        } catch (t: Throwable) {
            pending.remove(reqId)
            _lastError.value = t.message
            throw t
        }
    }

    private fun sendSimple(what: Int, fill: Bundle.() -> Unit) {
        val svc = serviceMessenger ?: return
        val msg = Message.obtain(null, what)
        msg.replyTo = replyMessenger
        msg.data = Bundle().apply(fill)
        svc.send(msg)
    }

    private fun onReply(msg: Message) {
        // Looper recycles msg (and nulls its Bundle) as soon as this handler returns.
        // The waiter resumes on the main thread after that, so it must own a copy.
        if (msg.what == ModSettingsProtocol.MSG_SCHEMA) {
            val arrived = msg.peekData()?.getString(ModSettingsProtocol.KEY_SCHEMA_JSON)?.length ?: -1
            Log.i(TAG, "onReply schemaBytes=$arrived")
        }
        val snapshot = snapshotMessage(msg)
        when (snapshot.what) {
            ModSettingsProtocol.MSG_PUSH_STATE -> {
                onPushState?.invoke(snapshot.data.getString(ModSettingsProtocol.KEY_VALUES_JSON).orEmpty())
                return
            }
            ModSettingsProtocol.MSG_ERROR -> {
                _lastError.value = snapshot.data.getString(ModSettingsProtocol.KEY_ERROR)
            }
        }
        val reqId = snapshot.data.getString(ModSettingsProtocol.KEY_REQ_ID) ?: return
        pending.remove(reqId)?.complete(snapshot)
    }

    private fun snapshotMessage(msg: Message): Message {
        val data = Bundle()
        val src = msg.peekData()
        if (src != null) {
            // Unparcel first, then copy map entries. Bundle(src) can share the
            // parcelled byte buffer that recycle drops with the original Message.
            src.keySet()
            data.putAll(src)
        }
        val copy = Message.obtain(null, msg.what)
        copy.data = data
        return copy
    }
}
