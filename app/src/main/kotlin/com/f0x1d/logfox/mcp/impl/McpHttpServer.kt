package com.f0x1d.logfox.mcp.impl

import com.f0x1d.logfox.feature.filters.api.domain.GetAllEnabledFiltersFlowUseCase
import com.f0x1d.logfox.feature.logging.api.domain.ClearLogsUseCase
import com.f0x1d.logfox.feature.logging.api.domain.GetLogsSnapshotUseCase
import com.f0x1d.logfox.feature.logging.api.domain.GetQueryFlowUseCase
import com.f0x1d.logfox.feature.logging.api.domain.StartLoggingUseCase
import com.f0x1d.logfox.feature.logging.api.domain.UpdateQueryUseCase
import com.f0x1d.logfox.feature.terminals.api.domain.GetSelectedTerminalUseCase
import com.f0x1d.logfox.mcp.api.ContentBlock
import com.f0x1d.logfox.mcp.api.McpTool
import com.f0x1d.logfox.mcp.api.ToolResult
import com.f0x1d.logfox.mcp.api.model.McpLogLine
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal, dependency-free HTTP server exposing LogFox logs over MCP
 * (Server-Sent Events transport) plus a small REST API.
 *
 * Supported endpoints:
 * - `GET /sse` - MCP SSE transport (returns `endpoint` event with message URL)
 * - `POST /messages?sessionId=<id>` - MCP JSON-RPC message endpoint
 * - `POST /mcp` - plain JSON-RPC entry (response returned in place)
 * - `GET /logs` - SSE stream of new log lines
 * - `POST /logs/clear`, `GET /query`, `POST /query/set`, `GET /filters`
 * - `GET /tools`, `POST /tools/{name}/call`
 * - `GET /health`, `GET /help`
 */
