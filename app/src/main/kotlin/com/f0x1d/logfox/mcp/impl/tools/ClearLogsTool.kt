package com.f0x1d.logfox.mcp.impl.tools

import com.f0x1d.logfox.feature.logging.api.domain.ClearLogsUseCase
import com.f0x1d.logfox.mcp.api.McpTool
import com.f0x1d.logfox.mcp.api.ToolResult
import com.google.gson.JsonObject
import timber.log.Timber

/**
 * `clear_logs` tool: clears all logcat buffers.
 */
class ClearLogsTool(
    private val clearLogsUseCase: ClearLogsUseCase,
) : McpTool {

    override val name: String = "clear_logs"

    override val description: String = "Clear all logcat buffers."

    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject())
    }

    override suspend fun call(params: JsonObject): ToolResult = try {
        clearLogsUseCase()

        Timber.d("MCP clear_logs: success")

        ToolResult.text("Logs cleared successfully.")
    } catch (e: Exception) {
        Timber.e(e, "MCP clear_logs failed")
        ToolResult.Error("Failed to clear logs: ${e.message}")
    }
}
