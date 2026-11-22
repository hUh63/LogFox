package com.f0x1d.logfox.feature.logging.impl.data

import com.f0x1d.logfox.core.di.DefaultDispatcher
import com.f0x1d.logfox.feature.logging.api.model.LogLine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.LinkedList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class LogsBufferDataSourceImpl @Inject constructor(
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : LogsBufferDataSource {

    private val buffer = LinkedList<LogLine>()
    private val mutex = Mutex()

    override suspend fun add(logLine: LogLine, limit: Int) = withContext(defaultDispatcher) {
        mutex.withLock {
            buffer.add(logLine)

            // A limit of 0 (the setting accepted it) made every line evict itself: the list
            // was always empty and lastLog() was always null, so restarts reset ids to 0 and
            // crash context collection silently stopped working
            val effectiveLimit = limit.coerceAtLeast(1)

            while (buffer.size > effectiveLimit) {
                buffer.removeFirst()
            }
        }
    }

    override suspend fun getAll(): List<LogLine> = withContext(defaultDispatcher) {
        mutex.withLock { buffer.toList() }
    }

    override suspend fun clear() = withContext(defaultDispatcher) {
        mutex.withLock {
            buffer.clear()
        }
    }

    override suspend fun lastLog(): LogLine? = withContext(defaultDispatcher) {
        mutex.withLock { buffer.lastOrNull() }
    }
}
