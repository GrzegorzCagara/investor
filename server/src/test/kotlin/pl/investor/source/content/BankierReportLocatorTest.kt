package pl.investor.source.content

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
import pl.investor.source.gpw.ListingEntry
import java.time.Clock
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BankierReportLocatorTest {

    private val server = MockWebServer()
    private val requestedPaths = mutableListOf<String>()
    private var asbisListFails = false
    private var dupList = ""
    private val biznesRadar = mockk<BiznesRadarClient>()
    private val tracker = mockk<SourceHealthTracker>(relaxed = true)
    private lateinit var locator: BankierReportLocator

    @BeforeEach
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                requestedPaths += path
                return when (path) {
                    "/gielda/notowania/akcje/ASBIS/komunikaty" -> if (asbisListFails) MockResponse.Builder().code(404).build() else list("bankier/asbis-komunikaty.html")
                    "/gielda/notowania/akcje/DUP/komunikaty" -> MockResponse.Builder().body(dupList).addHeader("Content-Type", "text/html; charset=utf-8").build()
                    "/gielda/notowania/akcje/XTB/komunikaty" -> list("bankier/xtb-komunikaty.html")
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        every { biznesRadar.mediumNameFor(any()) } returns null
        locator = BankierReportLocator(
            PoliteHttpClient(Clock.systemUTC(), "me@example.com", tracker),
            biznesRadar,
            server.url("/").toString().trimEnd('/'),
        )
    }

    @AfterEach
    fun stop() = server.close()

    private fun list(path: String): MockResponse =
        MockResponse.Builder()
            .body(checkNotNull(javaClass.getResource("/fixtures/$path")) { "missing fixture $path" }.readText())
            .addHeader("Content-Type", "text/html; charset=utf-8")
            .build()

    private fun entry(company: String, isin: String, publishedAt: String, title: String?, kind: ReportKind) = ListingEntry(
        market = Market.GPW,
        companyName = company,
        isin = isin,
        kind = kind,
        number = "1/2026",
        sourceRef = "1",
        title = title,
        publishedAt = Instant.parse(publishedAt),
        detailUrl = "https://www.gpw.pl/komunikat?geru_id=1",
    )

    private val asbisH1 = entry("ASBISC ENTERPRISES PLC", "CY1000031710", "2026-08-05T22:20:21Z", null, ReportKind.HALF_YEARLY)
    private val xtbPrelim = entry(
        "XTB S.A.", "PLXTRDM00011", "2026-07-29T15:14:57Z",
        "Wstępne wyniki finansowe i operacyjne za I półrocze 2026 roku", ReportKind.CURRENT,
    )

    @Test
    fun `ASBIS H1 periodic report published at 00-20 maps to its article`() {
        assertEquals("https://www.bankier.pl/wiadomosc/ASBISc-Wyniki-finansowe-PS-2026-9178519.html", locator.find(asbisH1))
    }

    @Test
    fun `XTB preliminary results map to their article by minute and title`() {
        assertEquals(
            "https://www.bankier.pl/wiadomosc/XTB-Wstepne-wyniki-finansowe-i-operacyjne-za-I-polrocze-2026-roku-9175226.html",
            locator.find(xtbPrelim),
        )
    }

    @Test
    fun `slug is resolved by trimming the company name and then cached`() {
        locator.find(asbisH1)
        requestedPaths.clear()

        locator.find(asbisH1)

        assertEquals(listOf("/gielda/notowania/akcje/ASBIS/komunikaty"), requestedPaths)
    }

    @Test
    fun `BiznesRadar medium name is the first slug candidate`() {
        every { biznesRadar.mediumNameFor("ASBISC ENTERPRISES PLC") } returns "ASBIS"

        locator.find(asbisH1)

        assertEquals(listOf("/gielda/notowania/akcje/ASBIS/komunikaty"), requestedPaths)
    }

    @Test
    fun `failed slug probes are not recorded as source failures`() {
        locator.find(asbisH1)

        assertEquals(listOf("/gielda/notowania/akcje/ASBISC/komunikaty", "/gielda/notowania/akcje/ASBIS/komunikaty"), requestedPaths)
        verify(exactly = 0) { tracker.recordFailure(any(), any(), any()) }
        verify { tracker.recordSuccess(Source.BANKIER, any()) }
    }

    @Test
    fun `one failure is recorded when no slug candidate responds`() {
        assertNull(locator.find(xtbPrelim.copy(companyName = "NIEZNANA SPOLKA SA", isin = "PL0000000000")))

        verify(exactly = 1) { tracker.recordFailure(Source.BANKIER, any(), any()) }
    }

    @Test
    fun `entry newer than the list returns null`() {
        assertNull(locator.find(xtbPrelim.copy(publishedAt = Instant.parse("2026-10-30T16:00:00Z"))))
    }

    private fun item(date: String, title: String, id: Int) =
        """<li class="m-quotes-announcements-list__item"><div class="m-quotes-announcements-item"><span class="m-quotes-announcements-item__date">$date</span><a class="m-quotes-announcements-item__anchor" href="https://www.bankier.pl/wiadomosc/x-$id.html">$title</a></div></li>"""

    private val dupEntry = xtbPrelim.copy(companyName = "DUP SA", isin = "PLDUP0000001", publishedAt = Instant.parse("2026-09-01T08:00:30Z"))

    @Test
    fun `single unrelated announcement in the exact minute is not attached`() {
        assertNull(locator.find(xtbPrelim.copy(title = "Zawiadomienie o zmianie udziału w głosach")))
    }

    @Test
    fun `single announcement with a reworded but similar title is attached`() {
        assertEquals(
            "https://www.bankier.pl/wiadomosc/XTB-Wstepne-wyniki-finansowe-i-operacyjne-za-I-polrocze-2026-roku-9175226.html",
            locator.find(xtbPrelim.copy(title = "Wstępne wyniki operacyjne i finansowe XTB za I półrocze 2026 roku")),
        )
    }

    @Test
    fun `blank title entry accepts a single Wyniki finansowe announcement of any kind`() {
        dupList = item("01.09.2026 10:00", "Wyniki finansowe PS /2026", 1)

        assertEquals("https://www.bankier.pl/wiadomosc/x-1.html", locator.find(dupEntry.copy(title = null, kind = ReportKind.CURRENT)))
    }

    @Test
    fun `blank title entry does not accept a single unrelated announcement`() {
        dupList = item("01.09.2026 10:00", "Powołanie członka zarządu", 1)

        assertNull(locator.find(dupEntry.copy(title = null, kind = ReportKind.HALF_YEARLY)))
    }

    @Test
    fun `two announcements with the same title in the same minute are ambiguous`() {
        dupList = item("01.09.2026 10:00", "Informacja o transakcji", 1) + item("01.09.2026 10:00", "Informacja o transakcji", 2)

        assertNull(locator.find(dupEntry.copy(title = "Informacja o transakcji")))
    }

    @Test
    fun `title differing only by punctuation and diacritics matches the right announcement`() {
        dupList = item("01.09.2026 10:00", "Zawiadomienie o zmianie udziału w głosach DUP S.A.", 1) + item("01.09.2026 10:00", "Powołanie członka zarządu", 2)

        assertEquals(
            "https://www.bankier.pl/wiadomosc/x-1.html",
            locator.find(dupEntry.copy(title = "Zawiadomienie o zmianie udzialu w glosach DUP SA")),
        )
    }

    @Test
    fun `different title with several announcements in the minute returns null`() {
        dupList = item("01.09.2026 10:00", "Informacja A", 1) + item("01.09.2026 10:00", "Informacja B", 2)

        assertNull(locator.find(dupEntry.copy(title = "Zupełnie inny tytuł")))
    }

    @Test
    fun `neighbouring minute is not accepted`() {
        dupList = item("01.09.2026 10:01", "Informacja A", 1)

        assertNull(locator.find(dupEntry.copy(title = "Informacja A")))
    }

    @Test
    fun `periodic report kind selects the matching Bankier results title`() {
        dupList = item("01.09.2026 10:00", "Wyniki finansowe QS 1/2026", 1) + item("01.09.2026 10:00", "Wyniki finansowe PS /2026", 2)

        assertEquals("https://www.bankier.pl/wiadomosc/x-2.html", locator.find(dupEntry.copy(title = null, kind = ReportKind.HALF_YEARLY)))
    }

    @Test
    fun `cached slug that starts failing is evicted and re-resolved`() {
        locator.find(asbisH1)
        asbisListFails = true
        assertNull(locator.find(asbisH1))
        asbisListFails = false
        requestedPaths.clear()

        assertEquals("https://www.bankier.pl/wiadomosc/ASBISc-Wyniki-finansowe-PS-2026-9178519.html", locator.find(asbisH1))
        assertEquals(listOf("/gielda/notowania/akcje/ASBISC/komunikaty", "/gielda/notowania/akcje/ASBIS/komunikaty"), requestedPaths)
    }

    @Test
    fun `slug is not cached when nothing matched`() {
        locator.find(asbisH1.copy(publishedAt = Instant.parse("2030-01-01T00:00:00Z")))
        requestedPaths.clear()

        locator.find(asbisH1)

        assertEquals("/gielda/notowania/akcje/ASBISC/komunikaty", requestedPaths.first())
    }

    @Test
    fun `unknown company returns null`() {
        assertNull(locator.find(xtbPrelim.copy(companyName = "NIEZNANA SPOLKA SA", isin = "PL0000000000")))
    }

    private val asbisCompany = Company(1, "ASBISC ENTERPRISES PLC", "CY1000031710", Market.GPW, null, null, true)

    @Test
    fun `confirmed slug is known only after a matched report and needs no request`() {
        assertNull(locator.confirmedSlug(asbisCompany))
        locator.find(asbisH1)
        requestedPaths.clear()

        assertEquals("ASBIS", locator.confirmedSlug(asbisCompany))
        assertEquals(emptyList<String>(), requestedPaths)
    }

    @Test
    fun `US company has no confirmed slug`() {
        locator.find(asbisH1)

        assertNull(locator.confirmedSlug(asbisCompany.copy(market = Market.US)))
    }

    @Test
    fun `announcements check probes komunikaty under the given source`() {
        assertTrue(locator.hasAnnouncements("ASBIS", Source.BANKIER_NEWS))
        assertFalse(locator.hasAnnouncements("NIEZNANA", Source.BANKIER_NEWS))

        verify { tracker.recordSuccess(Source.BANKIER_NEWS, any()) }
        verify(exactly = 0) { tracker.recordSuccess(Source.BANKIER, any()) }
        verify(exactly = 0) { tracker.recordFailure(any(), any(), any()) }
    }

    @Test
    fun `consensus lookups do not change how reports are located`() {
        locator.confirmedSlug(asbisCompany)
        locator.hasAnnouncements("ASBIS", Source.BANKIER_NEWS)
        requestedPaths.clear()

        assertEquals("https://www.bankier.pl/wiadomosc/ASBISc-Wyniki-finansowe-PS-2026-9178519.html", locator.find(asbisH1))
        assertEquals(listOf("/gielda/notowania/akcje/ASBISC/komunikaty", "/gielda/notowania/akcje/ASBIS/komunikaty"), requestedPaths)
    }

    @Test
    fun `US entries are not located`() {
        assertNull(locator.find(xtbPrelim.copy(market = Market.US)))
    }
}
