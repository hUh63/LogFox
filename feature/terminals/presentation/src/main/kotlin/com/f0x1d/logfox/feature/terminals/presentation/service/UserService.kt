package com.f0x1d.logfox.feature.terminals.presentation.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.os.ParcelFileDescriptor.AutoCloseOutputStream
import androidx.annotation.Keep
import com.f0x1d.logfox.feature.terminals.api.model.TerminalResult
import com.f0x1d.logfox.feature.terminals.presentation.IUserService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

class UserService() : IUserService.Stub() {

    private val serviceScopeJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceScopeJob)

    private companion object {
        // A shell command that never exits must not pin a binder thread forever
        const val EXECUTE_TIMEOUT_MS = 60_000L

        // Shutdown must always finish, even if a pipe coroutine is stuck in read()
        const val DESTROY_TIMEOUT_MS = 2_000L
    }

    private val latestId = AtomicLong(0L)

    // Binder calls arrive on several threads, a plain HashMap here raced with itself
    private val currentProcesses = ConcurrentHashMap<Long, Process>()

    // Needed for shizuku v13
    @Suppress("UNUSED_PARAMETER")
    @Keep
    constructor(context: Context) : this()

    override fun destroy() {
        // Kill the children BEFORE joining: their pipe coroutines block inside
        // InputStream.read(), which cannot be interrupted by cancellation, so join() would
        // wait forever and exitProcess() would never run - leaking the :service process
        currentProcesses.values.forEach { process -> process.tryDestroy() }
        currentProcesses.clear()

        serviceScope.cancel()

        runBlocking {
            // Bounded on purpose: never hang a binder thread on shutdown
            withTimeoutOrNull(DESTROY_TIMEOUT_MS) { serviceScopeJob.join() }
        }

        exitProcess(0)
    }

    override fun exit() {
        destroy()
    }

    override fun executeNow(command: String?) = runBlocking(Dispatchers.IO) {
        val process = runCatching { Runtime.getRuntime().exec(command) }
            .getOrElse { throwable ->
                return@runBlocking TerminalResult(
                    exitCode = -1,
                    errorOutput = throwable.message ?: "Failed to start the process",
                )
            }

        try {
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
            process.tryDestroy()
        }
    }

    override fun execute(command: String?): Long {
        // Keep the map bounded: finished processes were never removed before
        currentProcesses.entries.removeAll { entry -> !entry.value.isAlive }

        val processId = latestId.incrementAndGet()

        val process = Runtime.getRuntime().exec(command)
        currentProcesses[processId] = process

        return processId
    }

    override fun processOutput(processId: Long): ParcelFileDescriptor? {
        val process = currentProcesses[processId] ?: return null

        return pipeFrom(process.inputStream)
    }

    override fun processError(processId: Long): ParcelFileDescriptor? {
        val process = currentProcesses[processId] ?: return null

        return pipeFrom(process.errorStream)
    }

    private fun pipeFrom(inputStream: InputStream): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()

        serviceScope.launch {
            AutoCloseOutputStream(pipe[1]).use {
                try {
                    inputStream.copyTo(it)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return pipe[0]
    }

    override fun processInput(processId: Long): ParcelFileDescriptor? {
        val process = currentProcesses[processId] ?: return null

        val pipe = ParcelFileDescriptor.createPipe()
        serviceScope.launch {
            AutoCloseInputStream(pipe[0]).use {
                try {
                    it.copyTo(process.outputStream)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return pipe[1]
    }

    override fun destroyProcess(processId: Long) {
        currentProcesses.remove(processId)?.tryDestroy()
    }

    // Cancellable implementation
    private suspend fun InputStream.copyTo(
        out: OutputStream,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
    ): Long = withContext(Dispatchers.IO) {
        var bytesCopied: Long = 0
        val buffer = ByteArray(bufferSize)
        var bytes = read(buffer)

        while (bytes >= 0 && isActive) {
            out.write(buffer, 0, bytes)
            bytesCopied += bytes
            bytes = read(buffer)
        }

        return@withContext bytesCopied
    }

    private fun Process.tryDestroy() = runCatching {
        destroy()
    }
}
