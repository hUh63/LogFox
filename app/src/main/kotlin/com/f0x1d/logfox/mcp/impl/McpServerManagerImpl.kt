package com.f0x1d.logfox.mcp.impl

import android.content.Context
import com.f0x1d.logfox.feature.filters.api.domain.GetAllEnabledFiltersFlowUseCase
import com.f0x1d.logfox.feature.logging.api.domain.ClearLogsUseCase
import com.f0x1d.logfox.feature.logging.api.domain.GetLogsFlowUseCase
import com.f0x1d.logfox.feature.logging.api.domain.GetLogsSnapshotUseCase
import com.f0x1d.logfox.feature.logging.api.domain.GetQueryFlowUseCase
import com.f0x1d.logfox.feature.logging.api.domain.UpdateQueryUseCase
import com.f0x1d.logfox.mcp.api.McpServerManager
import com.f0x1d.logfox.mcp.api.McpTool
import com.f0x1d.logfox.mcp.impl.tools.ClearLogsTool
import com.f0x1d.logfox.mcp.impl.tools.GetFiltersTool
import com.f0x1d.logfox.mcp.impl.tools.GetQueryTool
import com.f0x1d.logfox.mcp.impl.tools.ReadLogsTool
import com.f0x1d.logfox.mcp.impl.tools.SetQueryTool
import com.f0x1d.logfox.mcp.impl.tools.StoredLogsTool
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.BindException
import java.net.ServerSocket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class McpServerManagerImpl @Inject constructor(
    private val getLogsFlowUseCase: GetLogsFlowUseCase,
    private val clearLogsUseCase: ClearLogsUseCase,
    private val getQueryFlowUseCase: GetQueryFlowUseCase,
    private val updateQueryUseCase: UpdateQueryUseCase,
    private val getAllEnabledFiltersFlowUseCase: GetAllEnabledFiltersFlowUseCase,
    private val getLogsSnapshotUseCase: GetLogsSnapshotUseCase,
    private val gson: Gson,
    @ApplicationContext private val context: Context,
) : McpServerManager {

    private val tools: Map<String, McpTool> by lazy { buildToolsMap() }

    private var server: McpHttpServer? = null

    // start()/stop() are called from onCreate and from every onStartCommand action, each in
    // its own coroutine. Two overlapping starts both saw `server == null`, both bound the
    // port and the loser reported "port N is already in use" while the server was running
    private val lifecycleMutex = Mutex()

    override var port: Int = McpServerManager.DEFAULT_PORT
        private set

    override val isRunning: Boolean
        get() = server != null

    init {
        Timber.d("[MCP] McpServerManagerImpl initialized")
    }

    override suspend fun start(port: Int) = lifecycleMutex.withLock {
        startLocked(port)
    }

    private suspend fun startLocked(port: Int) {
        if (server != null) {
            Timber.d("[MCP] Server already running on port ${this.port}")

            publishState(error = null)

            return
        }

        Timber.d("[MCP] Server start requested on port $port")
        Timber.d("[MCP] Checking if port $port is free")

        if (!isPortAvailable(port)) {
            Timber.e("[MCP] Port $port is busy, failed to start server")

            publishState(error = "port $port is already in use")

            return
        }

        try {
            val instance = McpHttpServer(
                port = port,
                gson = gson,
                tools = tools,
                getLogsFlowUseCase = getLogsFlowUseCase,
                clearLogsUseCase = clearLogsUseCase,
                getQueryFlowUseCase = getQueryFlowUseCase,
                updateQueryUseCase = updateQueryUseCase,
                getAllEnabledFiltersFlowUseCase = getAllEnabledFiltersFlowUseCase,
            )

            instance.start()

            server = instance
            this.port = port

            publishState(error = null)

            Timber.d("[MCP] Server start completed, isRunning=$isRunning")
        } catch (e: Exception) {
            Timber.e(e, "[MCP] Failed to start server on port $port")

            publishState(error = e.message ?: e::class.java.simpleName)
        }
    }

    override suspend fun stop() = lifecycleMutex.withLock {
        stopLocked()
    }

    private suspend fun stopLocked() {
        if (server == null) {
            Timber.d("[MCP] Server not running, ignoring")

            publishState(error = null)

            return
        }

        Timber.d("[MCP] Server stop requested")

        stopNow()

        Timber.d("[MCP] Server stop completed")
    }

    override fun stopNow() {
        val instance = server ?: return

        runCatching { instance.stop() }
            .onFailure { Timber.e(it, "[MCP] Failed to stop server") }

        server = null

        publishState(error = null)

        Timber.d("[MCP] Server stopped successfully")
    }

    /**
     * Mirrors the current server state into default shared preferences so that the
     * settings screen (which lives in another Gradle module) can render it.
     */
    private fun publishState(error: String?) {
        runCatching {
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(PREF_MCP_SERVER_RUNNING, server != null)
                .putInt(PREF_MCP_SERVER_ACTIVE_PORT, port)
                .putString(PREF_MCP_SERVER_ERROR, error)
                .apply()
        }
    }

    private fun buildToolsMap(): Map<String, McpTool> {
        Timber.d("[MCP] Building tools map...")

        val result = LinkedHashMap<String, McpTool>()

        listOf(
            ReadLogsTool(
                getLogsSnapshotUseCase = getLogsSnapshotUseCase,
                getLogsFlowUseCase = getLogsFlowUseCase,
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

    private companion object {
        const val PREF_MCP_SERVER_RUNNING = "pref_mcp_server_running"
        const val PREF_MCP_SERVER_ACTIVE_PORT = "pref_mcp_server_active_port"
        const val PREF_MCP_SERVER_ERROR = "pref_mcp_server_error"
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
