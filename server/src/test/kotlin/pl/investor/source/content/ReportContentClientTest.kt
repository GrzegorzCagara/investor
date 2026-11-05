package pl.investor.source.content

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pl.investor.report.Market
import pl.investor.report.ReportKind
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import pl.investor.source.nasdaq.NasdaqViewParser
import pl.investor.source.SourceUnavailableException
import pl.investor.source.gpw.ListingEntry
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportContentClientTest {

    private val http = mockk<PoliteHttpClient>()
    private val locator = mockk<BankierReportLocator>()
    private val attachments = mockk<AttachmentTextExtractor>()
    private val client = ReportContentClient(http, ReportContentParser(), locator, attachments, NasdaqViewParser(), pl.investor.source.eqs.EqsReleaseParser(), pl.investor.source.euronext.EuronextReleaseParser())

    private val entry = ListingEntry(
        market = Market.GPW,
        companyName = "XTB S.A.",
        isin = "PLXTRDM00011",
        kind = ReportKind.CURRENT,
        number = "18/2026",
        sourceRef = "494560",
        title = "Wstępne wyniki",
        publishedAt = Instant.parse("2026-07-29T15:14:57Z"),
        detailUrl = "https://www.gpw.pl/komunikat?geru_id=494560",
    )

    init {
        val html = checkNotNull(javaClass.getResource("/fixtures/reports/xtb-prelim-q2-2026.html")).readText()
        every { http.get(Source.GPW_LISTING, entry.detailUrl) } returns html
    }

    @Test
    fun `attachments are not touched by default`() {
        val content = client.fetch(entry)

        assertEquals("", content.attachmentText)
        assertFalse(content.attachmentsPending)
        verify(exactly = 0) { locator.find(any()) }
    }

    @Test
    fun `attachment text is filled when the article is located`() {
        every { locator.find(entry) } returns "https://www.bankier.pl/wiadomosc/x.html"
        every { attachments.read("https://www.bankier.pl/wiadomosc/x.html") } returns AttachmentReading("Przychody 992 558", nothingReadable = false)

        val content = client.fetch(entry, includeAttachments = true)

        assertEquals("Przychody 992 558", content.attachmentText)
        assertFalse(content.attachmentsPending)
    }

    @Test
    fun `missing Bankier copy marks attachments pending`() {
        every { locator.find(entry) } returns null

        val content = client.fetch(entry, includeAttachments = true)

        assertTrue(content.attachmentsPending)
        assertEquals("", content.attachmentText)
    }

    @Test
    fun `Bankier outage marks attachments pending instead of failing`() {
        every { locator.find(entry) } throws SourceUnavailableException(Source.BANKIER, RuntimeException("down"))

        assertTrue(client.fetch(entry, includeAttachments = true).attachmentsPending)
    }

    @Test
    fun `any failure while reading the article keeps the body and marks attachments pending`() {
        every { locator.find(entry) } returns "https://www.bankier.pl/wiadomosc/x.html"
        every { attachments.read(any()) } throws java.time.format.DateTimeParseException("bad", "x", 0)

        val content = client.fetch(entry, includeAttachments = true)

        assertTrue(content.attachmentsPending)
        assertTrue(content.text.isNotBlank() || content.selectedData.isNotEmpty() || content.attachmentUrls.isNotEmpty())
    }

    @Test
    fun `report page without attachments is not pending and Bankier is not asked`() {
        listOf(
            "reports/crj-prelim-q1-2026.html" to Market.GPW,
            "nc/report-beein-current-sales-2026-10-29.html" to Market.NC,
        ).forEach { (fixture, market) ->
            val withoutAttachments = entry.copy(market = market, detailUrl = "https://example.test/$fixture")
            val source = if (market == Market.GPW) Source.GPW_LISTING else Source.NC_LISTING
            every { http.get(source, withoutAttachments.detailUrl) } returns checkNotNull(javaClass.getResource("/fixtures/$fixture")).readText()

            val content = client.fetch(withoutAttachments, includeAttachments = true)

            assertEquals(emptyList(), content.attachmentUrls, fixture)
            assertFalse(content.attachmentsPending, fixture)
            assertEquals("", content.attachmentText)
        }
        verify(exactly = 0) { locator.find(any()) }
    }

    @Test
    fun `report pages with attachments still go to Bankier`() {
        listOf(
            "reports/asbis-ps-2026.html" to Market.GPW,
            "nc/report-present24-q2-2026-08-14.html" to Market.NC,
        ).forEach { (fixture, market) ->
            val withAttachments = entry.copy(market = market, detailUrl = "https://example.test/$fixture")
            val source = if (market == Market.GPW) Source.GPW_LISTING else Source.NC_LISTING
            every { http.get(source, withAttachments.detailUrl) } returns checkNotNull(javaClass.getResource("/fixtures/$fixture")).readText()
            every { locator.find(withAttachments) } returns null

            val content = client.fetch(withAttachments, includeAttachments = true)

            assertTrue(content.attachmentUrls.isNotEmpty(), fixture)
            assertTrue(content.attachmentsPending, fixture)
            verify(exactly = 1) { locator.find(withAttachments) }
        }
    }

    @Test
    fun `located article whose archives hold nothing readable is not pending`() {
        every { locator.find(entry) } returns "https://www.bankier.pl/wiadomosc/x.html"
        every { attachments.read(any()) } returns AttachmentReading("", nothingReadable = true)

        val content = client.fetch(entry, includeAttachments = true)

        assertFalse(content.attachmentsPending)
        assertEquals("", content.attachmentText)
    }

    @Test
    fun `report page whose attachments are all unsupported is not pending and Bankier is not asked`() {
        val unsupported = entry.copy(detailUrl = "https://example.test/unsupported")
        val html = checkNotNull(javaClass.getResource("/fixtures/reports/asbis-ps-2026.html")).readText()
            .replace(Regex("""(attachment/zal\d+_[^",]+)\.pdf"""), "$1.docx")
        every { http.get(Source.GPW_LISTING, unsupported.detailUrl) } returns html

        val content = client.fetch(unsupported, includeAttachments = true)

        assertTrue(content.attachmentUrls.isNotEmpty())
        assertFalse(content.attachmentsPending)
        verify(exactly = 0) { locator.find(any()) }
    }

    @Test
    fun `report page with a rar attachment goes to Bankier`() {
        val rar = entry.copy(detailUrl = "https://example.test/rar")
        val html = checkNotNull(javaClass.getResource("/fixtures/reports/asbis-ps-2026.html")).readText()
            .replace(Regex("""(attachment/zal\d+_[^",]+)\.pdf"""), "$1.rar")
        every { http.get(Source.GPW_LISTING, rar.detailUrl) } returns html
        every { locator.find(rar) } returns "https://www.bankier.pl/wiadomosc/rar.html"
        every { attachments.read("https://www.bankier.pl/wiadomosc/rar.html") } returns AttachmentReading("Revenue from sale of goods 26 380", nothingReadable = false)

        val content = client.fetch(rar, includeAttachments = true)

        assertEquals("Revenue from sale of goods 26 380", content.attachmentText)
        assertFalse(content.attachmentsPending)
    }

    @Test
    fun `located article without attachment text is marked pending`() {
        every { locator.find(entry) } returns "https://www.bankier.pl/wiadomosc/x.html"
        every { attachments.read(any()) } returns AttachmentReading("", nothingReadable = false)

        val content = client.fetch(entry, includeAttachments = true)

        assertTrue(content.attachmentsPending)
        assertEquals("", content.attachmentText)
    }

    private fun euronext(nid: String) = entry.copy(
        market = Market.NL,
        number = "Euronext",
        sourceRef = nid,
        detailUrl = "https://live.euronext.com/en/ajax/node/company-press-release/$nid",
    ).also { every { http.get(Source.EURONEXT, it.detailUrl) } returns checkNotNull(javaClass.getResource("/fixtures/nl/node-$nid.html")).readText() }

    @Test
    fun `short Euronext body is completed with its PDF even before attachments are asked for`() {
        val imcd = euronext("12921454")
        every { attachments.readEuronextPdfs(any()) } returns AttachmentReading("Revenue EUR 3,965 million", nothingReadable = false)

        val content = client.fetch(imcd)

        assertEquals("Revenue EUR 3,965 million", content.attachmentText)
        assertTrue(content.attachmentUrls.isEmpty())
        assertFalse(content.hasReadableAttachments)
    }

    @Test
    fun `long Euronext body is read without PDFs and a PDF not yet available leaves attachments pending`() {
        val vopak = euronext("12921130")
        val bam = euronext("12921499")
        every { attachments.readEuronextPdfs(any()) } throws SourceUnavailableException(Source.EURONEXT, RuntimeException("503"))

        assertEquals("", client.fetch(vopak).attachmentText)
        verify(exactly = 0) { attachments.readEuronextPdfs(any()) }
        val pending = client.fetch(bam)
        assertTrue(pending.attachmentsPending)
        assertTrue(pending.hasReadableAttachments)
    }
}
