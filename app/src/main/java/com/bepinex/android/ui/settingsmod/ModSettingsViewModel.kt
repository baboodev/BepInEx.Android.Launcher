package com.bepinex.android.ui.settingsmod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bepinex.android.remote.ModSettingsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ModSettingsViewModel(
    private val client: ModSettingsClient,
    private val scope: CoroutineScope,
) {
    var schema by mutableStateOf<SchemaDoc?>(null)
        private set
    var status by mutableStateOf("Connecting…")
        private set
    var usingFileFallback by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var snackbar by mutableStateOf<String?>(null)

    val values = mutableStateMapOf<String, ValueEntry>()

    private var loadJob: Job? = null
    private val debounceJobs = mutableMapOf<String, Job>()

    init {
        client.onPushState = { json ->
            applyValuesJson(json)
        }
        scope.launch {
            client.connected.collectLatest { connected ->
                if (connected) {
                    usingFileFallback = false
                    refreshAll()
                } else {
                    status = "Mod not connected"
                }
            }
        }
        scope.launch {
            client.lastError.collectLatest { err ->
                if (!err.isNullOrBlank()) lastError = err
            }
        }
    }

    fun refreshAll() {
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                status = "Loading schema…"
                val schemaJson = client.getSchema()
                val parsed = ModSettingsJson.parseSchema(schemaJson)
                if (parsed == null || parsed.controls.isEmpty()) {
                    usingFileFallback = true
                    status = "Using file fallback"
                    return@launch
                }
                schema = parsed
                status = "Loading values…"
                val valuesJson = client.getValues()
                applyValuesJson(valuesJson)
                usingFileFallback = false
                status = "Connected (${parsed.controls.size} controls)"
            } catch (t: Throwable) {
                usingFileFallback = true
                status = "Using file fallback"
                lastError = t.message
            }
        }
    }

    fun setBool(key: String, value: Boolean) {
        values[key] = ValueEntry(value = value, riskActive = values[key]?.riskActive == true)
        scope.launch {
            val (ok, err) = runCatching { client.setValue(key, "bool", if (value) "true" else "false") }
                .getOrElse { false to (it.message ?: "error") }
            if (!ok) {
                snackbar = err.ifBlank { "Set failed" }
                refreshValuesOnly()
            }
        }
    }

    fun setFloatDebounced(key: String, value: Float, min: Float?, max: Float?) {
        values[key] = ValueEntry(value = value, riskActive = values[key]?.riskActive == true)
        debounceJobs[key]?.cancel()
        debounceJobs[key] = scope.launch {
            delay(300)
            val clamped = value.coerceIn(min ?: value, max ?: value)
            val (ok, err) = runCatching {
                client.setValue(key, "float", clamped.toString())
            }.getOrElse { false to (it.message ?: "error") }
            if (!ok) {
                snackbar = err.ifBlank { "Set failed" }
                refreshValuesOnly()
            }
        }
    }

    fun setInt(key: String, value: Int) {
        values[key] = ValueEntry(value = value, riskActive = values[key]?.riskActive == true)
        scope.launch {
            val (ok, err) = runCatching { client.setValue(key, "int", value.toString()) }
                .getOrElse { false to (it.message ?: "error") }
            if (!ok) {
                snackbar = err.ifBlank { "Set failed" }
                refreshValuesOnly()
            }
        }
    }

    fun setEnum(key: String, optionId: String) {
        values[key] = ValueEntry(value = optionId, riskActive = values[key]?.riskActive == true)
        scope.launch {
            val payload = "\"$optionId\""
            val (ok, err) = runCatching { client.setValue(key, "enum", payload) }
                .getOrElse { false to (it.message ?: "error") }
            if (!ok) {
                snackbar = err.ifBlank { "Set failed" }
                refreshValuesOnly()
            }
        }
    }

    fun setListPick(key: String, itemId: String) {
        values[key] = ValueEntry(value = itemId, riskActive = false)
        scope.launch {
            val payload = "\"$itemId\""
            val (ok, err) = runCatching { client.setValue(key, "list_pick", payload) }
                .getOrElse { false to (it.message ?: "error") }
            if (!ok) {
                snackbar = err.ifBlank { "Set failed" }
            }
        }
    }

    fun invokeAction(key: String, onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        scope.launch {
            val (ok, err) = runCatching { client.invokeAction(key) }
                .getOrElse { false to (it.message ?: "error") }
            if (!ok) {
                snackbar = err.ifBlank { "Action failed" }
            } else {
                snackbar = "OK"
            }
            onDone(ok, err)
        }
    }

    suspend fun fetchLiveList(listId: String): List<LiveListItem> {
        return try {
            val (ok, payload) = client.getLiveList(listId)
            if (!ok) {
                snackbar = payload.ifBlank { "List failed" }
                emptyList()
            } else {
                ModSettingsJson.parseLiveList(payload)
            }
        } catch (t: Throwable) {
            snackbar = t.message
            emptyList()
        }
    }

    fun clearSnackbar() {
        snackbar = null
    }

    private suspend fun refreshValuesOnly() {
        try {
            applyValuesJson(client.getValues())
        } catch (_: Throwable) {
        }
    }

    private fun applyValuesJson(json: String) {
        val parsed = ModSettingsJson.parseValues(json)
        for ((k, v) in parsed) {
            values[k] = v
        }
    }
}
