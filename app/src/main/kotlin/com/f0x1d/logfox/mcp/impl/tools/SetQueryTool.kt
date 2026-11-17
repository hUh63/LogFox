package com.f0x1d.logfox.mcp.impl.tools

import com.f0x1d.logfox.feature.logging.api.domain.UpdateQueryUseCase
import com.f0x1d.logfox.mcp.api.McpTool
import com.f0x1d.logfox.mcp.api.ToolResult
import com.f0x1d.logfox.mcp.impl.stringOrNull
import com.google.gson.JsonObject
import timber.log.Timber

/**
 * `set_query` tool: sets the log filter query.
 */
class SetQueryTool(
    private val updateQueryUseCase: UpdateQueryUseCase,
) : McpTool {

    override val name: String = "set_query"

    override val description: String = "Set the log filter query."

    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add(
            "properties",
            JsonObject().apply {
                add(
                    "query",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Filter query string")
                    },
                )
            },
        )
    }

    override suspend fun call(params: JsonObject): ToolResult = try {
        val query = params.stringOrNull("query").orEmpty()

        updateQueryUseCase(query)

        Timber.d("MCP set_query: query='$query'")

        ToolResult.text("Query set: $query")
    } catch (e: Exception) {
        Timber.e(e, "MCP set_query failed")
        ToolResult.Error("Failed to set query: ${e.message}")
    }
}
