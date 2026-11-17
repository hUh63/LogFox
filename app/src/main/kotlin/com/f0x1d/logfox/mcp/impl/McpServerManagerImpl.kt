package com.f0x1d.logfox.mcp.impl

import android.content.Context
import com.f0x1d.logfox.feature.filters.api.domain.GetAllEnabledFiltersFlowUseCase
import com.f0x1d.logfox.feature.logging.api.domain.ClearLogsUseCase
import com.f0x1d.logfox.feature.logging.api.domain.GetLogsSnapshotUseCase
import com.f0x1d.logfox.feature.logging.api.domain.GetQueryFlowUseCase
import com.f0x1d.logfox.feature.logging.api.domain.StartLoggingUseCase
import com.f0x1d.logfox.feature.logging.api.domain.UpdateQueryUseCase
import com.f0x1d.logfox.feature.terminals.api.domain.GetSelectedTerminalUseCase
import com.f0x1d.logfox.mcp.api.McpServerManager
import com.f0x1d.logfox.mcp.api.McpTool
import com.f0x1d.logfox.mcp.impl.tools.ClearLogsTool
import com.f0x1d.logfox.mcp.impl.tools.GetFiltersTool
import com.f0x1d.logfox.mcp.impl.tools.GetQueryTool
import com.f0x1d.logfox.mcp.impl.tools.ReadLogsTool
import com.f0x1d.logfox.mcp.impl.tools.SetQueryTool
import com.f0x1d.logfox.mcp.impl.tools.StoredLogsTool
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.BindException
import java.net.ServerSocket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class McpServerManagerImpl @Inject constructor(
    private val startLoggingUseCase: StartLoggingUseCase,
    private val clearLogsUseCase: ClearLogsUseCase,
    private val getQueryFlowUseCase: GetQueryFlowUseCase,
    private val updateQueryUseCase: UpdateQueryUseCase,
    private val getAllEnabledFiltersFlowUseCase: GetAllEnabledFiltersFlowUseCase,
    private val getSelectedTerminalUseCase: GetSelectedTerminalUseCase,
    private val getLogsSnapshotUseCase: GetLogsSnapshotUseCase,
    private val gson: Gson,
    @ApplicationContext private val context: Context,
) : McpServerManager {

    private val tools: Map<String, McpTool> by lazy { buildToolsMap() }

    private var server: McpHttpServer? = null

    override var port: Int = McpServerManager.DEFAULT_PORT
        private set

    override val isRunning: Boolean
        get() = server != null

    init {
        Timber.d("[MCP] McpServerManagerImpl initialized")
    }

    override suspend fun start(port: Int) {
        if (server != null) {
            Timber.d("[MCP] Server already running on port ${this.port}")
            return
        }

        Timber.d("[MCP] Server start requested on port $port")
        Timber.d("[MCP] Checking if port $port is free")

        if (!isPortAvailable(port)) {
            Timber.e("[MCP] Port $port is busy, failed to start server")

            return
        }

        try {
            val instance = McpHttpServer(
                port = port,
                gson = gson,
                tools = tools,
                startLoggingUseCase = startLoggingUseCase,
                clearLogsUseCase = clearLogsUseCase,
                getQueryFlowUseCase = getQueryFlowUseCase,
                updateQueryUseCase = updateQueryUseCase,
                getAllEnabledFiltersFlowUseCase = getAllEnabledFiltersFlowUseCase,
                getSelectedTerminalUseCase = getSelectedTerminalUseCase,
            )

            instance.start()

            server = instance
            this.port = port

            Timber.d("[MCP] Server start completed, isRunning=$isRunning")
        } catch (e: Exception) {
            Timber.e(e, "[MCP] Failed to start server on port $port")
        }
    }

    override suspend fun stop() {
        val instance = server

        if (instance == null) {
            Timber.d("[MCP] Server not running, ignoring")
            return
        }

        Timber.d("[MCP] Server stop requested")

        runCatching { instance.stop() }
            .onFailure { Timber.e(it, "[MCP] Failed to stop server") }

        server = null

        Timber.d("[MCP] Server stop completed")
    }

    private fun buildToolsMap(): Map<String, McpTool> {
        Timber.d("[MCP] Building tools map...")

        val result = LinkedHashMap<String, McpTool>()

        listOf(
            ReadLogsTool(
                startLoggingUseCase = startLoggingUseCase,
                getLogsSnapshotUseCase = getLogsSnapshotUseCase,
                getSelectedTerminalUseCase = getSelectedTerminalUseCase,
            ),
            SetQueryTool(updateQueryUseCase = updateQueryUseCase),
            GetQueryTool(getQueryFlowUseCase = getQueryFlowUseCase),
            ClearLogsTool(clearLogsUseCase = clearLogsUseCase),
            GetFiltersTool(getAllEnabledFiltersFlowUseCase = getAllEnabledFiltersFlowUseCase),
            StoredLogsTool(context = context),
        ).forEach { tool ->
            result[tool.name] = tool

            Timber.d("[MCP] Added tool: ${tool.name}")
        }

        Timber.i("[MCP] Tools map built, total ${result.size} tools")

        return result
    }

    private suspend fun isPortAvailable(port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            ServerSocket(port).close()

            true
        } catch (e: BindException) {
            false
        } catch (e: Exception) {
            false
        }
    }
}