internal class McpHttpServer(
    private val port: Int,
    private val gson: Gson,
    private val tools: Map<String, McpTool>,
    private val startLoggingUseCase: StartLoggingUseCase,
    private val clearLogsUseCase: ClearLogsUseCase,
    private val getQueryFlowUseCase: GetQueryFlowUseCase,
    private val updateQueryUseCase: UpdateQueryUseCase,
    private val getAllEnabledFiltersFlowUseCase: GetAllEnabledFiltersFlowUseCase,
    private val getSelectedTerminalUseCase: GetSelectedTerminalUseCase,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val sessions = ConcurrentHashMap<String, McpSession>()

    private var serverSocket: ServerSocket? = null

    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(HOST, port))

        serverSocket = socket
        running.set(true)

        scope.launch { acceptLoop(socket) }

        Timber.d("[MCP] Server started successfully on port $port")
    }

    fun stop() {
        running.set(false)

        runCatching { serverSocket?.close() }
        serverSocket = null

        sessions.values.forEach { session -> runCatching { session.channel.close() } }
        sessions.clear()

        scope.coroutineContext.cancelChildren()

        Timber.d("[MCP] Server stopped successfully")
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running.get()) {
                    Timber.e(e, "[MCP] Failed to accept connection")
                }

                null
            } ?: continue

            scope.launch { handleClient(client) }
        }
    }

    private suspend fun handleClient(client: Socket) {
        try {
            val output = client.getOutputStream()
            val request = readRequest(BufferedInputStream(client.getInputStream()))

            if (request == null) {
                return
            }

            route(request, output)
        } catch (e: Exception) {
            Timber.e(e, "[MCP] Failed to handle connection")
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun route(request: HttpRequest, output: OutputStream) {
        val path = request.path

        Timber.d("[MCP] ${request.method} $path")

        when {
            request.method == METHOD_OPTIONS -> writeResponse(output, "204 No Content", CONTENT_TYPE_TEXT, ByteArray(0))

            path == "/help" || path == "/" -> writeJson(output, buildHelpDoc())

            path == "/health" -> writeJson(
                output,
                JsonObject().apply {
                    addProperty("status", "ok")
                    addProperty("server", SERVER_NAME)
                    addProperty("port", port)
                    addProperty("tools", tools.size)
                },
            )

            path == "/sse" || path == "/mcp/sse" -> handleSse(output)

            path == "/messages" || path == "/mcp/messages" -> handleMessagePost(request, output)

            path == "/mcp" -> handleMcpPost(request, output)

            path == "/logs" -> handleLogsSse(output)

            path == "/logs/clear" -> handleClearLogs(output)

            path == "/query" -> handleGetQuery(output)

            path == "/query/set" -> handleSetQuery(request, output)

            path == "/filters" -> writeJson(output, filtersJson())

            path == "/tools" -> writeJson(output, toolsListJson())

            path.startsWith("/tools/") && path.endsWith("/call") -> handleToolRest(path, request, output)

            else -> writeJson(
                output,
                JsonObject().apply {
                    addProperty("error", "Not found: $path")
                    add("endpoints", endpointDescriptions())
                },
                status = "404 Not Found",
            )
        }
    }

    // region MCP transport

    private suspend fun handleSse(output: OutputStream) {
        val session = McpSession(UUID.randomUUID().toString())

        sessions[session.id] = session

        writeSseHeaders(output)
        writeEvent(output, EVENT_ENDPOINT, "/messages?sessionId=${session.id}")

        Timber.d("[MCP] SSE opened, sessionId=${session.id}")

        try {
            while (running.get()) {
                val response = withTimeoutOrNull(SSE_KEEPALIVE_MS) { session.channel.receive() }

                if (response == null) {
                    output.write(": keepalive  \n\n".toByteArray())
                    output.flush()
                } else {
                    writeEvent(output, EVENT_MESSAGE, gson.toJson(response))
                }
            }
        } catch (e: Exception) {
            Timber.d("[MCP] SSE closed for ${session.id}: ${e.message}")
        } finally {
            sessions.remove(session.id)
            session.channel.close()

            Timber.d("[MCP] SSE closed for ${session.id}")
        }
    }

    private suspend fun handleMessagePost(request: HttpRequest, output: OutputStream) {
        val sessionId = request.query["sessionId"]

        if (sessionId.isNullOrEmpty()) {
            writeJson(
                output,
                JsonObject().apply { addProperty("error", "Missing sessionId") },
                status = "400 Bad Request",
            )
            return
        }

        val session = sessions[sessionId]

        if (session == null) {
            writeJson(
                output,
                JsonObject().apply { addProperty("error", "Unknown sessionId: $sessionId") },
                status = "404 Not Found",
            )
            return
        }

        val message = parseJson(request.body)

        if (message == null) {
            writeJson(
                output,
                JsonObject().apply { addProperty("error", "Invalid JSON") },
                status = "400 Bad Request",
            )
            return
        }

        Timber.d("[MCP] Processing msg for $sessionId")

        writeResponse(output, "202 Accepted", CONTENT_TYPE_TEXT, "Accepted".toByteArray())

        scope.launch {
            try {
                val response = handleJsonRpc(message)

                if (response != null) {
                    session.channel.send(response)
                }
            } catch (e: Exception) {
                Timber.e(e, "[MCP] Failed to send to $sessionId")
            }
        }
    }

    private suspend fun handleMcpPost(request: HttpRequest, output: OutputStream) {
        val message = parseJson(request.body)

        if (message == null) {
            writeJson(
                output,
                errorResponse(null, -32700, "Parse error"),
                status = "400 Bad Request",
            )
            return
        }

        val response = try {
            handleJsonRpc(message)
        } catch (e: Exception) {
            Timber.e(e, "[MCP] /mcp error")
            errorResponse(message.get("id"), -32603, "Internal error: ${e.message}")
        }

        if (response == null) {
            writeResponse(output, "202 Accepted", CONTENT_TYPE_TEXT, ByteArray(0))
        } else {
            writeJson(output, response)
        }
    }

    private suspend fun handleJsonRpc(message: JsonObject): JsonObject? {
        val method = message.stringOrNull("method")
        val id = message.get("id")

        Timber.d("[MCP] JSON-RPC: method=$method")

        if (method == null) {
            return errorResponse(id, -32600, "Invalid Request")
        }

        // Notifications never receive a response
        if (id == null || id.isJsonNull) {
            return null
        }

        return when (method) {
            METHOD_INITIALIZE -> successResponse(id, initializeResult())
            METHOD_PING -> successResponse(id, JsonObject())
            METHOD_TOOLS_LIST -> successResponse(id, toolsListJson())
            METHOD_TOOLS_CALL -> successResponse(id, callTool(message.objOrNull("params")))
            METHOD_NOTIFICATIONS_INITIALIZED -> null
            else -> errorResponse(id, -32601, "Method not found: $method")
        }
    }

    private fun initializeResult(): JsonObject = JsonObject().apply {
        addProperty("protocolVersion", PROTOCOL_VERSION)

        add(
            "capabilities",
            JsonObject().apply {
                add("tools", JsonObject().apply { addProperty("listChanged", false) })
            },
        )

        add(
            "serverInfo",
            JsonObject().apply {
                addProperty("name", SERVER_NAME)
                addProperty("version", SERVER_VERSION)
            },
        )

        addProperty("instructions", "Use read_logs to read Android logcat logs, get_stored_logs for stored crash/recording logs.")
    }

    private suspend fun callTool(params: JsonObject?): JsonObject {
        val name = params?.stringOrNull("name")

        if (name.isNullOrEmpty()) {
            return toolErrorJson("Missing tool name")
        }

        val tool = tools[name]

        if (tool == null) {
            return toolErrorJson("Unknown tool: $name (available: ${tools.keys.joinToString()})")
        }

        val arguments = params.objOrNull("arguments") ?: JsonObject()

        return toolResultJson(tool.call(arguments))
    }

    private suspend fun toolResultJson(result: ToolResult): JsonObject = when (result) {
        is ToolResult.Value -> JsonObject().apply { add("content", blocksJson(result.content)) }

        is ToolResult.Stream -> {
            val blocks = withTimeoutOrNull(STREAM_TIMEOUT_MS) { result.flow.toList() } ?: emptyList()

            JsonObject().apply { add("content", blocksJson(blocks)) }
        }

        is ToolResult.Error -> toolErrorJson(result.message)
    }

    private fun toolErrorJson(message: String): JsonObject = JsonObject().apply {
        add(
            "content",
            JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("type", ContentBlock.TYPE_TEXT)
                        addProperty("text", message)
                    },
                )
            },
        )

        addProperty("isError", true)
    }

    private fun blocksJson(blocks: List<ContentBlock>): JsonArray = JsonArray().apply {
        blocks.forEach { block ->
            add(
                JsonObject().apply {
                    addProperty("type", ContentBlock.TYPE_TEXT)
                    addProperty("text", block.text ?: block.data?.toString().orEmpty())
                },
            )
        }
    }

    // endregion

    // region REST endpoints

    private suspend fun handleLogsSse(output: OutputStream) {
        val terminal = runCatching { getSelectedTerminalUseCase() }.getOrNull()

        writeSseHeaders(output)

        if (terminal == null) {
            writeEvent(output, EVENT_ERROR, "No terminal selected")
            return
        }

        Timber.d("[MCP] GET /logs")

        try {
            startLoggingUseCase(terminal).collect { line ->
                writeEvent(output, EVENT_MESSAGE, gson.toJson(McpLogLine.from(line)))
            }
        } catch (e: Exception) {
            Timber.e(e, "[MCP] /logs error")
        }
    }

    private suspend fun handleClearLogs(output: OutputStream) {
        try {
            clearLogsUseCase()

            writeJson(output, okJson("Logs cleared successfully."))
        } catch (e: Exception) {
            Timber.e(e, "[MCP] /logs/clear error")

            writeJson(output, JsonObject().apply { addProperty("error", e.message.orEmpty()) }, status = "500 Internal Server Error")
        }
    }

    private suspend fun handleGetQuery(output: OutputStream) {
        val query = runCatching { getQueryFlowUseCase().first() }.getOrNull().orEmpty()

        writeJson(output, JsonObject().apply { addProperty("query", query) })
    }

    private suspend fun handleSetQuery(request: HttpRequest, output: OutputStream) {
        val body = parseJson(request.body)
        val query = body?.stringOrNull("query") ?: request.body.takeIf { it.isNotBlank() }

        if (query == null) {
            writeJson(
                output,
                JsonObject().apply { addProperty("error", "Missing query") },
                status = "400 Bad Request",
            )
            return
        }

        updateQueryUseCase(query)

        writeJson(output, okJson("Query set: $query"))
    }

    private suspend fun handleToolRest(path: String, request: HttpRequest, output: OutputStream) {
        val name = path.removePrefix("/tools/").removeSuffix("/call")

        val tool = tools[name]

        if (tool == null) {
            writeJson(
                output,
                JsonObject().apply { addProperty("error", "Unknown tool: $name") },
                status = "404 Not Found",
            )
            return
        }

        val arguments = parseJson(request.body) ?: JsonObject()

        writeJson(output, toolResultJson(tool.call(arguments)))
    }

    private suspend fun filtersJson(): JsonElement = try {
        val filters = getAllEnabledFiltersFlowUseCase().first()

        JsonArray().apply {
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
    } catch (e: Exception) {
        Timber.e(e, "[MCP] Failed to load filters")

        JsonArray()
    }

    private fun toolsListJson(): JsonObject = JsonObject().apply {
        add(
            "tools",
            JsonArray().apply {
                tools.values.forEach { tool ->
                    add(
                        JsonObject().apply {
                            addProperty("name", tool.name)
                            addProperty("description", tool.description)
                            add("inputSchema", tool.inputSchema)
                        },
                    )
                }
            },
        )
    }

    private fun buildHelpDoc(): JsonObject = JsonObject().apply {
        addProperty("server", "LogFox MCP Server")
        addProperty("port", port)
        addProperty("description", "LogFox MCP Server - Standard MCP SSE Transport + REST API")

        addProperty("protocol_version", PROTOCOL_VERSION)

        add(
            "mcp_transport",
            JsonObject().apply {
                addProperty("sse_endpoint", "GET /sse")
                addProperty("message_endpoint", "POST /messages?sessionId=<id>")
                addProperty("protocol_version", PROTOCOL_VERSION)
            },
        )

        add("endpoints", endpointDescriptions())

        add(
            "tools",
            JsonArray().apply {
                tools.values.forEach { tool ->
                    add(
                        JsonObject().apply {
                            addProperty("name", tool.name)
                            addProperty("description", tool.description)
                            addProperty("params", tool.inputSchema.toString())
                        },
                    )
                }
            },
        )
    }

    private fun endpointDescriptions(): JsonArray = JsonArray().apply {
        ENDPOINTS.forEach { (path, method, description) ->
            add(
                JsonObject().apply {
                    addProperty("path", path)
                    addProperty("method", method)
                    addProperty("description", description)
                },
            )
        }
    }

    // endregion

    // region HTTP plumbing

    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')

        if (parts.size < 2) {
            return null
        }

        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = LinkedHashMap<String, String>()

        while (true) {
            val line = readLine(input) ?: break

            if (line.isEmpty()) {
                break
            }

            val index = line.indexOf(':')

            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0

        val body = readBody(input, contentLength)

        val path = target.substringBefore('?')
        val queryString = target.substringAfter('?', "")

        val query = if (queryString.isEmpty()) {
            emptyMap()
        } else {
            queryString.split('&').mapNotNull { pair ->
                if (pair.isEmpty()) {
                    return@mapNotNull null
                }

                val key = decode(pair.substringBefore('='))
                val value = decode(pair.substringAfter('=', ""))

                key to value
            }.toMap()
        }

        return HttpRequest(method = method, path = path, query = query, body = body)
    }

    private fun readBody(input: BufferedInputStream, length: Int): String {
        if (length <= 0) {
            return ""
        }

        val buffer = ByteArray(length)
        var read = 0

        while (read < length) {
            val count = input.read(buffer, read, length - read)

            if (count < 0) {
                break
            }

            read += count
        }

        return String(buffer, 0, read, Charsets.UTF_8)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buffer = ByteArrayOutputStream()

        while (true) {
            val byte = input.read()

            if (byte == -1) {
                return if (buffer.size() == 0) null else buffer.toString(Charsets.UTF_8.name())
            }

            if (byte == '\n'.code) {
                break
            }

            if (byte != '\r'.code) {
                buffer.write(byte)
            }
        }

        return buffer.toString(Charsets.UTF_8.name())
    }

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun writeResponse(
        output: OutputStream,
        status: String,
        contentType: String,
        body: ByteArray,
    ) {
        val header = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("\r\n")
        }

        output.write(header.toByteArray())
        output.write(body)
        output.flush()

        runCatching { output.close() }
    }

    private fun writeJson(
        output: OutputStream,
        value: JsonElement,
        status: String = "200 OK",
    ) = writeResponse(output, status, CONTENT_TYPE_JSON, gson.toJson(value).toByteArray())

    private fun writeSseHeaders(output: OutputStream) {
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: keep-alive\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("\r\n")
        }

        output.write(header.toByteArray())
        output.flush()
    }

    private fun writeEvent(output: OutputStream, event: String, data: String) {
        val payload = "event: $event\ndata: $data\n\n"

        output.write(payload.toByteArray())
        output.flush()
    }

    private fun parseJson(body: String): JsonObject? = runCatching {
        JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()

    private fun successResponse(id: JsonElement?, result: JsonElement): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id ?: com.google.gson.JsonNull.INSTANCE)
        add("result", result)
    }

    private fun errorResponse(id: JsonElement?, code: Int, message: String): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id ?: com.google.gson.JsonNull.INSTANCE)
        add(
            "error",
            JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
            },
        )
    }

    private fun okJson(message: String): JsonObject = JsonObject().apply {
        addProperty("status", "ok")
        addProperty("message", message)
    }

    // endregion

    private class McpSession(
        val id: String,
    ) {
        val channel = Channel<JsonObject>(Channel.UNLIMITED)
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val body: String,
    )

    companion object {
        private const val TAG = "[MCP]"

        const val HOST = "0.0.0.0"

        const val PROTOCOL_VERSION = "2024-11-05"
        const val SERVER_NAME = "LogFox"
        const val SERVER_VERSION = "1.0"

        private const val METHOD_OPTIONS = "OPTIONS"

        private const val METHOD_INITIALIZE = "initialize"
        private const val METHOD_PING = "ping"
        private const val METHOD_TOOLS_LIST = "tools/list"
        private const val METHOD_TOOLS_CALL = "tools/call"
        private const val METHOD_NOTIFICATIONS_INITIALIZED = "notifications/initialized"

        private const val EVENT_ENDPOINT = "endpoint"
        private const val EVENT_MESSAGE = "message"
        private const val EVENT_ERROR = "error"

        private const val CONTENT_TYPE_TEXT = "text/plain; charset=utf-8"
        private const val CONTENT_TYPE_JSON = "application/json; charset=utf-8"

        private const val SSE_KEEPALIVE_MS = 15_000L
        private const val STREAM_TIMEOUT_MS = 10_000L

        private val ENDPOINTS = listOf(
            Triple("/sse", "GET", "MCP SSE transport"),
            Triple("/messages?sessionId=xxx", "POST", "MCP JSON-RPC"),
            Triple("/logs", "GET", "SSE log stream"),
            Triple("/logs/clear", "POST", "Clear logs"),
            Triple("/query", "GET", "Get query"),
            Triple("/query/set", "POST", "Set query"),
            Triple("/filters", "GET", "Get filters"),
            Triple("/tools", "GET", "List tools"),
            Triple("/tools/{name}/call", "POST", "Call tool"),
            Triple("/mcp", "POST", "JSON-RPC entry"),
            Triple("/health", "GET", "Health check"),
            Triple("/help", "GET", "This help"),
        )
    }
}
