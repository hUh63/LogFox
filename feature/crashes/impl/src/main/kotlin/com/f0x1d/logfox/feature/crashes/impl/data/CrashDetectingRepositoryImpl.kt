package com.f0x1d.logfox.feature.crashes.impl.data

import com.f0x1d.logfox.feature.crashes.api.data.CrashDetectingRepository
import com.f0x1d.logfox.feature.logging.api.model.LogLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class CrashDetectingRepositoryImpl @Inject constructor(
    private val crashDataSources: Set<@JvmSuppressWildcards CrashDataSource>,
) : CrashDetectingRepository {

    private val mutexes = crashDataSources.map { Mutex() }

    override suspend fun processLogLine(line: LogLine) {
        // One coroutine + one lock per data source per log line meant thousands of allocations
        // and scheduling hops per second at logcat throughput, while the sources are
        // independent and their work is cheap: sequential calls are enough
        crashDataSources.forEachIndexed { index, dataSource ->
            mutexes[index].withLock {
                runCatching { dataSource.process(line) }
                    .onFailure {
                        if (it is CancellationException) throw it

                        // Java, JNI and ANR detectors share each log line. A failure in one
                        // must not prevent the others from seeing it
                        Timber.w(it, "Crash detector failed for a log line")
                    }
            }
        }
    }

    override suspend fun flushPending(force: Boolean) {
        crashDataSources.forEachIndexed { index, dataSource ->
            mutexes[index].withLock {
                runCatching { dataSource.flushPending(force) }
                    .onFailure {
                        if (it is CancellationException) throw it

                        Timber.w(it, "Failed to flush pending crash")
                    }
            }
        }
    }
}
