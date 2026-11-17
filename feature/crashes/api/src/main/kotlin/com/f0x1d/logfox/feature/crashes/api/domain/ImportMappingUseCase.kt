package com.f0x1d.logfox.feature.crashes.api.domain

import android.net.Uri

interface ImportMappingUseCase {
    suspend operator fun invoke(uri: Uri): Boolean
}
