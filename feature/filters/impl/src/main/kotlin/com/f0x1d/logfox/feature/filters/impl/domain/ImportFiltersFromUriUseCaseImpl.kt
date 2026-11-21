package com.f0x1d.logfox.feature.filters.impl.domain

import android.net.Uri
import com.f0x1d.logfox.feature.export.api.data.ExportRepository
import com.f0x1d.logfox.feature.filters.api.domain.CreateAllFiltersUseCase
import com.f0x1d.logfox.feature.filters.api.domain.ImportFiltersFromUriUseCase
import com.f0x1d.logfox.feature.filters.api.model.UserFilter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject

internal class ImportFiltersFromUriUseCaseImpl @Inject constructor(
    private val exportRepository: ExportRepository,
    private val createAllFiltersUseCase: CreateAllFiltersUseCase,
    private val gson: Gson,
) : ImportFiltersFromUriUseCase {

    override suspend fun invoke(uri: Uri): Result<Unit> = runCatching {
        // A null here means the URI could not be read at all. Treating it as success hid
        // the failure: the user saw "nothing happened" and had no way to tell it apart
        // from an empty file
        val content = exportRepository.readContentFromUri(uri)
            ?: error("Cannot read filters from $uri")

        val filters = gson.fromJson<List<UserFilter>>(
            content,
            object : TypeToken<List<UserFilter>>() {}.type,
        ) ?: error("Empty filters payload in $uri")

        createAllFiltersUseCase(filters)
    }
}
