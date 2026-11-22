package com.f0x1d.logfox.feature.setup.presentation

internal data class SetupState(
    val showAdbDialog: Boolean,
    val adbCommand: String,
    // A grant request is in flight. Clicking again while the su/Shizuku dialog is open
    // started a second request in parallel and both results raced each other
    val isRunning: Boolean = false,
)
