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
import pl.investor.extraction.AiUnavailableException
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
    private val extraPages = mutableMapOf<String, String>()
    private val statusCodes = mutableMapOf<String, Int>()
    private val reader = mockk<ConsensusTextReader>()
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
                statusCodes[path]?.let { return MockResponse.Builder().code(it).build() }
                return when (path) {
                    "/gielda/notowania/akcje/ASBIS/komunikaty" -> page(fixture("bankier/asbis-komunikaty.html"))
                    "/gielda/notowania/akcje/ASBIS/wiadomosci" -> page(newsList ?: fixture("bankier/asbis-wiadomosci.html"))
                    notePath -> page(notePage ?: fixture("bankier/asbis-konsensus-q2-2026.html"))
                    else -> extraPages[path]?.let { page(it) } ?: MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        every { biznesRadar.mediumNameFor(any()) } returns "ASBIS"
        val baseUrl = server.url("/").toString().trimEnd('/')
        val http = PoliteHttpClient(Clock.systemUTC(), "me@example.com", tracker)
        locator = BankierReportLocator(http, biznesRadar, baseUrl)
        client = ConsensusClient(http, locator, biznesRadar, ConsensusNoteParser(), reader, clock, baseUrl)
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

        assertEquals(listOf("/gielda/notowania/akcje/ASBIS/wiadomosci"), requestedPaths)
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

    private val unimotNotePath = "/wiadomosc/Skorygowana-EBITDA-Unimotu-w-III-kw-wyniosla-133-mln-zl-wobec-108-8-mln-zl-konsensusu-9224554.html"
    private val orlenNotePath = "/wiadomosc/EBITDA-LIFO-Orlen-w-III-kw-14-78-mld-zl-wynik-pod-wplywem-zmiennosci-cen-surowcow-i-geopolityki-9224444.html"
    private val orlenTaxPath = "/wiadomosc/Orlen-zaplaci-2-4-mld-zl-podatku-Niepewnosc-takze-wokol-rafinerii-na-Litwie-9224527.html"
    private val unimot = Company(3, "UNIMOT SA", "PLUNMOT00013", Market.GPW, null, null, true)
    private val orlen = Company(4, "ORLEN SA", "PLPKN0000018", Market.GPW, null, null, true)
    private val unimotPrelimAt = Instant.parse("2026-11-05T18:43:00Z")
    private val unimotWindow = NoteWindow(unimotPrelimAt, unimotPrelimAt.plus(Duration.ofHours(36)))
    private val unimotRows = listOf(
        ConsensusRow("Przychody", BigDecimal("6000"), BigDecimal("4776"), 25.6),
        ConsensusRow("EBITDA skorygowana", BigDecimal("133"), BigDecimal("108.8"), 22.2),
    )

    private fun givenUnimotNews() {
        now = Instant.parse("2026-11-05T21:30:00Z")
        newsList = fixture("bankier/unimot-wiadomosci.html")
        extraPages[unimotNotePath] = fixture("bankier/unimot-konsensus-prelim-q3-2026.html")
    }

    private fun givenOrlenNews() {
        newsList = fixture("bankier/orlen-wiadomosci.html")
        extraPages[orlenNotePath] = fixture("bankier/orlen-konsensus-q3-2026.html")
        extraPages[orlenTaxPath] = fixture("bankier/orlen-article-podatek-2026-11-05.html")
    }

    @Test
    fun `Unimot prelim note without a consensus title is read from its text`() {
        givenUnimotNews()
        every { reader.read(any(), any()) } returns unimotRows

        val note = assertNotNull(find(unimot, unimotWindow)[unimotWindow])

        assertEquals(server.url(unimotNotePath).toString(), note.url)
        assertEquals(Instant.parse("2026-11-05T18:58:00Z"), note.publishedAt)
        assertEquals(unimotRows, note.rows)
        verify(exactly = 1) {
            reader.read("UNIMOT SA", match { "Analitycy ankietowani przez PAP Biznes spodziewali się 108,8 mln zł skorygowanej EBITDA" in it })
        }
    }

    @Test
    fun `checked article is neither fetched nor read again`() {
        givenUnimotNews()
        every { reader.read(any(), any()) } returns unimotRows

        find(unimot, unimotWindow)
        find(unimot, unimotWindow)

        assertEquals(1, requestedPaths.count { it == unimotNotePath })
        verify(exactly = 1) { reader.read(any(), any()) }
    }

    @Test
    fun `text without consensus rows is remembered as no note`() {
        givenUnimotNews()
        every { reader.read(any(), any()) } returns emptyList()

        assertNull(find(unimot, unimotWindow)[unimotWindow])
        assertNull(find(unimot, unimotWindow)[unimotWindow])

        verify(exactly = 1) { reader.read(any(), any()) }
    }

    @Test
    fun `text is read again later when AI was unavailable`() {
        givenUnimotNews()
        every { reader.read(any(), any()) } throws AiUnavailableException(RuntimeException("overloaded")) andThen unimotRows

        assertNull(find(unimot, unimotWindow)[unimotWindow])
        assertNotNull(find(unimot, unimotWindow)[unimotWindow])

        assertEquals(1, requestedPaths.count { it == unimotNotePath })
        verify(exactly = 2) { reader.read(any(), any()) }
    }

    @Test
    fun `Orlen table note with a rewritten title is found without AI and later articles are not fetched`() {
        givenOrlenNews()
        val published = Instant.parse("2026-11-05T16:02:00Z")
        val window = NoteWindow(published, published.plus(Duration.ofHours(36)))

        val note = assertNotNull(find(orlen, window)[window])

        assertEquals(Instant.parse("2026-11-05T16:16:00Z"), note.publishedAt)
        assertEquals(ConsensusRow("Przychody", BigDecimal("80140"), BigDecimal("85025"), -5.7), note.rows.first())
        assertTrue(note.rows.any { it.label.startsWith("EBITDA LIFO") && it.differencePct == -3.5 })
        assertTrue(orlenTaxPath !in requestedPaths)
        verify(exactly = 0) { reader.read(any(), any()) }
    }

    @Test
    fun `article without a consensus mention is not sent to AI`() {
        givenOrlenNews()
        val published = Instant.parse("2026-11-05T17:30:00Z")
        val window = NoteWindow(published, published.plus(Duration.ofHours(36)))

        assertNull(find(orlen, window)[window])

        assertTrue(orlenTaxPath in requestedPaths)
        assertTrue(orlenNotePath !in requestedPaths)
        verify(exactly = 0) { reader.read(any(), any()) }
    }

    @Test
    fun `article that is gone is not fetched again`() {
        newsList = fixture("bankier/unimot-wiadomosci.html")

        find(unimot, unimotWindow)
        find(unimot, unimotWindow)

        assertEquals(1, requestedPaths.count { it == unimotNotePath })
    }

    @Test
    fun `forbidden article is fetched again later`() {
        newsList = fixture("bankier/unimot-wiadomosci.html")
        statusCodes[unimotNotePath] = 403

        find(unimot, unimotWindow)
        find(unimot, unimotWindow)

        assertEquals(2, requestedPaths.count { it == unimotNotePath })
    }

    @Test
    fun `article that cannot be parsed is fetched again later`() {
        newsList = fixture("bankier/unimot-wiadomosci.html")
        extraPages[unimotNotePath] = "<p>Brak daty</p>"

        find(unimot, unimotWindow)
        find(unimot, unimotWindow)

        assertEquals(2, requestedPaths.count { it == unimotNotePath })
    }

    @Test
    fun `fresh article without rows is fetched and read again because the consensus may be added later`() {
        givenUnimotNews()
        now = Instant.parse("2026-11-05T19:30:00Z")
        every { reader.read(any(), any()) } returns emptyList()

        find(unimot, unimotWindow)
        find(unimot, unimotWindow)

        assertEquals(2, requestedPaths.count { it == unimotNotePath })
        verify(exactly = 2) { reader.read(any(), any()) }
    }

    @Test
    fun `rows are read separately for each company`() {
        givenUnimotNews()
        val otherUnimot = Company(5, "UNIMOT INVESTMENTS SA", "PLUNMOT00099", Market.GPW, null, null, true)
        every { reader.read(any(), any()) } returns unimotRows

        find(unimot, unimotWindow)
        find(otherUnimot, unimotWindow)

        verify(exactly = 1) { reader.read("UNIMOT SA", any()) }
        verify(exactly = 1) { reader.read("UNIMOT INVESTMENTS SA", any()) }
    }

    @Test
    fun `article that does not name the company is not sent to AI`() {
        givenUnimotNews()
        val xtb = Company(6, "XTB SPÓŁKA AKCYJNA", "PLXTRDM00011", Market.GPW, null, null, true)

        assertNull(find(xtb, unimotWindow)[unimotWindow])

        verify(exactly = 0) { reader.read(any(), any()) }
    }

    @Test
    fun `inflected company name in the article is recognised`() {
        givenUnimotNews()
        every { reader.read(any(), any()) } returns unimotRows

        assertNotNull(find(unimot.copy(name = "UNIMOT SPÓŁKA AKCYJNA"), unimotWindow)[unimotWindow])
    }

    @Test
    fun `failed reading is retried but malformed output is remembered`() {
        givenUnimotNews()
        every { reader.read(any(), any()) } throws ConsensusReadingFailedException("API error") andThenThrows
            MalformedConsensusReadingException("bad json", RuntimeException()) andThen unimotRows

        assertNull(find(unimot, unimotWindow)[unimotWindow])
        assertNull(find(unimot, unimotWindow)[unimotWindow])
        assertNull(find(unimot, unimotWindow)[unimotWindow])

        verify(exactly = 2) { reader.read(any(), any()) }
    }

    @Test
    fun `US company has no note`() {
        assertEquals(emptyMap(), find(asbis.copy(market = Market.US), window))
        assertEquals(emptyList<String>(), requestedPaths)
    }

    @Test
    fun `headlines are the listed articles of the range, newest first, up to the limit`() {
        newsList = fixture("bankier/orlen-wiadomosci.html")

        val headlines = client.headlines(asbis, Instant.parse("2026-11-04T00:00:00Z"), Instant.parse("2026-11-05T16:30:00Z"), 3)

        assertEquals(
            listOf(
                "Orlen zwiększył zysk netto o 163 proc. rdr. Konsensus pozostał poza zasięgiem",
                "Litwa chce sięgnąć po zyski Orlenu. Koncern rozważa zamknięcie rafinerii",
                "Orlen rozważa wycofanie się z Możejek. Litewski minister: Nie mamy żadnych informacji",
            ),
            headlines.map { it.title },
        )
        assertEquals(
            listOf("2026-11-05T16:16:00Z", "2026-11-05T13:17:00Z", "2026-11-04T13:20:00Z").map(Instant::parse),
            headlines.map { it.publishedAt },
        )
        assertTrue(headlines.first().url.startsWith(server.url("/wiadomosc/").toString()))
    }

    @Test
    fun `company without a Bankier page has no headlines`() {
        every { biznesRadar.mediumNameFor(any()) } returns null

        assertEquals(emptyList(), client.headlines(unknown, Instant.EPOCH, now, 15))
        assertTrue(requestedPaths.none { it.endsWith("/wiadomosci") })
    }

    @Test
    fun `headlines linking outside Bankier are dropped`() {
        newsList = """<ul><li><a class="m-listing-article-list__anchor" href="https://evil.example/x.html"><div class="m-listing-article-list__date-time">2026-11-05 18:00</div><div class="m-listing-article-list__title">Obca strona</div></a></li>""" +
            """<li><a class="m-listing-article-list__anchor" href="/wiadomosc/a-1.html"><div class="m-listing-article-list__date-time">2026-11-05 17:00</div><div class="m-listing-article-list__title">Własna strona</div></a></li></ul>"""

        val headlines = client.headlines(asbis, Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-06T00:00:00Z"), 15)

        assertEquals(listOf("Własna strona"), headlines.map { it.title })
    }
}
