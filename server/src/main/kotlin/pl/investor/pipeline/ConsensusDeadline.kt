package pl.investor.pipeline

import pl.investor.support.WARSAW
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

private val SESSION_OPEN: LocalTime = LocalTime.of(9, 0)
private val SESSION_CLOSE: LocalTime = LocalTime.of(17, 5)
private val NOTE_PATIENCE: Duration = Duration.ofHours(2)
private val BEFORE_OPEN: Duration = Duration.ofMinutes(15)
private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

fun consensusDeadline(publishedAt: Instant): Instant? {
    val local = publishedAt.atZone(WARSAW)
    if (local.isDuringSession()) return null
    return minOf(publishedAt.plus(NOTE_PATIENCE), local.nextSessionOpen().minus(BEFORE_OPEN)).takeIf { it > publishedAt }
}

private fun LocalDate.isWeekday(): Boolean = dayOfWeek !in WEEKEND

private fun ZonedDateTime.isDuringSession(): Boolean =
    toLocalDate().isWeekday() && toLocalTime() >= SESSION_OPEN && toLocalTime() < SESSION_CLOSE

private fun ZonedDateTime.nextSessionOpen(): Instant {
    val today = toLocalDate()
    val day = if (today.isWeekday() && toLocalTime() < SESSION_OPEN) today else generateSequence(today.plusDays(1)) { it.plusDays(1) }.first { it.isWeekday() }
    return day.atTime(SESSION_OPEN).atZone(WARSAW).toInstant()
}
