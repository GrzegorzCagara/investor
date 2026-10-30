package pl.investor.source.quotes

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pl.investor.history.DailyQuote
import pl.investor.history.QuoteRepository
import pl.investor.source.Source
import pl.investor.source.SourceUnavailableException
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

class DailyQuotesJobTest {

    private val gpw = mockk<GpwArchiveClient>()
    private val nc = mockk<NcDailyClient>()
    private val indices = mockk<IndexDailyClient>()
    private val repository = mockk<QuoteRepository>(relaxed = true)
    private val job = DailyQuotesJob(gpw, nc, indices, repository, Clock.systemUTC())

    private fun quote(instrument: String, date: LocalDate) = DailyQuote(instrument, date, BigDecimal.TEN, 1, null)

    private fun session(date: LocalDate) {
        every { gpw.day(date) } returns listOf(quote("PL1", date))
        every { nc.day(date) } returns listOf(quote("PL2", date))
        every { indices.day("WIG", date) } returns quote("WIG", date)
        every { indices.day("NCINDEX", date) } returns quote("NCINDEX", date)
    }

    @Test
    fun `run saves main market, newconnect and both indices`() {
        val date = LocalDate.of(2026, 9, 21)
        session(date)

        job.run(date)

        listOf("PL1", "PL2", "WIG", "NCINDEX").forEach { verify(exactly = 1) { repository.save(quote(it, date)) } }
    }

    @Test
    fun `failing newconnect source does not lose main market quotes or indices`() {
        val date = LocalDate.of(2026, 9, 21)
        session(date)
        every { nc.day(date) } throws SourceUnavailableException(Source.NC_DAILY, RuntimeException("down"))

        job.run(date)

        listOf("PL1", "WIG", "NCINDEX").forEach { verify(exactly = 1) { repository.save(quote(it, date)) } }
        verify(exactly = 0) { repository.save(quote("PL2", date)) }
    }

    @Test
    fun `parse failure in the main market source does not lose newconnect quotes`() {
        val date = LocalDate.of(2026, 9, 21)
        session(date)
        every { gpw.day(date) } throws IllegalStateException("Missing column")

        job.run(date)

        verify(exactly = 1) { repository.save(quote("PL2", date)) }
        verify(exactly = 1) { repository.save(quote("WIG", date)) }
    }

    @Test
    fun `backfill continues after a failed day`() {
        val friday = LocalDate.of(2026, 9, 18)
        val monday = LocalDate.of(2026, 9, 21)
        session(monday)
        every { gpw.day(friday) } throws SourceUnavailableException(Source.GPW_ARCHIVE, RuntimeException("down"))
        every { nc.day(friday) } throws SourceUnavailableException(Source.NC_DAILY, RuntimeException("down"))
        every { indices.day(any(), friday) } throws SourceUnavailableException(Source.INDEX_DAILY, RuntimeException("down"))

        job.backfill(friday, monday)

        verify(exactly = 4) { repository.save(any()) }
    }

    @Test
    fun `run on a non session day saves nothing`() {
        val date = LocalDate.of(2026, 5, 1)
        every { gpw.day(date) } returns emptyList()
        every { nc.day(date) } returns emptyList()
        every { indices.day(any(), date) } returns null

        job.run(date)

        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `backfill walks weekdays only`() {
        val friday = LocalDate.of(2026, 9, 18)
        val monday = LocalDate.of(2026, 9, 21)
        session(friday)
        session(monday)

        job.backfill(friday, monday)

        verify(exactly = 8) { repository.save(any()) }
        verify(exactly = 0) { gpw.day(LocalDate.of(2026, 9, 19)) }
        verify(exactly = 0) { gpw.day(LocalDate.of(2026, 9, 20)) }
    }
}
