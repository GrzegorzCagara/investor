package pl.investor.source.eqs

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

class EqsPollerTest {

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/de/$path")) { "missing fixture $path" }.readText()

    private val page = EqsListingParser.parse(fixture("eqs-listing-en-p1.html"))
    private val client = mockk<EqsListingClient>()
    private val reports = mockk<ReportRepository>()
    private val delivered = mutableListOf<ListingEntry>()
    private val clock = Clock.fixed(Instant.parse("2026-11-04T14:20:00Z"), ZoneOffset.UTC)

    private fun fullPage(pageNo: Int) = (page + page + page + page).take(EqsListingClient.PAGE_SIZE).mapIndexed { index, item ->
        item.copy(newsId = "${item.newsId}-$pageNo-$index", publishedAt = Instant.parse("2026-11-03T10:00:00Z"))
    }

    @Test
    fun `first poll reads only today and delivers covered German releases oldest first`() {
        every { reports.existsForMarket(Market.DE) } returns false
        every { reports.existsBySourceRef(Market.DE, any()) } returns false
        every { client.page(1) } returns page

        EqsPoller(client, reports, clock) { delivered += it }.poll()

        assertEquals(14, delivered.size)
        assertTrue(delivered.all { it.market == Market.DE && it.isin!!.startsWith("DE") && it.number in EqsCategories.COVERED })
        assertEquals(delivered.sortedBy { it.publishedAt }, delivered)
        assertEquals("Fresenius SE & Co. KGaA", delivered.first().companyName)
        verify(exactly = 0) { client.page(2) }
    }

    @Test
    fun `polling stops at the first known release`() {
        every { reports.existsForMarket(Market.DE) } returns true
        every { reports.existsBySourceRef(Market.DE, any()) } returns false
        every { reports.existsBySourceRef(Market.DE, "1869172c-aa15-4cff-9a3e-5c5c433ac07b") } returns true
        every { client.page(1) } returns fullPage(1).take(69) + page

        EqsPoller(client, reports, clock) { delivered += it }.poll()

        assertTrue(delivered.none { it.sourceRef == "1869172c-aa15-4cff-9a3e-5c5c433ac07b" })
        verify(exactly = 0) { client.page(2) }
    }

    @Test
    fun `full pages without a known release paginate up to the page cap`() {
        every { reports.existsForMarket(Market.DE) } returns true
        every { reports.existsBySourceRef(Market.DE, any()) } returns false
        every { client.page(any()) } answers { fullPage(firstArg()) }

        EqsPoller(client, reports, clock) { delivered += it }.poll()

        verify(exactly = 10) { client.page(any()) }
    }

    @Test
    fun `client asks the realtime list for English items with jQuery-style array parameters`() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse.Builder().body(fixture("eqs-listing-en-p1.html")).build())
        val http = PoliteHttpClient(Clock.systemUTC(), "me@example.com", mockk<SourceHealthTracker>(relaxed = true))

        val items = EqsListingClient(http, server.url("/wp/wp-admin/admin-ajax.php").toString()).page(3)

        val url = server.takeRequest().url
        assertEquals("en", url.queryParameter("lang"))
        assertEquals("fetch_realtime_news_data", url.queryParameter("action"))
        assertEquals("news", url.queryParameter("recordsFrom[0][api_type]"))
        assertEquals("", url.queryParameter("recordsFrom[0][category]"))
        assertEquals("100", url.queryParameter("pageLimit"))
        assertEquals("3", url.queryParameter("pageNo"))
        assertEquals(31, items.size)
        server.close()
    }
}
