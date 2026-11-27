package com.f0x1d.logfox.feature.crashes.presentation.details.di

import androidx.lifecycle.SavedStateHandle
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped
import javax.inject.Qualifier

@Module
@InstallIn(ViewModelComponent::class)
object CrashDetailsViewModelModule {

    // A deep link / restored state without the argument used to crash the screen with NPE.
    // An invalid id simply yields "no crash" and the screen stays empty instead
    private const val INVALID_CRASH_ID = -1L

    @Provides
    @ViewModelScoped
    @CrashId
    fun provideCrashId(savedStateHandle: SavedStateHandle) = savedStateHandle.get<Long>("crash_id") ?: INVALID_CRASH_ID
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CrashId
