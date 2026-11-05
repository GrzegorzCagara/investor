package pl.investor.pipeline

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.replay.MutableClock
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.source.Source
import pl.investor.source.SourceUnavailableException
import pl.investor.source.avanza.AvanzaIdentity
import pl.investor.source.biznesradar.BiznesRadarClient
import pl.investor.source.biznesradar.SymbolNotFoundException
import pl.investor.source.xtb.XtbAvailability
import pl.investor.source.xtb.XtbClient
import pl.investor.source.xtb.XtbCountry
import pl.investor.source.xtb.XtbStock
import pl.investor.support.PostgresTestBase
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XtbFilterTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository

    private val clock = MutableClock(Instant.parse("2026-11-04T07:00:00Z"))
    private val xtb = mockk<XtbClient>()
    private val biznesRadar = mockk<BiznesRadarClient>()
    private val avanzaRefresher = mockk<AvanzaCompanyRefresher>()
    private val refresher by lazy { CompanyDataRefresher(biznesRadar, companies, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), clock, avanzaRefresher) }

    private fun filter(markets: String = "DE") = XtbFilter(markets, XtbAvailability(xtb, biznesRadar, companies, clock), refresher, companies)

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE company RESTART IDENTITY CASCADE").update()
        every { xtb.stocks(XtbCountry.PL) } returns listOf(XtbStock("11B", "11 bit studios SA"))
        every { xtb.stocks(XtbCountry.SE) } returns listOf(XtbStock("VOLVB", "Volvo AB"))
        every { xtb.stocks(XtbCountry.DE) } returns listOf(XtbStock("MZX", "Masterflex SE"), XtbStock("TIMA", "ZEAL Network SE"))
        every { xtb.stocks(XtbCountry.NL) } returns listOf(XtbStock("ASML", "ASML Holding NV"))
        every { biznesRadar.tickersBySymbol() } returns mapOf("11-BIT-STUDIOS" to "11B", "ALFA" to "ALF")
        every { biznesRadar.mediumNameFor(any()) } returns null
    }

    private fun german(name: String, isin: String, ticker: String? = null): Company {
        val company = companies.save(Company(null, name, isin, Market.DE, null, null, false))
        val id = checkNotNull(company.id)
        if (ticker != null) companies.updateAvanzaIdentity(id, AvanzaIdentity("1", isin, ticker, null, name, "EUR"))
        val stored = companies.findById(id)
        every { avanzaRefresher.identification(match { it.id == id }) } returns
            if (ticker != null) AvanzaIdentification.Identified(stored) else AvanzaIdentification.NotListed
        return stored
    }

    private fun dutch(name: String, isin: String, euronextTicker: String?, avanzaTicker: String? = null, identification: AvanzaIdentification? = null): Company {
        val company = companies.save(Company(null, name, isin, Market.NL, null, null, false, exchangeTicker = euronextTicker))
        val id = checkNotNull(company.id)
        if (avanzaTicker != null) companies.updateAvanzaIdentity(id, AvanzaIdentity("2", isin, avanzaTicker, null, name, "EUR"))
        val stored = companies.findById(id)
        every { avanzaRefresher.identification(match { it.id == id }) } returns
            (identification ?: if (avanzaTicker != null) AvanzaIdentification.Identified(stored) else AvanzaIdentification.NotListed)
        return stored
    }

    @Test
    fun `Dutch company is matched on XTB by Avanza ticker, Euronext ticker or name`() {
        every { xtb.stocks(XtbCountry.NL) } returns listOf(XtbStock("ASML", "ASML Holding NV"), XtbStock("ENVI", "Envipco Holding NV"), XtbStock("LIGHT", "Signify NV"))
        val asml = dutch("ASML HOLDING N.V.", "NL0010273215", euronextTicker = null, avanzaTicker = "ASML")
        val envipco = dutch("ENVIPCO HOLDING N.V.", "NL0015000GX8", euronextTicker = "ENVI")
        val signify = dutch("SIGNIFY NV", "NL0011821392", euronextTicker = null)
        val bam = dutch("Koninklijke BAM Groep N.V.", "NL0000337319", euronextTicker = "BAMNB", avanzaTicker = "BAMNB")

        assertFalse(filter("NL").withholdsFromAi(asml))
        assertFalse(filter("NL").withholdsFromAi(envipco))
        assertFalse(filter("NL").withholdsFromAi(signify))
        assertTrue(filter("NL").withholdsFromAi(bam))
        assertTrue(companies.findById(checkNotNull(envipco.id)).availableOnXtb)
    }

    @Test
    fun `Dutch company with Avanza unavailable is retried unless the Euronext ticker matches`() {
        val asml = dutch("ASML HOLDING", "NL0010273215", euronextTicker = "ASML", identification = AvanzaIdentification.Unavailable)
        val bam = dutch("Koninklijke BAM Groep N.V.", "NL0000337319", euronextTicker = "BAMNB", identification = AvanzaIdentification.Unavailable)

        assertFalse(filter("NL").withholdsFromAi(asml))
        assertThrows<SourceUnavailableException> { filter("NL").withholdsFromAi(bam) }
    }

    @Test
    fun `German company on XTB by its Avanza ticker goes to AI and gets the badge at once`() {
        val masterflex = german("Masterflex Aktiengesellschaft", "DE0005492938", ticker = "MZX")

        assertFalse(filter().withholdsFromAi(masterflex))
        assertTrue(companies.findById(checkNotNull(masterflex.id)).availableOnXtb)
    }

    @Test
    fun `German company not on XTB is withheld from AI`() {
        val basler = german("Basler AG", "DE0005102008", ticker = "BSL")

        assertTrue(filter().withholdsFromAi(basler))
        assertFalse(companies.findById(checkNotNull(basler.id)).availableOnXtb)
    }

    @Test
    fun `company without an Avanza share is matched by name only`() {
        val zeal = german("ZEAL Network SE", "DE000ZEAL241")
        val umweltbank = german("Umweltbank AG", "DE0005570808")

        assertFalse(filter().withholdsFromAi(zeal))
        assertTrue(filter().withholdsFromAi(umweltbank))
    }

    @Test
    fun `transient Avanza failure without a name match is retried instead of skipped`() {
        val basler = german("Basler AG", "DE0005102008")
        every { avanzaRefresher.identification(match { it.id == basler.id }) } returns AvanzaIdentification.Unavailable

        assertThrows<SourceUnavailableException> { filter().withholdsFromAi(basler) }
    }

    @Test
    fun `XTB offer that cannot be downloaded and was never cached is retried`() {
        val masterflex = german("Masterflex SE", "DE0005492938", ticker = "MZX")
        every { xtb.stocks(XtbCountry.DE) } throws SourceUnavailableException(Source.XTB, RuntimeException("down"))

        assertThrows<SourceUnavailableException> { filter().withholdsFromAi(masterflex) }
    }

    @Test
    fun `cached offer is used when a later download fails`() {
        val masterflex = german("Masterflex SE", "DE0005492938", ticker = "MZX")
        val filter = filter()
        filter.withholdsFromAi(masterflex)
        every { xtb.stocks(XtbCountry.DE) } throws SourceUnavailableException(Source.XTB, RuntimeException("down"))
        clock.set(clock.instant().plusSeconds(24 * 3600))

        assertFalse(filter.withholdsFromAi(masterflex))
    }

    @Test
    fun `markets outside the config are never checked and Polish companies use the BiznesRadar ticker when configured`() {
        val gpw = companies.save(Company(null, "11 BIT STUDIOS SPÓŁKA AKCYJNA", "PLGRMN000010", Market.GPW, null, "11-BIT-STUDIOS", false))
        val alfa = companies.save(Company(null, "ALFA SA", "PLALFA000010", Market.GPW, null, "ALFA", false))
        val basler = german("Basler AG", "DE0005102008", ticker = "BSL")

        assertFalse(filter().withholdsFromAi(gpw))
        assertFalse(filter().withholdsFromAi(alfa))
        verify(exactly = 0) { xtb.stocks(any()) }

        val polishOnly = filter("GPW, NC")
        assertFalse(polishOnly.withholdsFromAi(gpw))
        assertTrue(polishOnly.withholdsFromAi(alfa))
        assertFalse(polishOnly.withholdsFromAi(basler))
        assertTrue(filter("").withholdsFromAi(alfa).not())
    }

    @Test
    fun `Polish company without a BiznesRadar symbol is looked up first and matched by ticker`() {
        val elevenBit = companies.save(Company(null, "11 BIT STUDIOS SPÃ“Å�KA AKCYJNA", "PLGRMN000010", Market.GPW, null, null, false))
        every { biznesRadar.symbolFor(match { it.id == elevenBit.id }) } returns "11-BIT-STUDIOS"

        assertFalse(filter("GPW").withholdsFromAi(elevenBit))
        assertEquals("11-BIT-STUDIOS", companies.findById(checkNotNull(elevenBit.id)).biznesradarSymbol)
    }

    @Test
    fun `BiznesRadar outage retries unless the XTB name already matches, and a missing symbol falls back to the name`() {
        val unknown = companies.save(Company(null, "NIEZNANA SPÃ“Å�KA SA", "PLNZNN000010", Market.NC, null, null, false))
        val elevenBit = companies.save(Company(null, "11 bit studios SA", "PLGRMN000010", Market.GPW, null, null, false))
        every { biznesRadar.symbolFor(any()) } throws SourceUnavailableException(Source.BIZNESRADAR, RuntimeException("down"))

        assertThrows<SourceUnavailableException> { filter("GPW,NC").withholdsFromAi(unknown) }
        assertFalse(filter("GPW,NC").withholdsFromAi(elevenBit))

        every { biznesRadar.symbolFor(any()) } answers { throw SymbolNotFoundException(firstArg()) }
        assertTrue(filter("GPW,NC").withholdsFromAi(unknown))
    }

    @Test
    fun `unavailable BiznesRadar ticker list retries a Polish company that does not match by name`() {
        val alfa = companies.save(Company(null, "ALFA SA", "PLALFA000010", Market.GPW, null, "ALFA", false))
        val elevenBit = companies.save(Company(null, "11 bit studios SA", "PLGRMN000010", Market.GPW, null, "11-BIT-STUDIOS", false))
        every { biznesRadar.tickersBySymbol() } throws SourceUnavailableException(Source.BIZNESRADAR, RuntimeException("down"))

        assertThrows<SourceUnavailableException> { filter("GPW").withholdsFromAi(alfa) }
        assertFalse(filter("GPW").withholdsFromAi(elevenBit))
    }
}
