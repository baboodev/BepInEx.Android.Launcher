package com.bepinex.android.remote

object ModSettingsProtocol {
    const val ACTION_BIND = "com.bepinex.android.action.MOD_SETTINGS"
    const val MSG_HELLO = 1
    const val MSG_GOODBYE = 2
    const val MSG_GET_SCHEMA = 10
    const val MSG_SCHEMA = 11
    const val MSG_GET_VALUES = 20
    const val MSG_VALUES = 21
    const val MSG_SET_VALUE = 30
    const val MSG_SET_RESULT = 31
    const val MSG_INVOKE_ACTION = 40
    const val MSG_ACTION_RESULT = 41
    const val MSG_GET_LIVE_LIST = 50
    const val MSG_LIVE_LIST = 51
    const val MSG_PUSH_STATE = 60
    const val MSG_ERROR = 90

    const val KEY_ROLE = "role"
    const val KEY_REQ_ID = "reqId"
    const val KEY_SCHEMA_JSON = "schemaJson"
    const val KEY_VALUES_JSON = "valuesJson"
    const val KEY_KEYS_JSON = "keysJson"
    const val KEY_KEY = "key"
    const val KEY_KIND = "kind"
    const val KEY_VALUE_JSON = "valueJson"
    const val KEY_ARGS_JSON = "argsJson"
    const val KEY_RESULT_JSON = "resultJson"
    const val KEY_LIST_ID = "listId"
    const val KEY_ITEMS_JSON = "itemsJson"
    const val KEY_OK = "ok"
    const val KEY_ERROR = "error"
    const val KEY_NOTE = "note"

    const val ROLE_MOD = "mod"
    const val ROLE_PANEL = "panel"
}
