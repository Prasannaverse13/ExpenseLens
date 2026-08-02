package com.expenselens.data.db

import androidx.room.TypeConverter
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Room type converters for java.time values. Annotate the @Database
 * declaration with @TypeConverters(Converters::class) to enable.
 */
class Converters {

    @TypeConverter
    fun fromLocalDate(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun toLocalDate(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    @TypeConverter
    fun fromLocalDateTime(value: LocalDateTime?): String? = value?.toString()

    @TypeConverter
    fun toLocalDateTime(value: String?): LocalDateTime? =
        value?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
}
