package pl.investor.source

import io.mockk.every
import io.mockk.mockk
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Test
import pl.investor.extraction.AiPool
import pl.investor.extraction.newsPool
import pl.investor.extraction.resultsPool
import pl.investor.history.indexInstrument
import pl.investor.history.sessionCalendar
import pl.investor.monitoring.PublicationWindows
import pl.investor.monitoring.SourceHealthTracker
import pl.investor.report.CompanyNames
import pl.investor.report.Market
import pl.investor.report.ReportKind
import pl.investor.report.ReportRepository
import pl.investor.scoring.ReferenceSession
import pl.investor.source.avanza.AvanzaClient
import pl.investor.source.avanza.AvanzaParser
import pl.investor.source.avanza.AvanzaSearchHit
import pl.investor.source.gpw.ListingEntry
import pl.investor.source.nasdaq.NasdaqList
import pl.investor.source.nasdaq.NasdaqNewsItem
import pl.investor.source.nasdaq.NasdaqNewsParser
import pl.investor.source.nasdaq.NasdaqNordicListingClient
import pl.investor.source.nasdaq.NasdaqNordicPoller
import pl.investor.source.nasdaq.isIgnoredNasdaqCategory
import pl.investor.source.quotes.YahooChartClient
import pl.investor.source.xtb.XtbClient
import pl.investor.source.xtb.XtbCountry
import pl.investor.source.xtb.XtbOffer
import pl.investor.source.xtb.XtbStock
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinnishSourcesTest {

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/$path")) { "missing fixture $path" }.readText()

    private val helsinki = NasdaqNewsParser.parse(fixture("fi/nasdaq-helsinki.json"))
    private val client = mockk<NasdaqNordicListingClient>()
    private val reports = mockk<ReportRepository>()
    private val delivered = mutableListOf<ListingEntry>()
    private val afterRaisioInterim = Clock.fixed(Instant.parse("2026-11-10T09:00:00Z"), ZoneOffset.UTC)

    private fun pollWith(clock: Clock, stockholm: List<NasdaqNewsItem> = emptyList()) {
        every { client.page(NasdaqList.HELSINKI, any()) } answers { if (secondArg<Int>() == 0) helsinki else emptyList() }
        every { client.page(NasdaqList.MAIN_MARKET, any()) } answers { if (secondArg<Int>() == 0) stockholm else emptyList() }
        every { client.page(NasdaqList.FIRST_NORTH, any()) } returns emptyList()
        every { reports.existsBySourceRef(any(), any()) } returns false
        NasdaqNordicPoller(client, reports, clock) { delivered += it }.poll()
    }

    @Test
    fun `Helsinki main market list maps to FI and the Stockholm lists stay SE`() {
        assertEquals(Market.FI, NasdaqList.HELSINKI.market)
        assertEquals("Main Market, Helsinki", NasdaqList.HELSINKI.marketName)
        assertEquals(listOf(Market.SE, Market.SE), listOf(NasdaqList.MAIN_MARKET.market, NasdaqList.FIRST_NORTH.market))
        assertTrue(helsinki.any { it.market == "Main Market, Stockholm" })
    }

    @Test
    fun `first FI poll reads only today in Helsinki and delivers the English copy of a release before the Finnish one`() {
        every { reports.existsForMarket(any()) } returns false

        pollWith(afterRaisioInterim)

        val startOfHelsinkiDay = Instant.parse("2026-11-09T22:00:00Z")
        assertTrue(delivered.isNotEmpty())
        assertTrue(delivered.all { it.market == Market.FI && !it.publishedAt.isBefore(startOfHelsinkiDay) })
        val raisio = delivered.filter { it.companyName == "Raisio" }.map { it.sourceRef }
        assertEquals(listOf(RAISIO_EN, RAISIO_FI), raisio)
    }

    @Test
    fun `later polls look back a week, drop items labelled with other markets and keep SE items of the Stockholm list as SE`() {
        every { reports.existsForMarket(any()) } returns true
        val leakedStockholm = helsinki.filter { it.market == "Main Market, Stockholm" }

        pollWith(afterRaisioInterim, stockholm = leakedStockholm)

        val finnish = delivered.filter { it.market == Market.FI }
        assertTrue(finnish.none { it.sourceRef in leakedStockholm.map { item -> item.disclosureId } })
        assertTrue(finnish.any { it.sourceRef == KEMPOWER_INTERIM })
        val nordea = delivered.filter { it.companyName == "Nordea Bank Abp" }
        assertTrue(nordea.all { it.market == Market.SE })
        val languages = nordea.map { ref -> leakedStockholm.single { it.disclosureId == ref.sourceRef }.language }
        assertEquals(listOf("en", "sv", "fi"), languages)
    }

    @Test
    fun `FI entries carry the Nasdaq category and other categories are ignored like in Sweden`() {
        val kempower = helsinki.single { it.disclosureId == KEMPOWER_INTERIM }.toListingEntry(Market.FI)
        val managers = helsinki.first { it.category == "Managers' Transactions" }.toListingEntry(Market.FI)

        assertEquals(ReportKind.QUARTERLY, kempower.kind)
        assertEquals("Interim report (Q1 and Q3)", kempower.number)
        assertFalse(kempower.isIgnoredNasdaqCategory())
        assertTrue(managers.isIgnoredNasdaqCategory())
        assertEquals(ReportKind.CURRENT, helsinki.single { it.disclosureId == "1475515" }.toListingEntry(Market.FI).kind)
    }

    @Test
    fun `Avanza name search for FI accepts only Helsinki venues and returns every share class of the issuer`() {
        val raisio = AvanzaClient.matches("Raisio", AvanzaParser.searchHits(fixture("fi/avanza-search-raisio.json")), Market.FI)
        val nokia = AvanzaParser.searchHits(fixture("fi/avanza-search-nokia.json"))
        val sampo = AvanzaParser.searchHits(fixture("fi/avanza-search-sampo.json"))

        assertEquals(listOf("52801", "52802"), raisio.map { it.orderBookId })
        assertEquals(listOf("52784"), AvanzaClient.matches("Nokia", nokia, Market.FI).map { it.orderBookId })
        assertEquals("82987", AvanzaClient.matches("Nokia", nokia, Market.SE).firstOrNull()?.orderBookId)
        assertEquals(listOf("52810"), AvanzaClient.matches("Sampo", sampo, Market.FI).map { it.orderBookId })
        assertEquals("1501882", AvanzaClient.matches("Sampo", sampo, Market.SE).firstOrNull()?.orderBookId)
    }

    @Test
    fun `only hits with the wanted name plus a share class word are share classes of one issuer`() {
        val kone = AvanzaSearchHit("1", "Kone B", "FI", "Helsingforsbörsen")
        val konecranes = AvanzaSearchHit("2", "Konecranes", "FI", "Helsingforsbörsen")
        val foundation = AvanzaSearchHit("3", "Kone Foundation XYZ", "FI", "Helsingforsbörsen")

        assertEquals(listOf("1"), AvanzaClient.matches("KONE Oyj", listOf(konecranes, kone, foundation), Market.FI).map { it.orderBookId })
        assertEquals(listOf("1"), AvanzaClient.matches("KONE Oyj", listOf(kone, konecranes), Market.FI).map { it.orderBookId })
        assertEquals(listOf("2"), AvanzaClient.matches("Konecranes Oyj", listOf(kone, konecranes), Market.FI).map { it.orderBookId })
        assertEquals(
            listOf("Raisio K", "Raisio Vaihto-osake"),
            AvanzaClient.matches("Raisio", AvanzaParser.searchHits(fixture("fi/avanza-search-raisio.json")), Market.FI).map { it.name },
        )
        val yit = AvanzaSearchHit("4", "YIT Corporation", "FI", "Helsingforsbörsen")
        assertEquals(listOf("4"), AvanzaClient.matches("YIT", listOf(yit), Market.FI).map { it.orderBookId })
    }

    @Test
    fun `Avanza finds Kempower in Helsinki with EUR quotes and quarterly history`() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse.Builder().body(fixture("fi/avanza-search-kempower.json")).build())
        val http = PoliteHttpClient(Clock.systemUTC(), "me@example.com", mockk<SourceHealthTracker>(relaxed = true))

        val hits = AvanzaClient(http, server.url("").toString().trimEnd('/')).findShareClasses("Kempower Oyj", Market.FI)
        val stock = AvanzaParser.stock(fixture("fi/avanza-stock-kempower.json"))
        val q3 = AvanzaParser.quarters(fixture("fi/avanza-analysis-kempower.json")).single { it.quarterEnd == LocalDate.parse("2026-09-30") }

        assertEquals(listOf("1319400"), hits.map { it.orderBookId })
        assertEquals("FI4000513593", stock.isin)
        assertEquals("KEMPOWR", stock.ticker)
        assertEquals("EUR", stock.currency)
        assertEquals("Mid Cap Helsinki", stock.listing)
        assertEquals(0, BigDecimal("66200").compareTo(q3.salesInThousands))
        assertEquals(LocalDate.parse("2026-11-04"), q3.reportDate)
        server.close()
    }

    @Test
    fun `Avanza daily bars of Kempower land on the Helsinki trading date and Yahoo OMXH25 has one close per session`() {
        val bars = AvanzaParser.dailyQuotes(fixture("fi/avanza-chart-kempower.json"), "FI4000513593")
        assertEquals(0, BigDecimal("6.45").compareTo(bars.single { it.date == LocalDate.parse("2026-11-04") }.close))
        assertEquals(0, BigDecimal("8.095").compareTo(bars.single { it.date == LocalDate.parse("2026-11-03") }.close))

        val omxh25 = YahooChartClient.parse(fixture("fi/yahoo-omxh25.json"), "OMXH25")
        assertEquals(22, omxh25.size)
        assertEquals(omxh25.size, omxh25.map { it.date }.distinct().size)
        assertTrue(omxh25.all { it.instrument == "OMXH25" })
    }

    @Test
    fun `XTB Finnish cash stocks carry the friendly name and match by ticker, friendly name and name without share class`() {
        val finnish = XtbClient.parse(fixture("xtb/instruments-fi.json"), XtbCountry.FI)
        val swedish = XtbClient.parse(fixture("xtb/instruments-se-2026-11-10.json"), XtbCountry.SE)
        val offer = XtbOffer(mapOf(XtbCountry.FI to finnish, XtbCountry.SE to swedish))

        assertEquals(33, finnish.size)
        assertEquals("Nokian Tyres", finnish.single { it.ticker == "TYRES" }.friendlyName)
        assertTrue(offer.lists(listOf(XtbCountry.FI), listOf("KEMPOWR"), "Kempower Oyj"))
        assertTrue(offer.lists(listOf(XtbCountry.FI), emptyList(), "Nokian Tyres plc"))
        assertTrue(offer.lists(listOf(XtbCountry.FI), emptyList(), "Wärtsilä Oyj Abp"))
        assertTrue(offer.lists(listOf(XtbCountry.FI), emptyList(), "Nordea Bank Abp"))
        assertFalse(offer.lists(listOf(XtbCountry.FI), listOf("RAIVV"), "Raisio"))
        assertFalse(offer.lists(listOf(XtbCountry.SE), listOf("SAMPO SEK"), "Sampo"))
        assertTrue(offer.lists(listOf(XtbCountry.SE, XtbCountry.FI), listOf("SAMPO SEK"), "Sampo"))
        assertTrue(offer.lists(listOf(XtbCountry.SE), listOf("NOKIA SEK"), "Nokia"))
        assertEquals("TYRES", offer.tickerNamed(XtbCountry.FI, "Nokian Tyres plc"))
        assertNull(offer.tickerNamed(XtbCountry.FI, "Kuntarahoitus Oyj"))
    }

    @Test
    fun `share class suffix of the XTB name and a trailing currency of the Avanza ticker are ignored`() {
        val offer = XtbOffer(mapOf(XtbCountry.FI to listOf(XtbStock("KNEBV", "Kone OYJ - class B"), XtbStock("SAMPO", "Sampo Oyj - class A"))))

        assertTrue(offer.lists(listOf(XtbCountry.FI), emptyList(), "KONE Oyj"))
        assertTrue(offer.lists(listOf(XtbCountry.FI), listOf("SAMPO SEK"), "Unrelated name"))
        assertFalse(offer.lists(listOf(XtbCountry.FI), listOf("SAMPOX"), "Unrelated name"))
        assertTrue(offer.listsTicker(XtbCountry.FI, "KNE BV"))
    }

    @Test
    fun `Finnish legal forms Oyj, Oy and Abp are not part of the name key`() {
        assertEquals("wartsila", CompanyNames.keyWithoutLegalForm("Wärtsilä Oyj Abp"))
        assertEquals("nordeabank", CompanyNames.keyWithoutLegalForm("Nordea Bank Abp"))
        assertEquals("fortacogroupholdco", CompanyNames.keyWithoutLegalForm("Fortaco Group Holdco Oy"))
    }

    @Test
    fun `Helsinki session closes at 18 35, FI uses OMXH25 in EUR without a liquidity limit and Helsinki publication windows`() {
        val session = LocalDate.parse("2026-11-05")

        assertFalse(ReferenceSession.isClosed(session, Instant.parse("2026-11-05T16:34:00Z"), Market.FI))
        assertTrue(ReferenceSession.isClosed(session, Instant.parse("2026-11-05T16:36:00Z"), Market.FI))
        assertFalse(ReferenceSession.isClosed(LocalDate.parse("2026-07-15"), Instant.parse("2026-07-15T15:34:00Z"), Market.FI))
        assertEquals("OMXH25", Market.FI.indexInstrument())
        assertEquals("OMXH25", Market.FI.sessionCalendar())
        assertEquals("EUR", Market.FI.currency)
        assertNull(Market.FI.lowLiquidityTurnover)
        assertTrue(Market.FI.isCoveredByAvanza)
        assertTrue(PublicationWindows.isOpen(Instant.parse("2026-11-05T07:30:00Z"), Market.FI))
        assertFalse(PublicationWindows.isOpen(Instant.parse("2026-11-05T12:00:00Z"), Market.FI))
        assertTrue(PublicationWindows.isOpen(Instant.parse("2026-11-05T21:30:00Z"), Market.FI))
    }

    @Test
    fun `FI pools split the unchanged 4,75 USD monthly total`() {
        assertEquals(AiPool.FI, Market.FI.resultsPool())
        assertEquals(AiPool.FI_NEWS, Market.FI.newsPool())
        assertEquals(0, BigDecimal("0.20").compareTo(AiPool.FI.limitUsd))
        assertEquals(0, BigDecimal("0.05").compareTo(AiPool.FI_NEWS.limitUsd))
        assertEquals(0, BigDecimal("4.75").compareTo(AiPool.entries.sumOf { it.limitUsd }))
    }

    private companion object {
        const val RAISIO_EN = "1476137"
        const val RAISIO_FI = "1476133"
        const val KEMPOWER_INTERIM = "1474755"
    }
}
