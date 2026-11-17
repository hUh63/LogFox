package com.f0x1d.logfox.mcp.api

import kotlinx.coroutines.flow.Flow

/**
 * Result of [McpTool.call].
 */
sealed interface ToolResult {

    data class Value(val content: List<ContentBlock>) : ToolResult

    data class Stream(val flow: Flow<ContentBlock>) : ToolResult

    data class Error(val message: String) : ToolResult

    companion object {
        fun text(value: String): ToolResult = Value(listOf(ContentBlock.text(value)))
    }
}
