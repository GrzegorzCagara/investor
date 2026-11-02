package pl.investor.source.nasdaq

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Test
import pl.investor.monitoring.SourceHealthTracker
import pl.investor.report.Market
import pl.investor.report.ReportRepository
import pl.investor.source.PoliteHttpClient
import pl.investor.source.gpw.ListingEntry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NasdaqNordicPollerTest {

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/se/$path")) { "missing fixture $path" }.readText()

    private val mainMarket = NasdaqNewsParser.parse(fixture("nasdaq-main-market.json"))
    private val firstNorth = NasdaqNewsParser.parse(fixture("nasdaq-first-north.json"))
    private val client = mockk<NasdaqNordicListingClient>()
    private val reports = mockk<ReportRepository>()
    private val delivered = mutableListOf<ListingEntry>()
    private val clock = Clock.fixed(Instant.parse("2026-11-02T15:45:00Z"), ZoneOffset.UTC)
    private val poller = NasdaqNordicPoller(client, reports, clock) { delivered += it }

    @Test
    fun `first poll reads only today, filters foreign markets and delivers English copies first`() {
        every { reports.existsForMarket(Market.SE) } returns false
        every { reports.existsBySourceRef(Market.SE, any()) } returns false
        every { client.page(NasdaqList.MAIN_MARKET, 0) } returns mainMarket
        every { client.page(NasdaqList.FIRST_NORTH, 0) } returns firstNorth

        poller.poll()

        val today = Instant.parse("2026-11-01T23:00:00Z")
        assertTrue(delivered.isNotEmpty())
        assertTrue(delivered.all { !it.publishedAt.isBefore(today) })
        assertEquals(delivered.size, delivered.map { it.sourceRef }.distinct().size)
        val macGregor = delivered.filter { it.companyName == "MacGregor Group AB" }.map { it.sourceRef }
        assertEquals(listOf("1474109", "1474108"), macGregor)
        val foreign = (mainMarket + firstNorth).filter { it.market !in NasdaqList.entries.map(NasdaqList::marketName) }.map { it.disclosureId }
        assertTrue(delivered.none { it.sourceRef in foreign })
        verify(exactly = 0) { client.page(any(), NasdaqNordicListingClient.PAGE_SIZE) }
    }

    @Test
    fun `later polls stop at the first known release and paginate otherwise`() {
        every { reports.existsForMarket(Market.SE) } returns true
        every { reports.existsBySourceRef(Market.SE, any()) } returns false
        every { reports.existsBySourceRef(Market.SE, "1474245") } returns true
        every { client.page(NasdaqList.MAIN_MARKET, 0) } returns mainMarket
        every { client.page(NasdaqList.FIRST_NORTH, 0) } returns firstNorth
        every { client.page(NasdaqList.FIRST_NORTH, 200) } returns emptyList()
        val sevenDaysAfterOldestItem = Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC)

        NasdaqNordicPoller(client, reports, sevenDaysAfterOldestItem) { delivered += it }.poll()

        verify(exactly = 0) { client.page(NasdaqList.MAIN_MARKET, 200) }
        verify(exactly = 1) { client.page(NasdaqList.FIRST_NORTH, 200) }
    }

    @Test
    fun `pagination stops when a page repeats the previous one and never exceeds the page cap`() {
        val sevenDaysAfterOldestItem = Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC)
        every { reports.existsForMarket(Market.SE) } returns true
        every { reports.existsBySourceRef(Market.SE, any()) } returns false
        every { client.page(NasdaqList.MAIN_MARKET, any()) } returns mainMarket.map { it.copy(publishedAt = Instant.parse("2026-11-01T10:00:00Z")) }
        every { client.page(NasdaqList.FIRST_NORTH, any()) } answers {
            val start = secondArg<Int>()
            firstNorth.map { it.copy(disclosureId = "${it.disclosureId}-$start", publishedAt = Instant.parse("2026-11-01T10:00:00Z")) }
        }

        NasdaqNordicPoller(client, reports, sevenDaysAfterOldestItem) { delivered += it }.poll()

        verify(exactly = 2) { client.page(NasdaqList.MAIN_MARKET, any()) }
        verify(exactly = 10) { client.page(NasdaqList.FIRST_NORTH, any()) }
    }

    @Test
    fun `failing list does not block the other one`() {
        every { reports.existsForMarket(Market.SE) } returns true
        every { reports.existsBySourceRef(Market.SE, any()) } returns true
        every { client.page(NasdaqList.MAIN_MARKET, 0) } throws IllegalStateException("down")
        every { client.page(NasdaqList.FIRST_NORTH, 0) } returns firstNorth

        poller.poll()

        verify(exactly = 1) { client.page(NasdaqList.FIRST_NORTH, 0) }
    }

    @Test
    fun `client asks for one market with an encoded filter`() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse.Builder().body(fixture("nasdaq-first-north.json")).build())
        val http = PoliteHttpClient(Clock.systemUTC(), "me@example.com", mockk<SourceHealthTracker>(relaxed = true))

        val items = NasdaqNordicListingClient(http, server.url("/news/query.action").toString()).page(NasdaqList.MAIN_MARKET, 400)

        val request = server.takeRequest().url
        assertEquals("Main Market, Stockholm", request.queryParameter("market"))
        assertEquals("400", request.queryParameter("start"))
        assertEquals("200", request.queryParameter("limit"))
        assertEquals("yyyy-MM-dd HH:mm:ss", request.queryParameter("dateMask"))
        assertEquals(200, items.size)
        server.close()
    }
}
