package com.f0x1d.logfox.mcp.impl.tools

import android.content.Context
import com.f0x1d.logfox.mcp.api.McpTool
import com.f0x1d.logfox.mcp.api.ToolResult
import com.google.gson.JsonObject
import timber.log.Timber
import java.io.File

/**
 * `get_stored_logs` tool: reads LogFox stored crash logs and recording logs
 * from the app private files directory.
 */
class StoredLogsTool(
    private val context: Context,
) : McpTool {

    override val name: String = "get_stored_logs"

    override val description: String =
        "Read LogFox stored crash logs and recording logs from app private files for AI analysis. " +
            "Returns files under files/crashes and files/recordings."

    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject())
    }

    override suspend fun call(params: JsonObject): ToolResult = try {
        val builder = StringBuilder()

        appendDir(builder, File(context.filesDir, "crashes"), "crashes")
        appendDir(builder, File(context.filesDir, "recordings"), "recordings")

        Timber.d("MCP get_stored_logs: ${builder.length} chars")

        ToolResult.text(builder.toString())
    } catch (e: Exception) {
        Timber.e(e, "MCP get_stored_logs failed")
        ToolResult.Error("Failed to read stored logs: ${e.message}")
    }

    private fun appendDir(builder: StringBuilder, dir: File, title: String) {
        builder.append("\n## ").append(title)
        builder.append("\npath=").append(dir.absolutePath).append("\n")

        if (!dir.exists()) {
            builder.append("Directory does not exist.\n")
            return
        }

        val files = dir.listFiles()

        if (files.isNullOrEmpty()) {
            builder.append("No files.\n")
            return
        }

        files.sortedBy { it.name }.forEach { file ->
            if (builder.length >= MAX_TOTAL_CHARS) {
                builder.append("\n[output truncated at $MAX_TOTAL_CHARS chars]\n")

                return
            }

            if (file.isDirectory) {
                appendDir(builder, file, file.name)
            } else {
                builder.append("\n### FILE ").append(file.name).append(" size=").append(file.length()).append("\n")
                builder.append("```log\n")

                try {
                    builder.append(readLimited(file))
                } catch (e: Exception) {
                    builder.append("[read error] ").append(e.message)
                }

                builder.append("\n```\n")
            }
        }
    }

    /**
     * Reads at most [MAX_FILE_CHARS] characters so that huge logs can not blow up
     * the heap (files are still read through a small buffer).
     */
    private fun readLimited(file: File): String = file.bufferedReader().use { reader ->
        val buffer = CharArray(MAX_FILE_CHARS)
        var total = 0

        while (total < MAX_FILE_CHARS) {
            val read = reader.read(buffer, total, MAX_FILE_CHARS - total)

            if (read < 0) {
                break
            }

            total += read
        }

        val text = String(buffer, 0, total)
        val truncated = reader.read() >= 0

        if (truncated) "$text\n[TRUNCATED at $MAX_FILE_CHARS chars]\n" else text
    }

    companion object {
        private const val MAX_FILE_CHARS = 500_000
        private const val MAX_TOTAL_CHARS = 1_500_000
    }
}
