package com.f0x1d.logfox.core.tea

/**
 * Marks a side effect that must eventually reach the UI even if it was produced while the
 * view was stopped (navigation, pickers, "the operation failed" reports).
 *
 * Effects that do NOT implement this are volatile: they are delivered only while a view is
 * listening, because showing an outdated toast after the user comes back is worse than not
 * showing it at all.
 */
interface ReliableSideEffect
