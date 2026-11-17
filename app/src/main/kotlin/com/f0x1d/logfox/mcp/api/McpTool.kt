package com.f0x1d.logfox.mcp.api

import com.google.gson.JsonObject

/**
 * A single tool exposed through MCP.
 */
interface McpTool {

    val name: String

    val description: String

    val inputSchema: JsonObject

    suspend fun call(params: JsonObject): ToolResult
}
