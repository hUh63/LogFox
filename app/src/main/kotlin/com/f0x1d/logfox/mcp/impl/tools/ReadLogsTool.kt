package com.f0x1d.logfox.mcp.impl.tools

import com.f0x1d.logfox.feature.logging.api.domain.GetLogsFlowUseCase
import com.f0x1d.logfox.feature.logging.api.domain.GetLogsSnapshotUseCase
import com.f0x1d.logfox.feature.logging.api.model.LogLine
import com.f0x1d.logfox.mcp.api.McpTool
import com.f0x1d.logfox.mcp.api.ToolResult
import com.f0x1d.logfox.mcp.api.model.McpLogLine
import com.f0x1d.logfox.mcp.impl.intOrNull
import com.f0x1d.logfox.mcp.impl.stringOrNull
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapIterable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * `read_logs` tool: reads Android logcat logs.
 */
class ReadLogsTool(
    private val getLogsSnapshotUseCase: GetLogsSnapshotUseCase,
    private val getLogsFlowUseCase: GetLogsFlowUseCase,
) : McpTool {

    override val name: String = "read_logs"

    override val description: String =
        "Read Android logcat logs. Use mode='snapshot' to get buffered logs, " +
            "mode='live' to capture new logs for a few seconds, or mode='dump' for the last single log line. " +
            "Returns formatted log lines as text. Logs are in format: UID PID TID Package Level/Tag: Content"

    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add(
            "properties",
            JsonObject().apply {
                add(
                    "mode",
                    JsonObject().apply {
                        addProperty("type", "string")
                        add(
                            "enum",
                            JsonArray().apply {
                                add("snapshot")
                                add("live")
                                add("dump")
                            },
                        )
                        addProperty(
                            "description",
                            "snapshot: get recent buffered logs (default); " +
                                "live: capture new logs for a few seconds; dump: last single log line",
                        )
                        addProperty("default", "snapshot")
                    },
                )
                add(
                    "count",
                    JsonObject().apply {
                        addProperty("type", "integer")
                        addProperty("description", "Maximum number of log lines to return (default: 100, max: 500)")
                        addProperty("default", 100)
                    },
                )
            },
        )
    }

    override suspend fun call(params: JsonObject): ToolResult = try {
        val mode = params.stringOrNull("mode") ?: MODE_SNAPSHOT
        val count = (params.intOrNull("count") ?: DEFAULT_COUNT).coerceIn(1, MAX_COUNT)

        val lines = when (mode) {
            MODE_LIVE -> readLive(count)
            MODE_DUMP -> listOfNotNull(getLogsSnapshotUseCase().lastOrNull())
            else -> getLogsSnapshotUseCase().takeLast(count)
        }

        Timber.d("MCP read_logs: mode=$mode, count=${lines.size}")

        if (lines.isEmpty()) {
            ToolResult.text("No logs found (mode=$mode).")
        } else {
            ToolResult.text(lines.joinToString(separator = "\n") { McpLogLine.from(it).format() })
        }
    } catch (e: Exception) {
        Timber.e(e, "MCP read_logs failed (mode=${params.stringOrNull("mode")})")
        ToolResult.Error("Failed to read logs: ${e.message}")
    }

    /**
     * Collects the next [count] log lines from the logging buffer that is already
     * shared with the app. Starting another terminal/logcat process here would
     * duplicate the logging session, so only new lines are observed.
     */
    private suspend fun readLive(count: Int): List<LogLine> = withTimeoutOrNull(LIVE_TIMEOUT_MS) {
        var knownIds: Set<Long>? = null

        getLogsFlowUseCase()
            .map { lines ->
                val known = knownIds

                if (known == null) {
                    knownIds = lines.map { it.id }.toSet()

                    emptyList()
                } else {
                    val fresh = lines.filter { it.id !in known }

                    knownIds = known + fresh.map { it.id }

                    fresh
                }
            }
            .filter { it.isNotEmpty() }
            .flatMapIterable { it }
            .take(count)
            .toList()
    } ?: emptyList()

    companion object {
        private const val MODE_SNAPSHOT = "snapshot"
        private const val MODE_LIVE = "live"
        private const val MODE_DUMP = "dump"

        private const val DEFAULT_COUNT = 100
        private const val MAX_COUNT = 500

        private const val LIVE_TIMEOUT_MS = 5000L
    }
}
