package com.f0x1d.logfox.mcp.impl

import com.f0x1d.logfox.feature.filters.api.domain.GetAllEnabledFiltersFlowUseCase
import com.f0x1d.logfox.feature.logging.api.domain.ClearLogsUseCase
import com.f0x1d.logfox.feature.logging.api.domain.GetLogsFlowUseCase
import com.f0x1d.logfox.feature.logging.api.domain.GetQueryFlowUseCase
import com.f0x1d.logfox.feature.logging.api.domain.UpdateQueryUseCase
import com.f0x1d.logfox.mcp.api.ContentBlock
import com.f0x1d.logfox.mcp.api.McpTool
import com.f0x1d.logfox.mcp.api.ToolResult
import com.f0x1d.logfox.mcp.api.model.McpLogLine
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val getLogsFlowUseCase: GetLogsFlowUseCase,
    private val clearLogsUseCase: ClearLogsUseCase,
    private val getQueryFlowUseCase: GetQueryFlowUseCase,
    private val updateQueryUseCase: UpdateQueryUseCase,
    private val getAllEnabledFiltersFlowUseCase: GetAllEnabledFiltersFlowUseCase,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val sessions = ConcurrentHashMap<String, McpSession>()
    private val clientSemaphore = Semaphore(MAX_CLIENTS)

    // Connection count alone was not enough: one client could fire hundreds of concurrent
    // tools/call requests on a single connection (read_logs collects 5 s of live output)
    // and exhaust the shared IO pool
    private val requestSemaphore = Semaphore(MAX_CONCURRENT_REQUESTS)

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
        var failures = 0

        while (running.get()) {
            val client = try {
                socket.accept().also { failures = 0 }
            } catch (e: Exception) {
                if (!running.get()) return

                failures++
                Timber.e(e, "[MCP] Failed to accept connection")

                // Without backoff a persistent accept error (e.g. EMFILE) turned this into a
                // 100% CPU busy loop while `isRunning` still reported true
                delay((100L * failures).coerceAtMost(2_000L))
                null
            } ?: continue

            // Bounded concurrency: every accepted connection used to get its own coroutine on
            // the shared Dispatchers.IO pool, so enough idle clients starved the whole app
            clientSemaphore.acquire()

            scope.launch {
                try {
                    handleClient(client)
                } finally {
                    clientSemaphore.release()
                }
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        try {
            // A client that connects and never sends anything used to hold an IO thread
            // forever (slowloris); the socket read timeout closes it
            client.soTimeout = SOCKET_TIMEOUT_MS

            val output = client.getOutputStream()
            val request = readRequest(BufferedInputStream(client.getInputStream()))

            if (request == null) {
                return
            }

            route(request, output)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable

            // Malformed input can raise an OutOfMemoryError, which `catch (Exception)`
            // used to let escape and kill the whole app process
            Timber.e(throwable, "[MCP] Failed to handle connection")
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun route(request: HttpRequest, output: OutputStream) {
        val path = request.path

        Timber.d("[MCP] ${request.method} $path")

        if (request.oversized) {
            writeJson(
                output,
                JsonObject().apply { addProperty("error", "Request body too large") },
                status = "413 Payload Too Large",
            )

            return
        }

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

            path.startsWith("/tools/") && path.endsWith("/call") && request.method == "POST" ->
                handleToolRest(path, request, output)

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

        try {
            // Both writes are inside try: they used to sit outside, so a client that
            // disconnected immediately left a session in the map forever and every later
            // POST kept pushing responses into a channel nobody reads
            writeSseHeaders(output)
            writeEvent(output, EVENT_ENDPOINT, "/messages?sessionId=${session.id}")

            Timber.d("[MCP] SSE opened, sessionId=${session.id}")

            while (running.get()) {
                val response = withTimeoutOrNull(SSE_KEEPALIVE_MS) { session.channel.receive() }

                if (response == null) {
                    output.write(": keepalive  \n\n".toByteArray())
                    output.flush()
                } else {
                    writeEvent(output, EVENT_MESSAGE, gson.toJson(response))
                }
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable

            Timber.d("[MCP] SSE closed for ${session.id}: ${throwable.message}")
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
            requestSemaphore.withPermit {
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
        writeSseHeaders(output)

        Timber.d("[MCP] GET /logs")

        // Bounded: a slow client used to grow an UNLIMITED channel for as long as it stayed
        // connected, which could end in an OOM
        val lines = Channel<McpLogLine>(capacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        val collecting = scope.launch {
            var knownIds: Set<Long>? = null

            runCatching {
                getLogsFlowUseCase().collect { batch ->
                    val known = knownIds

                    val fresh = if (known == null) {
                        emptyList()
                    } else {
                        batch.filter { it.id !in known }
                    }

                    // Keep only the current snapshot instead of every id ever seen: the old
                    // accumulating set grew without bound for the whole connection
                    knownIds = batch.map { it.id }.toSet()

                    fresh.forEach { line -> lines.send(McpLogLine.from(line)) }
                }
            }.onFailure { Timber.e(it, "[MCP] /logs error") }
        }

        try {
            while (running.get()) {
                val line = withTimeoutOrNull(SSE_KEEPALIVE_MS) { lines.receive() }

                if (line == null) {
                    output.write(": keepalive  \n\n".toByteArray())
                    output.flush()
                } else {
                    writeEvent(output, EVENT_MESSAGE, gson.toJson(line))
                }
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable

            Timber.d("[MCP] /logs stream closed: ${throwable.message}")
        } finally {
            collecting.cancel()
            lines.close()
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

        var headerCount = 0

        while (true) {
            val line = readLine(input) ?: break

            if (line.isEmpty()) {
                break
            }

            if (++headerCount > MAX_HEADERS) {
                return null
            }

            val index = line.indexOf(':')

            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0

        if (contentLength > MAX_BODY_BYTES) {
            return HttpRequest(
                method = method,
                path = target.substringBefore('?'),
                query = emptyMap(),
                body = "",
                oversized = true,
            )
        }

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
                // A request line / header without a newline used to grow this buffer until
                // the process ran out of memory. Refuse the connection instead.
                if (buffer.size() >= MAX_LINE_BYTES) return null

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
            // No Access-Control-Allow-Origin: a wildcard let any web page opened on the
            // device read the whole logcat stream (and clear it) via fetch(). Native MCP
            // clients do not need CORS, browsers must not get through.
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
        // Bounded, and blocking instead of dropping. Dropping silently lost responses for
        // requests that already received 202 Accepted - the client just waited until its
        // own timeout. Backpressure suspends the producing coroutine (which holds a request
        // permit), so a slow reader throttles itself instead of losing data or OOMing
        val channel = Channel<JsonObject>(capacity = 1024)
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val body: String,
        val oversized: Boolean = false,
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
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024
        private const val MAX_HEADERS = 100
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val MAX_CLIENTS = 32
        private const val MAX_CONCURRENT_REQUESTS = 8
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
