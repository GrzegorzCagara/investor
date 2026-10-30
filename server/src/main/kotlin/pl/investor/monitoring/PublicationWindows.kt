package pl.investor.monitoring

import pl.investor.report.Market
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

object PublicationWindows {

    private val warsaw: ZoneId = ZoneId.of("Europe/Warsaw")

    private val polishMarketWindows = listOf(
        LocalTime.MIDNIGHT..LocalTime.of(0, 59, 59, 999_999_999),
        LocalTime.of(7, 0)..LocalTime.of(8, 59, 59, 999_999_999),
        LocalTime.of(16, 30)..LocalTime.MAX,
    )

    fun isOpen(at: Instant, market: Market): Boolean = when (market) {
        Market.GPW, Market.NC -> at.atZone(warsaw).toLocalTime().let { time -> polishMarketWindows.any { time in it } }
        Market.US -> false
    }
}
