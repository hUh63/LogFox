package com.f0x1d.logfox.feature.recordings.presentation.di

import androidx.lifecycle.SavedStateHandle
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped
import javax.inject.Qualifier

@Module
@InstallIn(ViewModelComponent::class)
object RecordingViewModelModule {

    // A missing deep-link argument must not crash: an unknown id means "no recording"
    private const val INVALID_RECORDING_ID = -1L

    @Provides
    @ViewModelScoped
    @RecordingId
    fun provideRecordingId(savedStateHandle: SavedStateHandle) = savedStateHandle.get<Long>("recording_id") ?: INVALID_RECORDING_ID
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RecordingId
