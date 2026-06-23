package com.gameserver.manager.util

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken

object JsonUtil {
    private val gson = Gson()

    fun toJson(value: Any?): String = gson.toJson(value)

    fun <T> fromJson(json: String, clazz: Class<T>): T = gson.fromJson(json, clazz)

    fun parseMap(json: String): Map<String, Any?> {
        val type = object : TypeToken<Map<String, Any?>>() {}.type
        return gson.fromJson(json, type)
    }

    fun getString(map: Map<String, Any?>, key: String, default: String = ""): String {
        return map[key]?.toString() ?: default
    }

    fun getInt(map: Map<String, Any?>, key: String, default: Int = 0): Int {
        return when (val value = map[key]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    }

    fun getLong(map: Map<String, Any?>, key: String, default: Long = 0L): Long {
        return when (val value = map[key]) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: default
            else -> default
        }
    }

    fun getBoolean(map: Map<String, Any?>, key: String, default: Boolean = false): Boolean {
        return when (val value = map[key]) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull() ?: default
            else -> default
        }
    }

    fun success(data: Any? = null): String {
        return toJson(mapOf("code" to 0, "msg" to "success", "data" to data))
    }

    fun error(message: String, code: Int = -1): String {
        return toJson(mapOf("code" to code, "msg" to message, "data" to null))
    }
}
