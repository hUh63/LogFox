package com.f0x1d.logfox.feature.crashes.impl.data

import com.f0x1d.logfox.core.di.IODispatcher
import com.f0x1d.logfox.feature.crashes.api.data.CrashesRepository
import com.f0x1d.logfox.feature.crashes.api.model.AppCrash
import com.f0x1d.logfox.feature.crashes.impl.mapper.toDomainModel
import com.f0x1d.logfox.feature.crashes.impl.mapper.toEntity
import com.f0x1d.logfox.feature.database.api.data.AppCrashDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal class CrashesRepositoryImpl @Inject constructor(
    private val notificationsLocalDataSource: CrashesNotificationsLocalDataSource,
    private val appCrashDataSource: AppCrashDataSource,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : CrashesRepository {

    override fun getAllAsFlow(): Flow<List<AppCrash>> = appCrashDataSource.getAllAsFlow()
        .map { list -> list.map { it.toDomainModel() } }
        .distinctUntilChanged()
        .flowOn(ioDispatcher)

    override fun getByIdAsFlow(id: Long): Flow<AppCrash?> = appCrashDataSource.getByIdAsFlow(id)
        .map { it?.toDomainModel() }
        .flowOn(ioDispatcher)

    override suspend fun getAll(): List<AppCrash> = withContext(ioDispatcher) {
        appCrashDataSource.getAll().map { it.toDomainModel() }
    }

    override suspend fun getById(id: Long): AppCrash? = withContext(ioDispatcher) {
        appCrashDataSource.getById(id)?.toDomainModel()
    }

    override suspend fun getAllByDateAndTime(
        dateAndTime: Long,
        packageName: String,
    ): List<AppCrash> = withContext(ioDispatcher) {
        appCrashDataSource.getAllByDateAndTime(
            dateAndTime = dateAndTime,
            packageName = packageName,
        ).map { it.toDomainModel() }
    }

    override suspend fun insert(appCrash: AppCrash): Long = withContext(ioDispatcher) {
        appCrashDataSource.insert(appCrash.toEntity())
    }

    override suspend fun deleteAllByPackageName(packageName: String) = withContext(ioDispatcher) {
        val crashes = appCrashDataSource.getAllByPackageName(packageName)

        // Rows first, files second: deleting files first left rows pointing at logs that no
        // longer existed when the process died in between (empty detail screen, empty export)
        appCrashDataSource.deleteByPackageName(packageName)

        crashes.forEach {
            it.toDomainModel().deleteAssociatedFiles()
            notificationsLocalDataSource.cancelCrashNotificationFor(it.toDomainModel())
        }
    }

    override suspend fun update(item: AppCrash) = withContext(ioDispatcher) {
        appCrashDataSource.update(item.toEntity())
    }

    override suspend fun delete(item: AppCrash) = withContext(ioDispatcher) {
        // Targeted update instead of writing the whole row: the domain model has no `log`,
        // so a full @Update blanked the legacy crash body.
        // Marked before the files are removed: file deletion cannot be rolled back, so doing
        // it first left a visible crash whose log was already gone if the process died here
        appCrashDataSource.markDeleted(item.id)

        item.deleteAssociatedFiles()

        notificationsLocalDataSource.cancelCrashNotificationFor(item)
    }

    override suspend fun clearDeletedIfNeeded() = withContext(ioDispatcher) {
        appCrashDataSource.clearIfNeeded()
    }

    override suspend fun clear() = withContext(ioDispatcher) {
        val crashes = getAll()

        appCrashDataSource.deleteAll()

        crashes.forEach {
            it.deleteAssociatedFiles()
        }

        notificationsLocalDataSource.cancelAllCrashNotifications()
    }
}
