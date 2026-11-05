package pl.investor.monitoring

import pl.investor.support.AMSTERDAM
import pl.investor.support.BERLIN
import pl.investor.support.WARSAW
import pl.investor.support.STOCKHOLM
import pl.investor.report.Market
import java.time.Instant
import java.time.LocalTime

object PublicationWindows {

    private val polishMarketWindows = listOf(
        LocalTime.MIDNIGHT..LocalTime.of(0, 59, 59, 999_999_999),
        LocalTime.of(7, 0)..LocalTime.of(8, 59, 59, 999_999_999),
        LocalTime.of(16, 30)..LocalTime.MAX,
    )

    private val swedishMarketWindows = listOf(
        LocalTime.MIDNIGHT..LocalTime.of(0, 59, 59, 999_999_999),
        LocalTime.of(7, 0)..LocalTime.of(8, 59, 59, 999_999_999),
        LocalTime.of(17, 30)..LocalTime.MAX,
    )

    private val germanMarketWindows = listOf(
        LocalTime.MIDNIGHT..LocalTime.of(0, 59, 59, 999_999_999),
        LocalTime.of(6, 30)..LocalTime.of(8, 59, 59, 999_999_999),
        LocalTime.of(17, 30)..LocalTime.MAX,
    )

    fun isOpen(at: Instant, market: Market): Boolean = when (market) {
        Market.GPW, Market.NC -> at.atZone(WARSAW).toLocalTime().let { time -> polishMarketWindows.any { time in it } }
        Market.SE -> at.atZone(STOCKHOLM).toLocalTime().let { time -> swedishMarketWindows.any { time in it } }
        Market.DE -> at.atZone(BERLIN).toLocalTime().let { time -> germanMarketWindows.any { time in it } }
        Market.NL -> at.atZone(AMSTERDAM).toLocalTime().let { time -> germanMarketWindows.any { time in it } }
        Market.US -> false
    }
}
