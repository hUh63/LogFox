package com.f0x1d.logfox.feature.logging.impl.data

import com.f0x1d.logfox.core.di.DefaultDispatcher
import com.f0x1d.logfox.feature.logging.api.model.LogLine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class LogsDataSourceImpl @Inject constructor(
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : LogsDataSource {

    private val mutableLogs = MutableStateFlow(emptyList<LogLine>())

    override val logs: Flow<List<LogLine>> get() = mutableLogs.asStateFlow()

    override suspend fun updateLogs(logs: List<LogLine>) = withContext(defaultDispatcher) {
        // The buffer already hands out a fresh immutable list, copying it again every few
        // hundred milliseconds just doubled the allocation of a 10k-element array
        mutableLogs.value = logs
    }
}
