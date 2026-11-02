package pl.investor.source.quotes

import pl.investor.support.STOCKHOLM
import org.springframework.stereotype.Component
import pl.investor.history.DailyQuote
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import tools.jackson.databind.json.JsonMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Component
class YahooChartClient(private val http: PoliteHttpClient, private val baseUrl: String = BASE_URL) {

    fun daily(symbol: String, instrument: String, from: LocalDate, to: LocalDate): List<DailyQuote> {
        val period1 = from.atStartOfDay(STOCKHOLM).toEpochSecond()
        val period2 = to.plusDays(1).atStartOfDay(STOCKHOLM).toEpochSecond()
        return parse(http.get(Source.YAHOO, "$baseUrl/v8/finance/chart/${URLEncoder.encode(symbol, StandardCharsets.UTF_8)}?period1=$period1&period2=$period2&interval=1d"), instrument)
    }

    companion object {
        private const val BASE_URL = "https://query1.finance.yahoo.com"
        private val mapper = JsonMapper.builder().build()

        fun parse(json: String, instrument: String): List<DailyQuote> {
            val result = mapper.readTree(json).path("chart").path("result").path(0)
            val zone = result.path("meta").path("exchangeTimezoneName").asString("").takeIf { it.isNotBlank() }?.let(ZoneId::of) ?: STOCKHOLM
            val quote = result.path("indicators").path("quote").path(0)
            val closes = quote.path("close")
            val volumes = quote.path("volume")
            return result.path("timestamp").iterator().asSequence().toList().mapIndexedNotNull { index, timestamp ->
                val close = closes.path(index).takeIf { it.isNumber }?.decimalValue()?.setScale(4, RoundingMode.HALF_UP) ?: return@mapIndexedNotNull null
                DailyQuote(
                    instrument = instrument,
                    date = Instant.ofEpochSecond(timestamp.asLong()).atZone(zone).toLocalDate(),
                    close = close,
                    volume = volumes.path(index).asLong(0),
                    turnover = null,
                )
            }
        }
    }
}
