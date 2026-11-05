package pl.investor.source.euronext

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import pl.investor.report.Market
import pl.investor.report.Report
import pl.investor.report.ReportKind
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import pl.investor.source.Source
import pl.investor.source.SourceUnavailableException
import pl.investor.source.gpw.ListingEntry
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EuronextPollerTest {

    private fun fixture(path: String) = javaClass.getResource("/fixtures/nl/$path")?.readText()

    private fun page(no: Int) = EuronextListingParser.parse(checkNotNull(fixture("euronext-listing-p$no.json")))

    private val parser = EuronextReleaseParser()
    private val client = mockk<EuronextClient>()
    private val reports = mockk<ReportRepository>()
    private val delivered = mutableListOf<ListingEntry>()
    private val stored = mutableSetOf<String>()

    private fun poller(at: String) = EuronextPoller(client, reports, Clock.fixed(Instant.parse(at), ZoneOffset.UTC)) { delivered += it }

    private fun unknownOnly(vararg nids: String) {
        every { reports.existsForMarket(Market.NL) } returns true
        every { reports.existsBySourceRef(Market.NL, any()) } answers { secondArg<String>() !in nids || secondArg<String>() in stored }
    }

    @BeforeEach
    fun setUp() {
        every { client.page(any()) } answers { page(firstArg()) }
        every { client.releaseUrl(any()) } answers { "https://live.euronext.com/en/ajax/node/company-press-release/${firstArg<String>()}" }
        every { client.issuer(any()) } answers {
            val nid = firstArg<String>()
            fixture("node-$nid.html")?.let(parser::issuer) ?: EuronextIssuer("XX000$nid", "T$nid")
        }
        every { reports.titledOfIsinBetween(Market.NL, any(), any(), any()) } returns emptyList()
    }

    private fun storedReport(nid: String, title: String, publishedAt: String) = Report(
        1, 1, Market.NL, ReportKind.CURRENT, "Euronext", nid, title, Instant.parse(publishedAt), Instant.parse(publishedAt),
        "https://live.euronext.com/en/ajax/node/company-press-release/$nid", ReportStatus.SCORED,
    )

    @Test
    fun `first poll reads only today, delivers listed shares oldest first and drops the same release sent twice`() {
        every { reports.existsForMarket(Market.NL) } returns false
        every { reports.existsBySourceRef(Market.NL, any()) } returns false

        assertEquals(8, poller("2026-11-05T14:00:00Z").poll())

        assertEquals(
            setOf("12921454", "12921457", "12921550", "12921552", "12921499", "12921643", "12921756", "12921771"),
            delivered.map { it.sourceRef }.toSet(),
        )
        assertEquals(delivered.sortedBy { it.publishedAt }, delivered)
        assertEquals(1, delivered.count { it.isin == "NL0011832811" })
        val bam = delivered.single { it.sourceRef == "12921499" }
        assertEquals(Market.NL, bam.market)
        assertEquals("Koninklijke BAM Groep N.V.", bam.companyName)
        assertEquals("NL0000337319", bam.isin)
        assertEquals("BAMNB", bam.exchangeTicker)
        assertEquals(ReportKind.CURRENT, bam.kind)
        assertEquals("Euronext", bam.number)
        assertEquals("https://live.euronext.com/en/ajax/node/company-press-release/12921499", bam.detailUrl)
        verify(exactly = 0) { client.page(1) }
    }

    @Test
    fun `English and Dutch versions of one release in the same poll keep the English one`() {
        unknownOnly("12921298", "12921299")

        poller("2026-11-05T14:00:00Z").poll()

        assertEquals(listOf("12921298"), delivered.map { it.sourceRef })
    }

    @Test
    fun `duplicates and language pairs on an older page are dropped within one poll`() {
        every { client.page(0) } returns page(1)
        unknownOnly("12919651", "12919652", "12919282", "12919283", "12919845", "12919846")

        poller("2026-10-30T12:00:00Z").poll()

        assertEquals(1, delivered.count { it.isin == "NL0015000K93" })
        assertEquals(1, delivered.count { it.isin == "NL0015000GX8" })
        assertEquals(listOf("12919845"), delivered.filter { it.isin == "NL0000302636" }.map { it.sourceRef })
        assertEquals(3, delivered.size)
    }

    @Test
    fun `release stored in an earlier poll makes its copy and its other language version duplicates`() {
        every { client.page(0) } returns page(1)
        unknownOnly("12919652", "12919846")
        every { reports.titledOfIsinBetween(Market.NL, "NL0015000K93", any(), any()) } returns
            listOf(storedReport("12919651", "EUROCOMMERCIAL PROPERTIES N.V.: NINE-MONTH RESULTS 2026", "2026-10-29T16:45:00Z"))
        every { reports.titledOfIsinBetween(Market.NL, "NL0000302636", any(), any()) } returns
            listOf(storedReport("12919845", "Van Lanschot Kempen trading update for the third quarter of 2026", "2026-10-30T06:00:00Z"))
        val poller = poller("2026-10-30T12:00:00Z")

        poller.poll()
        poller.poll()

        assertEquals(emptyList(), delivered)
        verify(exactly = 1) { client.issuer("12919652") }
        verify(exactly = 1) { client.issuer("12919846") }
    }

    @Test
    fun `bond notice without a Symbol is not stored and not read again`() {
        every { client.page(0) } returns page(4)
        unknownOnly("12914496")
        val poller = poller("2026-10-06T12:00:00Z")

        poller.poll()
        poller.poll()

        assertEquals(emptyList(), delivered)
        verify(exactly = 1) { client.issuer("12914496") }
    }

    @Test
    fun `release whose detail cannot be read stops the poll and is retried next time`() {
        unknownOnly("12921454", "12921499", "12921756")
        every { client.issuer("12921499") } throws SourceUnavailableException(Source.EURONEXT, IOException("timeout"))
        val poller = poller("2026-11-05T14:00:00Z")

        poller.poll()
        assertEquals(listOf("12921454"), delivered.map { it.sourceRef })
        stored += "12921454"

        every { client.issuer("12921499") } answers { parser.issuer(checkNotNull(fixture("node-12921499.html"))) }
        poller.poll()

        assertEquals(listOf("12921454", "12921499", "12921756"), delivered.map { it.sourceRef })
    }

    @Test
    fun `release that no longer exists is skipped instead of blocking the market`() {
        unknownOnly("12921499", "12921756")
        every { client.issuer("12921499") } throws SourceUnavailableException(Source.EURONEXT, HttpClientErrorException(HttpStatus.NOT_FOUND))

        poller("2026-11-05T14:00:00Z").poll()

        assertEquals(listOf("12921756"), delivered.map { it.sourceRef })
    }

    @Test
    fun `rate limited release stops the poll like a timeout`() {
        unknownOnly("12921499", "12921756")
        every { client.issuer("12921499") } throws SourceUnavailableException(Source.EURONEXT, HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS))

        poller("2026-11-05T14:00:00Z").poll()

        assertEquals(emptyList(), delivered)
        verify(exactly = 0) { client.issuer("12921756") }
    }

    @Test
    fun `paging continues while pages hold only new releases and stops at a known one`() {
        every { reports.existsForMarket(Market.NL) } returns true
        every { reports.existsBySourceRef(Market.NL, any()) } answers { secondArg<String>() == "12919845" }
        every { client.issuer(any()) } returns EuronextIssuer(null, null)

        poller("2026-11-05T14:00:00Z").poll()

        verify(exactly = 1) { client.page(1) }
        verify(exactly = 0) { client.page(2) }
        assertTrue(delivered.isEmpty())
    }
}
