package com.f0x1d.logfox.feature.database.api.entity

import java.io.File

data class AppCrashEntity(
    val id: Long = 0,
    val appName: String?,
    val packageName: String,
    val crashType: CrashType,
    val dateAndTime: Long,
    /**
     * Legacy in-database crash body. Rows written by older builds still carry it, and a
     * whole-row `@Update` used to blank it because this field was missing from the mapping.
     */
    @Deprecated("Use logFile")
    val log: String = "",
    val logFile: File? = null,
    val logDumpFile: File? = null,
    val isDeleted: Boolean = false,
    val deletedTime: Long? = null,
)
