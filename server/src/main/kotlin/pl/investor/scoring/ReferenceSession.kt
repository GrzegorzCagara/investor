package pl.investor.scoring

import pl.investor.support.WARSAW
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

object ReferenceSession {

    private val sessionEnd = LocalTime.of(17, 5)

    fun isClosed(session: LocalDate, at: Instant): Boolean =
        session.atTime(sessionEnd).atZone(WARSAW).toInstant() < at

    fun lastClosedSessionBefore(publishedAt: Instant, sessions: List<LocalDate>): LocalDate =
        sessions.filter { isClosed(it, publishedAt) }.max()
}
