package com.f0x1d.logfox.mcp.impl.tools

import com.f0x1d.logfox.feature.filters.api.domain.GetAllEnabledFiltersFlowUseCase
import com.f0x1d.logfox.mcp.api.McpTool
import com.f0x1d.logfox.mcp.api.ToolResult
import com.f0x1d.logfox.mcp.impl.buildQueryFromFilter
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * `get_filters` tool: returns all enabled log filters.
 */
class GetFiltersTool(
    private val getAllEnabledFiltersFlowUseCase: GetAllEnabledFiltersFlowUseCase,
) : McpTool {

    override val name: String = "get_filters"

    override val description: String = "Get all enabled log filters."

    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject())
    }

    override suspend fun call(params: JsonObject): ToolResult = try {
        val filters = getAllEnabledFiltersFlowUseCase().first()

        Timber.d("MCP get_filters: ${filters.size} filters")

        val array = JsonArray().apply {
            filters.forEach { filter ->
                add(
                    JsonObject().apply {
                        addProperty("name", filter.name.orEmpty())
                        addProperty("including", filter.including)
                        addProperty("query", buildQueryFromFilter(filter))
                    },
                )
            }
        }

        ToolResult.text(array.toString())
    } catch (e: Exception) {
        Timber.e(e, "MCP get_filters failed")
        ToolResult.Error("Failed to get filters: ${e.message}")
    }
}
