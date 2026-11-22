package com.f0x1d.logfox.core.logging

import android.content.Context
import com.f0x1d.logfox.core.di.IODispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimberFileTree @Inject constructor(
    @ApplicationContext private val context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : Timber.DebugTree() {

    private companion object {
        const val BUFFER_SIZE = 4096
        const val FLUSH_EVERY_LINES = 20
    }

    private val logsFile = context.timberLogFile.apply { delete() }

    // Bounded on purpose: if writing the file ever fails the consumer stops, and an unbounded
    // channel would then buffer every single log line in memory until the app is killed
    private val channel = Channel<String>(
        capacity = BUFFER_SIZE,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // appendText() reopened the file for every single line. One long-lived buffered writer
    // is much cheaper and gives us a single place to flush on shutdown
    private val writerJob = scope.launch {
        val writer = runCatching {
            logsFile.parentFile?.mkdirs()
            logsFile.writeText("")
            BufferedWriter(logsFile.writer())
        }.getOrNull() ?: return@launch

        var pending = 0

        try {
            for (value in channel) {
                // A full disk or a deleted file must not kill the writer loop: the next line
                // should still be attempted
                try {
                    writer.write(value)
                    writer.newLine()

                    // The buffer is only flushed on close, which never happens when the
                    // process is killed (exactly when logs matter). Flush in small batches
                    if (++pending >= FLUSH_EVERY_LINES) {
                        writer.flush()
                        pending = 0
                    }
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                }
            }
        } finally {
            runCatching { writer.flush() }
            runCatching { writer.close() }
        }
    }

    /**
     * Flushes pending lines and stops the writer. Safe to call more than once.
     */
    suspend fun flushAndStop() {
        channel.close()
        writerJob.join()
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val exception = t?.stackTraceToString()?.let { "\n$it" } ?: ""
        val line = "${tag ?: "NO_TAG"}: $message" + exception

        channel.trySend(line)
    }
}

val Context.timberLogFile: File get() = File(filesDir, "timber.log")
