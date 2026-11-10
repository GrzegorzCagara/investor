package pl.investor.scoring

import pl.investor.support.AMSTERDAM
import pl.investor.support.BERLIN
import pl.investor.support.HELSINKI
import pl.investor.support.WARSAW
import pl.investor.support.STOCKHOLM
import pl.investor.report.Market
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

object ReferenceSession {

    private val POLISH_SESSION_CLOSED = LocalTime.of(17, 5)
    private val SWEDISH_SESSION_CLOSED = LocalTime.of(17, 35)
    private val GERMAN_SESSION_CLOSED = LocalTime.of(17, 35)
    private val DUTCH_SESSION_CLOSED = LocalTime.of(17, 40)
    private val FINNISH_SESSION_CLOSED = LocalTime.of(18, 35)

    fun isClosed(session: LocalDate, at: Instant, market: Market): Boolean =
        market.closedAt(session) < at

    fun lastClosedSessionBefore(publishedAt: Instant, sessions: List<LocalDate>, market: Market): LocalDate =
        sessions.filter { isClosed(it, publishedAt, market) }.max()

    private fun Market.closedAt(session: LocalDate): Instant = when (this) {
        Market.SE -> session.atTime(SWEDISH_SESSION_CLOSED).atZone(STOCKHOLM).toInstant()
        Market.DE -> session.atTime(GERMAN_SESSION_CLOSED).atZone(BERLIN).toInstant()
        Market.NL -> session.atTime(DUTCH_SESSION_CLOSED).atZone(AMSTERDAM).toInstant()
        Market.FI -> session.atTime(FINNISH_SESSION_CLOSED).atZone(HELSINKI).toInstant()
        Market.GPW, Market.NC, Market.US -> session.atTime(POLISH_SESSION_CLOSED).atZone(WARSAW).toInstant()
    }
}
