package com.f0x1d.logfox.feature.crashes.impl.domain

import com.f0x1d.logfox.core.di.IODispatcher
import com.f0x1d.logfox.feature.crashes.api.data.CrashLogRepository
import com.f0x1d.logfox.feature.crashes.api.data.CrashesRepository
import com.f0x1d.logfox.feature.crashes.api.data.CrashesRetraceRepository
import com.f0x1d.logfox.feature.crashes.api.domain.GetCrashAndLogByIdFlowUseCase
import com.f0x1d.logfox.feature.crashes.api.model.AppCrash
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject

internal class GetCrashAndLogByIdFlowUseCaseImpl @Inject constructor(
    private val crashesRepository: CrashesRepository,
    private val crashLogRepository: CrashLogRepository,
    private val crashesRetraceRepository: CrashesRetraceRepository,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : GetCrashAndLogByIdFlowUseCase {
    override suspend fun invoke(id: Long): Flow<Pair<AppCrash, String?>?> =
        crashesRepository.getByIdAsFlow(id).map { appCrash ->
            appCrash?.let {
                it to runCatching {
                    val log = crashLogRepository
                        .readCrashLog(it)
                        .joinToString(separator = "\n")

                    // Deobfuscate the stack trace when a mapping.txt was imported (F0x1d/LogFox#264)
                    crashesRetraceRepository.retrace(log)
                }.getOrNull()
            }
            // Reading (and parsing) the crash log file is disk IO: keep it off the
            // main thread, a big log used to make the details screen unresponsive
        }.flowOn(ioDispatcher)
}
