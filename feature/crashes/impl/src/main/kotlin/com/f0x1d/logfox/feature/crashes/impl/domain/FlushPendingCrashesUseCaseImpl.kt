package com.f0x1d.logfox.feature.crashes.impl.domain

import com.f0x1d.logfox.feature.crashes.api.data.CrashDetectingRepository
import com.f0x1d.logfox.feature.crashes.api.domain.FlushPendingCrashesUseCase
import javax.inject.Inject

internal class FlushPendingCrashesUseCaseImpl @Inject constructor(
    private val crashDetectingRepository: CrashDetectingRepository,
) : FlushPendingCrashesUseCase {

    override suspend fun invoke(force: Boolean) {
        crashDetectingRepository.flushPending(force)
    }
}
