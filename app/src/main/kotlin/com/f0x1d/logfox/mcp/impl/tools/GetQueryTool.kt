package com.f0x1d.logfox.mcp.impl.tools

import com.f0x1d.logfox.feature.logging.api.domain.GetQueryFlowUseCase
import com.f0x1d.logfox.mcp.api.McpTool
import com.f0x1d.logfox.mcp.api.ToolResult
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * `get_query` tool: returns the current log filter query.
 */
class GetQueryTool(
    private val getQueryFlowUseCase: GetQueryFlowUseCase,
) : McpTool {

    override val name: String = "get_query"

    override val description: String = "Get the current log filter query."

    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject())
    }

    override suspend fun call(params: JsonObject): ToolResult = try {
        val query = getQueryFlowUseCase().first().orEmpty()

        Timber.d("MCP get_query: query='$query'")

        ToolResult.text(query)
    } catch (e: Exception) {
        Timber.e(e, "MCP get_query failed")
        ToolResult.Error("Failed to get query: ${e.message}")
    }
}
