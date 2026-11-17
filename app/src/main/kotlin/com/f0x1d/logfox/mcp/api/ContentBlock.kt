package com.f0x1d.logfox.mcp.api

import com.google.gson.JsonObject

/**
 * Content block of a tool result, MCP compatible.
 *
 * @param type one of [TYPE_TEXT], [TYPE_JSON]
 * @param text plain text payload for [TYPE_TEXT]
 * @param data structured payload for [TYPE_JSON]
 */
data class ContentBlock(
    val type: String,
    val text: String? = null,
    val data: JsonObject? = null,
) {

    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_JSON = "json"

        fun text(value: String) = ContentBlock(type = TYPE_TEXT, text = value)

        fun json(value: JsonObject) = ContentBlock(type = TYPE_JSON, data = value)
    }
}
