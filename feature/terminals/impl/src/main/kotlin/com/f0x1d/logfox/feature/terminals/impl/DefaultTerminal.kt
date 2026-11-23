package com.f0x1d.logfox.feature.terminals.impl

import com.f0x1d.logfox.core.di.IODispatcher
import com.f0x1d.logfox.feature.terminals.api.base.Terminal
import com.f0x1d.logfox.feature.terminals.api.base.TerminalType
import com.f0x1d.logfox.feature.terminals.api.model.TerminalProcess
import com.f0x1d.logfox.feature.terminals.api.model.TerminalResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal open class DefaultTerminal @Inject constructor(
    @IODispatcher protected val ioDispatcher: CoroutineDispatcher,
) : Terminal {

    override val type = TerminalType.Default
    override val title = R.string.terminal_default

    override suspend fun isSupported() = true

    override suspend fun executeNow(vararg command: String) = withContext(ioDispatcher) {
        val process = runCatching { Runtime.getRuntime().exec(command) }
            .getOrElse { throwable ->
                return@withContext TerminalResult(
                    exitCode = -1,
                    errorOutput = throwable.message ?: "Failed to start the process",
                )
            }

        try {
            // A command that never exits used to hold the IO thread forever (there was no
            // timeout at all) and its three streams leaked on every failure
            withTimeoutOrNull(EXECUTE_TIMEOUT_MS) {
                val output = async {
                    process.inputStream.readBytes().decodeToString()
                }
                val error = async {
                    process.errorStream.readBytes().decodeToString()
                }
                val exitCode = process.waitFor()

                TerminalResult(exitCode, output.await(), error.await())
            } ?: TerminalResult(
                exitCode = -1,
                errorOutput = "Command timed out after ${EXECUTE_TIMEOUT_MS / 1000} s",
            )
        } finally {
            runCatching { process.destroy() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

    private companion object {
        const val EXECUTE_TIMEOUT_MS = 30_000L
    }

    override fun execute(vararg command: String) = Runtime.getRuntime().exec(command).run {
        TerminalProcess(
            output = inputStream,
            error = errorStream,
            input = outputStream,
            destroy = {
                // stdout is closed by the caller (bufferedReader().use), but stderr and
                // stdin used to stay open: every collection restart leaked two file
                // descriptors until exec() started failing with EMFILE
                runCatching { errorStream.close() }
                runCatching { outputStream.close() }
                runCatching { destroy() }
            },
        )
    }
}
