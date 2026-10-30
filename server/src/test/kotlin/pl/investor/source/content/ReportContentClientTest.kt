package pl.investor.source.content

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pl.investor.report.Market
import pl.investor.report.ReportKind
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
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
    private val client = ReportContentClient(http, ReportContentParser(), locator, attachments)

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
        every { attachments.relevantText("https://www.bankier.pl/wiadomosc/x.html") } returns "Przychody 992 558"

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
        every { attachments.relevantText(any()) } throws java.time.format.DateTimeParseException("bad", "x", 0)

        val content = client.fetch(entry, includeAttachments = true)

        assertTrue(content.attachmentsPending)
        assertTrue(content.text.isNotBlank() || content.selectedData.isNotEmpty() || content.attachmentUrls.isNotEmpty())
    }

    @Test
    fun `located article without attachment text is marked pending`() {
        every { locator.find(entry) } returns "https://www.bankier.pl/wiadomosc/x.html"
        every { attachments.relevantText(any()) } returns ""

        val content = client.fetch(entry, includeAttachments = true)

        assertTrue(content.attachmentsPending)
        assertEquals("", content.attachmentText)
    }
}
