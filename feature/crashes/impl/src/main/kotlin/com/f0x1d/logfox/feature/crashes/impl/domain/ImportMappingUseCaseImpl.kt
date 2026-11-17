package com.f0x1d.logfox.feature.crashes.impl.domain

import android.net.Uri
import com.f0x1d.logfox.feature.crashes.api.data.CrashesRetraceRepository
import com.f0x1d.logfox.feature.crashes.api.domain.ImportMappingUseCase
import javax.inject.Inject

internal class ImportMappingUseCaseImpl @Inject constructor(
    private val crashesRetraceRepository: CrashesRetraceRepository,
) : ImportMappingUseCase {
    override suspend fun invoke(uri: Uri) = crashesRetraceRepository.importMapping(uri)
}
