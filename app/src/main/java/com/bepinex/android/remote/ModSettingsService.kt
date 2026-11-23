package com.bepinex.android.remote

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log

class ModSettingsService : Service() {
    companion object {
        private const val TAG = "BugtopiaModSettings"

        @Volatile
        private var instance: ModSettingsService? = null

        /** In-process mod attach (same :game process) — avoids bindService from the mod. */
        @JvmStatic
        fun attachModReply(reply: Messenger?) {
            val svc = instance
            if (svc == null || reply == null) {
                Log.w(TAG, "attachModReply skipped instance=${svc != null}")
                return
            }
            svc.modPeer = reply
            Log.i(TAG, "HELLO mod connected (in-process attach)")
        }
    }

    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger
    @Volatile private var modPeer: Messenger? = null
    @Volatile private var panelPeer: Messenger? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        thread = HandlerThread("ModSettingsSvc").also { it.start() }
        messenger = Messenger(Handler(thread.looper, ::handle))
        Log.i(TAG, "ModSettingsService onCreate process=:game")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        thread.quitSafely()
        modPeer = null
        panelPeer = null
        super.onDestroy()
    }

    private fun handle(msg: Message): Boolean {
        when (msg.what) {
            ModSettingsProtocol.MSG_HELLO -> {
                val role = msg.data?.getString(ModSettingsProtocol.KEY_ROLE)
                val reply = msg.replyTo
                if (reply == null) return true
                when (role) {
                    ModSettingsProtocol.ROLE_MOD -> {
                        modPeer = reply
                        Log.i(TAG, "HELLO mod connected")
                    }
                    ModSettingsProtocol.ROLE_PANEL -> {
                        panelPeer = reply
                        Log.i(TAG, "HELLO panel connected")
                    }
                    else -> Log.w(TAG, "HELLO bad role=$role")
                }
                return true
            }
            ModSettingsProtocol.MSG_GOODBYE -> {
                when (msg.data?.getString(ModSettingsProtocol.KEY_ROLE)) {
                    ModSettingsProtocol.ROLE_MOD -> {
                        modPeer = null
                        Log.i(TAG, "GOODBYE mod")
                    }
                    ModSettingsProtocol.ROLE_PANEL -> {
                        panelPeer = null
                        Log.i(TAG, "GOODBYE panel")
                    }
                }
                return true
            }
            ModSettingsProtocol.MSG_SCHEMA,
            ModSettingsProtocol.MSG_VALUES,
            ModSettingsProtocol.MSG_SET_RESULT,
            ModSettingsProtocol.MSG_ACTION_RESULT,
            ModSettingsProtocol.MSG_LIVE_LIST,
            ModSettingsProtocol.MSG_PUSH_STATE,
            ModSettingsProtocol.MSG_ERROR -> {
                forward(panelPeer, msg)
                return true
            }
            ModSettingsProtocol.MSG_GET_SCHEMA,
            ModSettingsProtocol.MSG_GET_VALUES,
            ModSettingsProtocol.MSG_SET_VALUE,
            ModSettingsProtocol.MSG_INVOKE_ACTION,
            ModSettingsProtocol.MSG_GET_LIVE_LIST -> {
                val mod = modPeer
                if (mod == null) {
                    val err = Message.obtain(null, ModSettingsProtocol.MSG_ERROR)
                    err.data = msg.data ?: android.os.Bundle()
                    err.data.putString(ModSettingsProtocol.KEY_ERROR, "MOD_NOT_CONNECTED")
                    forward(panelPeer, err)
                } else {
                    // Preserve panel replyTo so mod can answer via service or directly;
                    // service forwards mod→panel by code above when mod replies to service.
                    val copy = Message.obtain(null, msg.what)
                    copy.data = msg.data
                    copy.replyTo = messenger // mod always replies to service
                    // Stash original panel reply path: panel already registered as panelPeer.
                    forward(mod, copy)
                }
                return true
            }
            else -> {
                Log.w(TAG, "unknown what=${msg.what}")
                return true
            }
        }
    }

    private fun forward(target: Messenger?, msg: Message) {
        if (target == null) return
        try {
            val copy = Message.obtain(null, msg.what)
            copy.data = msg.data
            target.send(copy)
        } catch (e: RemoteException) {
            Log.w(TAG, "forward failed what=${msg.what}", e)
        }
    }
}
