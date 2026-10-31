package pl.investor.source.consensus

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.investor.monitoring.SourceHealthTracker
import pl.investor.report.Company
import pl.investor.report.Market
import pl.investor.report.ReportKind
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import pl.investor.source.biznesradar.BiznesRadarClient
import pl.investor.source.content.BankierReportLocator
import pl.investor.source.gpw.ListingEntry
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsensusClientTest {

    private val server = MockWebServer()
    private val requestedPaths = mutableListOf<String>()
    private val biznesRadar = mockk<BiznesRadarClient>()
    private val tracker = mockk<SourceHealthTracker>(relaxed = true)
    private var now = Instant.parse("2026-08-06T05:15:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
    private var newsList: String? = null
    private var notePage: String? = null
    private lateinit var locator: BankierReportLocator
    private lateinit var client: ConsensusClient

    private val notePath = "/wiadomosc/Wyniki-Asbisu-w-II-kwartale-2026-roku-vs-konsensus-PAP-tabela-9178558.html"
    private val asbis = Company(1, "ASBISC ENTERPRISES PLC", "CY1000031710", Market.GPW, null, null, true)
    private val unknown = Company(2, "NIEZNANA SPOLKA SA", "PL0000000000", Market.GPW, null, null, false)
    private val reportPublished = Instant.parse("2026-08-05T22:20:21Z")
    private val window = NoteWindow(reportPublished, reportPublished.plus(Duration.ofHours(36)))
    private val asbisH1 = ListingEntry(
        market = Market.GPW,
        companyName = "ASBISC ENTERPRISES PLC",
        isin = "CY1000031710",
        kind = ReportKind.HALF_YEARLY,
        number = "1/2026",
        sourceRef = "1",
        title = null,
        publishedAt = reportPublished,
        detailUrl = "https://www.gpw.pl/komunikat?geru_id=1",
    )

    @BeforeEach
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                requestedPaths += path
                return when (path) {
                    "/gielda/notowania/akcje/ASBIS/komunikaty" -> page(fixture("bankier/asbis-komunikaty.html"))
                    "/gielda/notowania/akcje/ASBIS/wiadomosci" -> page(newsList ?: fixture("bankier/asbis-wiadomosci.html"))
                    notePath -> page(notePage ?: fixture("bankier/asbis-konsensus-q2-2026.html"))
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        every { biznesRadar.mediumNameFor(any()) } returns "ASBIS"
        val baseUrl = server.url("/").toString().trimEnd('/')
        val http = PoliteHttpClient(Clock.systemUTC(), "me@example.com", tracker)
        locator = BankierReportLocator(http, biznesRadar, baseUrl)
        client = ConsensusClient(http, locator, biznesRadar, ConsensusNoteParser(), clock, baseUrl)
    }

    @AfterEach
    fun stop() = server.close()

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/$path")) { "missing fixture $path" }.readText()

    private fun page(body: String): MockResponse =
        MockResponse.Builder().body(body).addHeader("Content-Type", "text/html; charset=utf-8").build()

    private fun find(company: Company, vararg windows: NoteWindow) = client.find(company, windows.toList())

    @Test
    fun `ASBIS half-year report published after midnight finds the morning consensus note`() {
        val note = assertNotNull(find(asbis, window)[window])

        assertEquals(server.url(notePath).toString(), note.url)
        assertEquals(Instant.parse("2026-08-06T04:46:00Z"), note.publishedAt)
        assertEquals(ConsensusRow("Przychody", BigDecimal("1696.7"), BigDecimal("1686.3"), 0.6), note.rows.first())
        assertEquals(4, note.rows.size)
    }

    @Test
    fun `window ending before the note finds nothing`() {
        val early = window.copy(until = Instant.parse("2026-08-06T04:45:00Z"))

        assertNull(find(asbis, early)[early])
    }

    @Test
    fun `note published before the report is ignored`() {
        val later = NoteWindow(Instant.parse("2026-08-06T05:00:00Z"), Instant.parse("2026-08-07T17:00:00Z"))

        assertNull(find(asbis, later)[later])
    }

    @Test
    fun `report published within the note's listed minute but after it is ignored`() {
        val sameMinute = NoteWindow(Instant.parse("2026-08-06T04:46:30Z"), Instant.parse("2026-08-07T16:46:30Z"))

        assertNull(find(asbis, sameMinute)[sameMinute])
        assertTrue(notePath in requestedPaths)
    }

    @Test
    fun `news list is fetched once for several windows of the same company`() {
        val second = NoteWindow(reportPublished.plusSeconds(60), reportPublished.plus(Duration.ofHours(30)))

        val notes = find(asbis, window, second)

        assertNotNull(notes[window])
        assertNotNull(notes[second])
        assertEquals(1, requestedPaths.count { it == "/gielda/notowania/akcje/ASBIS/wiadomosci" })
        assertEquals(1, requestedPaths.count { it == notePath })
    }

    @Test
    fun `consensus fetches are recorded as BANKIER_NEWS`() {
        find(asbis, window)

        verify { tracker.recordSuccess(Source.BANKIER_NEWS, any()) }
        verify(exactly = 0) { tracker.recordSuccess(Source.BANKIER, any()) }
    }

    @Test
    fun `link with an unparseable list date is still fetched`() {
        newsList = """<a class="m-listing-article-list__anchor" href="$notePath"><div class="m-listing-article-list__date-time">wczoraj</div><div class="m-listing-article-list__title">Wyniki Asbisu vs. konsensus PAP (tabela)</div></a>"""

        assertNotNull(find(asbis, window)[window])
    }

    @Test
    fun `note without rows is ignored`() {
        notePage = """<script>{"datePublished": "2026-08-06T06:46:00+0200"}</script><p>Brak tabeli</p>"""

        assertNull(find(asbis, window)[window])
    }

    @Test
    fun `note that cannot be parsed is skipped without aborting the lookup`() {
        notePage = "<p>Brak daty</p>"

        assertEquals(emptyMap(), find(asbis, window))
    }

    @Test
    fun `removed note page is skipped and the other note is still found`() {
        val removed = "/wiadomosc/Wyniki-Asbisu-vs-konsensus-PAP-tabela-1.html"
        newsList = listOf(removed, notePath).joinToString("") { path ->
            """<a class="m-listing-article-list__anchor" href="$path"><div class="m-listing-article-list__date-time">wczoraj</div><div class="m-listing-article-list__title">Wyniki Asbisu vs. konsensus PAP (tabela)</div></a>"""
        }

        assertNotNull(find(asbis, window)[window])
        assertTrue(removed in requestedPaths)
    }

    @Test
    fun `slug confirmed by the report locator is used without BiznesRadar`() {
        every { biznesRadar.mediumNameFor(any()) } returns null
        assertNotNull(locator.find(asbisH1))
        requestedPaths.clear()

        assertNotNull(find(asbis, window)[window])

        assertEquals(listOf("/gielda/notowania/akcje/ASBIS/wiadomosci", notePath), requestedPaths)
        verify(exactly = 1) { biznesRadar.mediumNameFor(any()) }
    }

    @Test
    fun `slug is resolved only from the BiznesRadar medium name verified by komunikaty and then cached`() {
        find(asbis, window)
        assertEquals("/gielda/notowania/akcje/ASBIS/komunikaty", requestedPaths.first())
        requestedPaths.clear()

        find(asbis, window)

        assertEquals(listOf("/gielda/notowania/akcje/ASBIS/wiadomosci", notePath), requestedPaths)
        verify(exactly = 1) { biznesRadar.mediumNameFor(any()) }
    }

    @Test
    fun `no heuristic slug guesses are made without a medium name`() {
        every { biznesRadar.mediumNameFor(any()) } returns null

        assertEquals(emptyMap(), find(asbis, window))
        assertEquals(emptyList<String>(), requestedPaths)
    }

    @Test
    fun `medium name without announcements is not used`() {
        every { biznesRadar.mediumNameFor(any()) } returns "NIEZNANA"

        assertEquals(emptyMap(), find(unknown, window))
        assertEquals(listOf("/gielda/notowania/akcje/NIEZNANA/komunikaty"), requestedPaths)
    }

    @Test
    fun `unresolvable company is looked up at most once a day`() {
        every { biznesRadar.mediumNameFor(any()) } returns null

        find(unknown, window)
        now = Instant.parse("2026-08-06T21:00:00Z")
        find(unknown, window)
        verify(exactly = 1) { biznesRadar.mediumNameFor(any()) }

        now = Instant.parse("2026-08-06T22:30:00Z")
        find(unknown, window)
        verify(exactly = 2) { biznesRadar.mediumNameFor(any()) }
    }

    @Test
    fun `consensus lookups leave the report locator cache untouched`() {
        every { biznesRadar.mediumNameFor(any()) } returnsMany listOf("ASBIS", null)
        find(asbis, window)
        requestedPaths.clear()

        assertNotNull(locator.find(asbisH1))

        assertEquals(listOf("/gielda/notowania/akcje/ASBISC/komunikaty", "/gielda/notowania/akcje/ASBIS/komunikaty"), requestedPaths)
    }

    @Test
    fun `US company has no note`() {
        assertEquals(emptyMap(), find(asbis.copy(market = Market.US), window))
        assertEquals(emptyList<String>(), requestedPaths)
    }
}
