package pl.investor.source.xtb

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.replay.MutableClock
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.source.Source
import pl.investor.source.SourceUnavailableException
import pl.investor.source.biznesradar.BiznesRadarClient
import pl.investor.support.PostgresTestBase
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XtbAvailabilityJobTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository

    private val clock = MutableClock(Instant.parse("2026-11-04T06:00:00Z"))
    private val xtb = mockk<XtbClient>()
    private val biznesRadar = mockk<BiznesRadarClient>()
    private val job by lazy { XtbAvailabilityJob(XtbAvailability(xtb, biznesRadar, companies, clock)) }

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE company RESTART IDENTITY CASCADE").update()
        every { xtb.stocks(XtbCountry.PL) } returns listOf(
            XtbStock("11B", "11 bit studios SA"),
            XtbStock("ZUK", "Zaklady Urzadzen Kotlowych Staporkow SA"),
        )
        every { xtb.stocks(XtbCountry.SE) } returns listOf(XtbStock("VOLVB", "Volvo AB"))
        every { xtb.stocks(XtbCountry.DE) } returns listOf(XtbStock("MZX", "Masterflex SE"))
        every { xtb.stocks(XtbCountry.NL) } returns listOf(XtbStock("ASML", "ASML Holding NV"))
        every { xtb.stocks(XtbCountry.FI) } returns listOf(XtbStock("NOKIA", "Nokia OYJ", "Nokia"))
        every { biznesRadar.tickersBySymbol() } returns mapOf("11-BIT-STUDIOS" to "11B", "ALFA" to "ALF")
    }

    private fun company(name: String, market: Market, biznesradarSymbol: String? = null, ticker: String? = null): Long =
        checkNotNull(companies.save(Company(null, name, null, market, ticker, biznesradarSymbol, false)).id)

    private fun available(id: Long) = companies.findById(id).availableOnXtb

    @Test
    fun `companies offered by XTB are flagged by ticker or by name`() {
        val elevenBit = company("11 BIT STUDIOS SPÓŁKA AKCYJNA", Market.GPW, biznesradarSymbol = "11-BIT-STUDIOS")
        val zuk = company("ZAKŁADY URZĄDZEŃ KOTŁOWYCH STĄPORKÓW S.A.", Market.NC)
        val alfa = company("ALFA SA", Market.GPW, biznesradarSymbol = "ALFA")
        val volvo = company("AB Volvo", Market.SE, ticker = "VOLV B")
        val unknownSwede = company("Okänd AB", Market.SE)

        job.refresh()

        assertTrue(available(elevenBit))
        assertTrue(available(zuk))
        assertFalse(available(alfa))
        assertTrue(available(volvo))
        assertFalse(available(unknownSwede))
    }

    @Test
    fun `company dropped by XTB loses the flag`() {
        val alfa = company("ALFA SA", Market.GPW, biznesradarSymbol = "ALFA")
        companies.updateXtbAvailability(listOf(alfa), available = true)

        job.refresh()

        assertFalse(available(alfa))
    }

    @Test
    fun `list is downloaded once a day and new companies are matched against it in between`() {
        job.refresh()
        val elevenBit = company("11 BIT STUDIOS SPÓŁKA AKCYJNA", Market.GPW, biznesradarSymbol = "11-BIT-STUDIOS")
        clock.set(clock.instant().plus(Duration.ofHours(1)))

        job.refresh()

        assertTrue(available(elevenBit))
        verify(exactly = 1) { xtb.stocks(XtbCountry.PL) }

        clock.set(clock.instant().plus(Duration.ofHours(23)))
        job.refresh()

        verify(exactly = 2) { xtb.stocks(XtbCountry.PL) }
    }

    @Test
    fun `failed download keeps the previous list and the current flags`() {
        val elevenBit = company("11 BIT STUDIOS SPÓŁKA AKCYJNA", Market.GPW, biznesradarSymbol = "11-BIT-STUDIOS")
        job.refresh()
        val zuk = company("ZAKŁADY URZĄDZEŃ KOTŁOWYCH STĄPORKÓW S.A.", Market.NC)
        every { xtb.stocks(XtbCountry.SE) } throws UnexpectedXtbListException("XTB returned 30 of 106 stocks for SE")
        clock.set(clock.instant().plus(Duration.ofDays(1)))

        job.refresh()

        assertTrue(available(elevenBit))
        assertTrue(available(zuk))
    }

    @Test
    fun `nothing changes without any list or without BiznesRadar tickers`() {
        val elevenBit = company("11 BIT STUDIOS SPÓŁKA AKCYJNA", Market.GPW, biznesradarSymbol = "11-BIT-STUDIOS")
        val alfa = company("ALFA SA", Market.GPW, biznesradarSymbol = "ALFA")
        companies.updateXtbAvailability(listOf(alfa), available = true)
        every { biznesRadar.tickersBySymbol() } throws SourceUnavailableException(Source.BIZNESRADAR, RuntimeException("down"))

        job.refresh()

        assertFalse(available(elevenBit))
        assertTrue(available(alfa))

        every { xtb.stocks(XtbCountry.PL) } throws SourceUnavailableException(Source.XTB, RuntimeException("down"))
        val freshJob = XtbAvailabilityJob(XtbAvailability(xtb, biznesRadar, companies, clock))
        every { biznesRadar.tickersBySymbol() } returns mapOf("11-BIT-STUDIOS" to "11B")

        freshJob.refresh()

        assertFalse(available(elevenBit))
        assertTrue(available(alfa))
    }
}
