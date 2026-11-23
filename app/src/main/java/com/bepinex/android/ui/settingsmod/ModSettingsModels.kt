package com.bepinex.android.ui.settingsmod

import org.json.JSONArray
import org.json.JSONObject

data class SchemaDoc(
    val v: Int,
    val lang: String,
    val tabs: List<SchemaTab>,
    val controls: List<SchemaControl>,
)

data class SchemaTab(
    val id: String,
    val label: String,
    val subs: List<SchemaSub>,
)

data class SchemaSub(
    val id: String,
    val label: String,
)

data class SchemaControl(
    val key: String,
    val kind: String,
    val tab: String,
    val sub: String?,
    val label: String,
    val description: String?,
    val order: Int,
    val risk: String?,
    val min: Float?,
    val max: Float?,
    val step: Float?,
    val options: List<SchemaOption>?,
    val listId: String?,
    val worldRequired: Boolean,
)

data class SchemaOption(
    val id: String,
    val label: String,
)

data class ValueEntry(
    val value: Any?,
    val riskActive: Boolean = false,
)

data class LiveListItem(
    val id: String,
    val label: String,
)

object ModSettingsJson {
    fun parseSchema(json: String): SchemaDoc? {
        if (json.isBlank()) return null
        return try {
            val obj = JSONObject(json)
            val tabsArr = obj.optJSONArray("tabs") ?: JSONArray()
            val tabs = mutableListOf<SchemaTab>()
            for (i in 0 until tabsArr.length()) {
                val t = tabsArr.getJSONObject(i)
                val subsArr = t.optJSONArray("subs") ?: JSONArray()
                val subs = mutableListOf<SchemaSub>()
                for (j in 0 until subsArr.length()) {
                    val s = subsArr.getJSONObject(j)
                    subs.add(SchemaSub(s.optString("id"), s.optString("label")))
                }
                tabs.add(SchemaTab(t.optString("id"), t.optString("label"), subs))
            }
            val controlsArr = obj.optJSONArray("controls") ?: JSONArray()
            val controls = mutableListOf<SchemaControl>()
            for (i in 0 until controlsArr.length()) {
                val c = controlsArr.getJSONObject(i)
                val optsArr = c.optJSONArray("options")
                val options: List<SchemaOption>? = if (optsArr != null) {
                    val list = ArrayList<SchemaOption>(optsArr.length())
                    for (j in 0 until optsArr.length()) {
                        val o = optsArr.getJSONObject(j)
                        list.add(SchemaOption(o.optString("id"), o.optString("label")))
                    }
                    list
                } else null
                controls.add(
                    SchemaControl(
                        key = c.optString("key"),
                        kind = c.optString("kind"),
                        tab = c.optString("tab"),
                        sub = c.optString("sub", null)?.takeIf { it.isNotBlank() },
                        label = c.optString("label"),
                        description = c.optString("description", null)?.takeIf { it.isNotBlank() },
                        order = c.optInt("order", 0),
                        risk = c.optString("risk", null)?.takeIf { it.isNotBlank() },
                        min = if (c.has("min")) c.optDouble("min").toFloat() else null,
                        max = if (c.has("max")) c.optDouble("max").toFloat() else null,
                        step = if (c.has("step")) c.optDouble("step").toFloat() else null,
                        options = options,
                        listId = c.optString("listId", null)?.takeIf { it.isNotBlank() },
                        worldRequired = c.optBoolean("worldRequired", false),
                    )
                )
            }
            SchemaDoc(
                v = obj.optInt("v", 1),
                lang = obj.optString("lang", "en"),
                tabs = tabs,
                controls = controls.sortedBy { it.order },
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun parseValues(json: String): Map<String, ValueEntry> {
        if (json.isBlank()) return emptyMap()
        return try {
            val root = JSONObject(json)
            val values = root.optJSONObject("values") ?: return emptyMap()
            val out = LinkedHashMap<String, ValueEntry>()
            val keys = values.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val entry = values.optJSONObject(k)
                if (entry != null) {
                    out[k] = ValueEntry(
                        value = entry.opt("value"),
                        riskActive = entry.optBoolean("riskActive", false),
                    )
                } else {
                    out[k] = ValueEntry(value = values.opt(k))
                }
            }
            out
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    fun parseLiveList(json: String): List<LiveListItem> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(LiveListItem(o.optString("id"), o.optString("label")))
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun boolValue(entry: ValueEntry?): Boolean {
        val v = entry?.value ?: return false
        return when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            else -> v.toString().equals("true", ignoreCase = true)
        }
    }

    fun floatValue(entry: ValueEntry?, fallback: Float): Float {
        val v = entry?.value ?: return fallback
        return when (v) {
            is Number -> v.toFloat()
            else -> v.toString().toFloatOrNull() ?: fallback
        }
    }

    fun intValue(entry: ValueEntry?, fallback: Int): Int {
        val v = entry?.value ?: return fallback
        return when (v) {
            is Number -> v.toInt()
            else -> v.toString().toIntOrNull() ?: fallback
        }
    }

    fun stringValue(entry: ValueEntry?): String {
        val v = entry?.value ?: return ""
        return v.toString().trim('"')
    }
}
