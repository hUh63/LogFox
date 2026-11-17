package com.f0x1d.logfox.feature.crashes.impl.domain

import com.f0x1d.logfox.feature.crashes.api.data.CrashesRetraceRepository
import com.f0x1d.logfox.feature.crashes.api.domain.ClearMappingUseCase
import javax.inject.Inject

internal class ClearMappingUseCaseImpl @Inject constructor(
    private val crashesRetraceRepository: CrashesRetraceRepository,
) : ClearMappingUseCase {
    override suspend fun invoke() = crashesRetraceRepository.clearMapping()
}
