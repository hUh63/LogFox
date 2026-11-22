package com.f0x1d.logfox.feature.database.impl.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.f0x1d.logfox.feature.logging.api.model.LogLevel

@Entity(tableName = "UserFilter")
internal data class UserFilterRoomEntity(
    @ColumnInfo(name = "name") val name: String? = null,
    @ColumnInfo(name = "including") val including: Boolean = true,
    @ColumnInfo(name = "allowed_levels") val allowedLevels: List<LogLevel> = emptyList(),
    @ColumnInfo(name = "uid") val uid: String? = null,
    @ColumnInfo(name = "pid") val pid: String? = null,
    @ColumnInfo(name = "tid") val tid: String? = null,
    @ColumnInfo(name = "package_name") val packageName: String? = null,
    @ColumnInfo(name = "tag") val tag: String? = null,
    @ColumnInfo(name = "content") val content: String? = null,
    @ColumnInfo(name = "tag_regex", defaultValue = "0") val tagRegex: Boolean = false,
    @ColumnInfo(name = "content_regex", defaultValue = "0") val contentRegex: Boolean = false,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
)

internal class AllowedLevelsConverter {

    @TypeConverter
    fun toAllowedLevels(data: String): List<LogLevel> = when (data.isEmpty()) {
        true -> emptyList()

        // getOrNull instead of []: a level ordinal written by another build (or a future
        // enum) used to throw ArrayIndexOutOfBoundsException, which killed the whole filter
        // flow and with it the log list
        else -> data.split(",").mapNotNull { value ->
            value.toIntOrNull()?.let { ordinal -> LogLevel.entries.getOrNull(ordinal) }
        }
    }

    @TypeConverter
    fun fromAllowedLevels(allowedLevels: List<LogLevel>): String = allowedLevels.joinToString(",") {
        it.ordinal.toString()
    }
}
