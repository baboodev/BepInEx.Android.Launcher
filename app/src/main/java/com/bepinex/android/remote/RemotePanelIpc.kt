package com.bepinex.android.remote

import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * File IPC under /storage/emulated/0/Bugtopia/ for the rotate-to-mod-remote panel.
 * Panel writes commands; the mod writes state while the session file exists.
 */
object RemotePanelIpc {
    private const val TAG = "BugtopiaRemoteIpc"

    const val KEY_AUTO_FISHING = "EnableAutoFishing"
    const val KEY_INSECT_NET = "EnableInsectNet"
    const val KEY_AURA_FARM = "EnableAuraFarm"
    const val KEY_AUTO_SELL = "EnableAutoSell"
    const val KEY_NOCLIP = "EnableNoclip"
    const val KEY_ANTI_AFK = "EnableAntiAfk"
    const val KEY_BIRD_PHOTO = "EnableBirdPhoto"

    val WHITELIST_KEYS: List<String> = listOf(
        KEY_AUTO_FISHING,
        KEY_INSECT_NET,
        KEY_AURA_FARM,
        KEY_AUTO_SELL,
        KEY_NOCLIP,
        KEY_ANTI_AFK,
        KEY_BIRD_PHOTO,
    )

    data class PanelState(
        val keys: Map<String, Boolean> = emptyMap(),
        val note: String = "",
        val ts: Long = 0L,
    )

    fun bugtopiaRoot(): File =
        File(Environment.getExternalStorageDirectory(), "Bugtopia")

    private fun cmdFile(): File = File(bugtopiaRoot(), "remote-panel-cmd.json")
    private fun stateFile(): File = File(bugtopiaRoot(), "remote-panel-state.json")
    private fun sessionFile(): File = File(bugtopiaRoot(), "remote-panel-session")

    fun writeSessionActive() {
        try {
            val root = bugtopiaRoot()
            if (!root.exists()) root.mkdirs()
            sessionFile().writeText("1")
        } catch (t: Throwable) {
            Log.w(TAG, "writeSessionActive failed", t)
        }
    }

    fun clearSession() {
        try {
            sessionFile().delete()
        } catch (t: Throwable) {
            Log.w(TAG, "clearSession failed", t)
        }
    }

    fun writeSetCommand(key: String, value: Boolean) {
        if (key !in WHITELIST_KEYS) return
        try {
            val root = bugtopiaRoot()
            if (!root.exists()) root.mkdirs()
            val json = JSONObject()
                .put("v", 1)
                .put("id", UUID.randomUUID().toString())
                .put("op", "set")
                .put("key", key)
                .put("value", value)
                .put("ts", System.currentTimeMillis())
            atomicWrite(cmdFile(), json.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "writeSetCommand failed", t)
        }
    }

    fun readState(): PanelState {
        return try {
            val file = stateFile()
            if (!file.isFile) return PanelState()
            val text = file.readText()
            if (text.isBlank()) return PanelState()
            val obj = JSONObject(text)
            val keysObj = obj.optJSONObject("keys") ?: JSONObject()
            val keys = LinkedHashMap<String, Boolean>()
            for (k in WHITELIST_KEYS) {
                if (keysObj.has(k)) keys[k] = keysObj.optBoolean(k, false)
            }
            PanelState(
                keys = keys,
                note = obj.optString("note", ""),
                ts = obj.optLong("ts", 0L),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "readState failed", t)
            PanelState(note = "state read error")
        }
    }

    fun isStateStale(state: PanelState, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (state.ts <= 0L) return true
        return nowMs - state.ts > 2_000L
    }

    private fun atomicWrite(target: File, contents: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(contents)
        if (!tmp.renameTo(target)) {
            target.writeText(contents)
            tmp.delete()
        }
    }
}
