package pl.investor.source.quotes

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pl.investor.history.DailyQuote
import pl.investor.history.QuoteRepository
import pl.investor.report.Company
import pl.investor.report.Market
import pl.investor.source.avanza.AvanzaClient
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class SwedishQuotesTest {

    private val avanza = mockk<AvanzaClient>()
    private val yahoo = mockk<YahooChartClient>()
    private val repository = mockk<QuoteRepository>(relaxed = true)
    private val company = Company(1, "Mips AB", "SE0009216278", Market.SE, "MIPS", null, false, avanzaId = "740416")
    private val today = LocalDate.parse("2026-11-02")

    private fun quotes(at: String) = AvanzaQuotes(avanza, yahoo, repository, Clock.fixed(Instant.parse(at), ZoneOffset.UTC), mockk(relaxed = true))

    private fun bar(instrument: String, date: LocalDate) = DailyQuote(instrument, date, BigDecimal.TEN, 100, BigDecimal(1000))

    @Test
    fun `first need loads about two and a half years of the stock`() {
        every { repository.latestDate("SE0009216278") } returns null
        every { avanza.dailyQuotes("740416", "SE0009216278", any(), any()) } returns listOf(bar("SE0009216278", today.minusDays(3)))

        quotes("2026-11-02T18:00:00Z").updateCompany(company)

        verify { avanza.dailyQuotes("740416", "SE0009216278", today.minusDays(915), today) }
        verify(exactly = 1) { repository.save(any()) }
    }

    @Test
    fun `later updates ask only for missing days and skip the unfinished session`() {
        every { repository.latestDate("OMXSPI") } returns today.minusDays(4)
        every { yahoo.daily("^OMXSPI", "OMXSPI", any(), any()) } returns listOf(bar("OMXSPI", today.minusDays(3)), bar("OMXSPI", today))

        quotes("2026-11-02T14:00:00Z").updateIndex(Market.SE)

        verify { yahoo.daily("^OMXSPI", "OMXSPI", today.minusDays(3), today.minusDays(1)) }
        verify(exactly = 1) { repository.save(match { it.date == today.minusDays(3) }) }
        verify(exactly = 0) { repository.save(match { it.date == today }) }
    }

    @Test
    fun `no call is made when only the unfinished session or a weekend is missing`() {
        every { repository.latestDate("OMXSPI") } returns today.minusDays(1)
        quotes("2026-11-02T15:00:00Z").updateIndex(Market.SE)

        every { repository.latestDate("OMXSPI") } returns LocalDate.parse("2026-10-30")
        quotes("2026-11-01T12:00:00Z").updateIndex(Market.SE)

        verify(exactly = 0) { yahoo.daily(any(), any(), any(), any()) }
    }

    @Test
    fun `after the Stockholm close today's session is fetched`() {
        every { repository.latestDate("OMXSPI") } returns today.minusDays(1)
        every { yahoo.daily("^OMXSPI", "OMXSPI", today, today) } returns listOf(bar("OMXSPI", today))

        quotes("2026-11-02T16:40:00Z").updateIndex(Market.SE)

        verify(exactly = 1) { repository.save(match { it.date == today }) }
    }

    @Test
    fun `company without Avanza identity is skipped`() {
        quotes("2026-11-02T18:00:00Z").updateCompany(company.copy(avanzaId = null))

        verify(exactly = 0) { avanza.dailyQuotes(any(), any(), any(), any()) }
    }
}
