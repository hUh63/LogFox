package com.f0x1d.logfox.feature.crashes.impl.domain

import com.f0x1d.logfox.feature.crashes.api.data.CrashesRetraceRepository
import com.f0x1d.logfox.feature.crashes.api.domain.IsMappingAvailableUseCase
import javax.inject.Inject

internal class IsMappingAvailableUseCaseImpl @Inject constructor(
    private val crashesRetraceRepository: CrashesRetraceRepository,
) : IsMappingAvailableUseCase {
    override fun invoke() = crashesRetraceRepository.isMappingAvailable()
}
