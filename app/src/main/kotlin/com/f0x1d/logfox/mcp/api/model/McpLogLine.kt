package com.f0x1d.logfox.mcp.api.model

import androidx.annotation.Keep
import com.f0x1d.logfox.feature.logging.api.model.LogLevel
import com.f0x1d.logfox.feature.logging.api.model.LogLine

/**
 * Plain, serializable snapshot of a [LogLine] as exposed by the MCP server.
 *
 * Kept from R8 shrinking/renaming: the class is serialized by Gson, so its field
 * names are part of the wire format.
 */
@Keep
data class McpLogLine(
    val id: Long,
    val dateAndTime: Long,
    val uid: String,
    val pid: String,
    val tid: String,
    val packageName: String?,
    val level: LogLevel,
    val tag: String,
    val content: String,
) {

    fun format(): String = buildString {
        append(uid).append(' ')
        append(pid).append(' ')
        append(tid).append(' ')
        if (packageName != null) append(packageName).append(' ')
        append(level.letter).append('/').append(tag).append(": ").append(content)
    }

    companion object {
        fun from(logLine: LogLine) = McpLogLine(
            id = logLine.id,
            dateAndTime = logLine.dateAndTime,
            uid = logLine.uid,
            pid = logLine.pid,
            tid = logLine.tid,
            packageName = logLine.packageName,
            level = logLine.level,
            tag = logLine.tag,
            content = logLine.content,
        )
    }
}
