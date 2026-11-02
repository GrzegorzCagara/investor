package pl.investor.source.content

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.investor.report.Market
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import pl.investor.source.gpw.ListingEntry

@Component
class ReportContentClient(
    private val http: PoliteHttpClient,
    private val parser: ReportContentParser,
    private val locator: BankierReportLocator,
    private val attachments: AttachmentTextExtractor,
) {

    private val log = LoggerFactory.getLogger(ReportContentClient::class.java)

    fun fetch(entry: ListingEntry, includeAttachments: Boolean = false): ReportContent {
        val content = parser.parse(http.get(entry.market.source(), entry.detailUrl), entry.market.baseUrl(), entry.detailUrl)
        return if (includeAttachments && content.hasSupportedAttachments) content.withAttachmentsOf(entry) else content
    }

    private fun ReportContent.withAttachmentsOf(entry: ListingEntry): ReportContent =
        try {
            val reading = locator.find(entry)?.let(attachments::read)
            when {
                reading == null -> copy(attachmentsPending = true)
                reading.text.isNotEmpty() -> copy(attachmentText = reading.text)
                reading.nothingReadable -> this
                else -> copy(attachmentsPending = true)
            }
        } catch (e: Exception) {
            log.warn("Attachments of {} unavailable for now: {}", entry.detailUrl, e.message)
            copy(attachmentsPending = true)
        }

    private fun Market.source() = when (this) {
        Market.GPW -> Source.GPW_LISTING
        Market.NC -> Source.NC_LISTING
        Market.US -> error("No report content for $this")
    }

    private fun Market.baseUrl() = when (this) {
        Market.GPW -> "https://www.gpw.pl/"
        Market.NC -> "https://newconnect.pl/"
        Market.US -> error("No report content for $this")
    }
}
