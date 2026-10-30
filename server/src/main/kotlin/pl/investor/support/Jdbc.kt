package pl.investor.support

import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

fun Instant.toUtc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

fun ResultSet.toInstant(column: String): Instant =
    getObject(column, OffsetDateTime::class.java).toInstant()

fun ResultSet.toLocalDate(column: String): LocalDate? =
    getObject(column, LocalDate::class.java)

fun ResultSet.getLongOrNull(column: String): Long? =
    getLong(column).takeUnless { wasNull() }
