package com.f0x1d.logfox.mcp.impl

import com.google.gson.JsonObject

internal fun JsonObject.stringOrNull(key: String): String? {
    val element = get(key) ?: return null

    if (!element.isJsonPrimitive) return null

    return runCatching { element.asString }.getOrNull()
}

internal fun JsonObject.intOrNull(key: String): Int? {
    val element = get(key) ?: return null

    if (!element.isJsonPrimitive) return null

    return runCatching { element.asInt }.getOrNull()
}

internal fun JsonObject.objOrNull(key: String): JsonObject? {
    val element = get(key) ?: return null

    if (!element.isJsonObject) return null

    return element.asJsonObject
}
