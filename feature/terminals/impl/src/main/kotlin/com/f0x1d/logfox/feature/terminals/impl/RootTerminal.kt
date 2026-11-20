package com.f0x1d.logfox.feature.terminals.impl

import com.f0x1d.logfox.core.di.IODispatcher
import com.f0x1d.logfox.feature.terminals.api.base.Terminal
import com.f0x1d.logfox.feature.terminals.api.base.TerminalType
import com.f0x1d.logfox.feature.terminals.api.model.TerminalProcess
import com.f0x1d.logfox.feature.terminals.api.model.TerminalResult
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class RootTerminal @Inject constructor(
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : Terminal {

    override val type = TerminalType.Root
    override val title = R.string.root

    override suspend fun isSupported(): Boolean = withContext(ioDispatcher) {
        Shell.getShell().isRoot
    }

    override suspend fun executeNow(vararg command: String): TerminalResult = withContext(ioDispatcher) {
        Shell.cmd(command.joinToString(" ")).exec().run {
            TerminalResult(
                exitCode = code,
                output = out.joinToString("\n"),
                errorOutput = err.joinToString("\n"),
            )
        }
    }

    // It seems not all devices support "su -c ...".
    // I got feedback that LogFox does not read logs, while other apps do.
    // After I implemented libsu permission could be got. So i took a look at libsu code
    // and tried to do the same here
    private fun createProcess(commands: Array<out String>): Process? {
        val process = runCatching { Runtime.getRuntime().exec("su") }.getOrNull() ?: return null

        return runCatching {
            process.outputStream.run {
                write(commands.joinToString(" ").encodeToByteArray())
                write('\n'.code)
                flush()
            }

            process
        }.getOrElse {
            // A denied su prompt makes the write fail with "Broken pipe". The process was
            // already forked, so destroy it and close its pipes - otherwise every attempt
            // leaked a su process and three file descriptors
            runCatching { process.destroy() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }

            null
        }
    }

    override fun execute(vararg command: String): TerminalProcess? = createProcess(command)?.run {
        val process = this

        TerminalProcess(
            output = inputStream,
            error = errorStream,
            input = outputStream,
            destroy = {
                runCatching { process.destroy() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
            },
        )
    }

    override suspend fun exit() = withContext(ioDispatcher) {
        Shell.getShell().waitAndClose()
    }
}
