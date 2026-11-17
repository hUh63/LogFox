package com.f0x1d.logfox.mcp.api

/**
 * Manages lifecycle of the built-in MCP (Model Context Protocol) server.
 *
 * The server exposes LogFox logs, filters and query state to AI clients
 * (SSE transport + REST API), see [com.f0x1d.logfox.mcp.impl.McpHttpServer].
 */
interface McpServerManager {

    val isRunning: Boolean

    val port: Int

    suspend fun start(port: Int = DEFAULT_PORT)

    suspend fun stop()

    /**
     * Synchronously stops the server. Must be used from places whose coroutine scope
     * is about to be cancelled (for example `Service.onDestroy`).
     */
    fun stopNow()

    companion object {
        const val DEFAULT_PORT = 8765
    }
}
