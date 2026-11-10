package pl.investor.source.content

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.investor.report.Market
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import pl.investor.source.eqs.EqsReleaseParser
import pl.investor.source.euronext.EuronextReleaseParser
import pl.investor.source.gpw.ListingEntry
import pl.investor.source.nasdaq.NasdaqViewParser

@Component
class ReportContentClient(
    private val http: PoliteHttpClient,
    private val parser: ReportContentParser,
    private val locator: BankierReportLocator,
    private val attachments: AttachmentTextExtractor,
    private val nasdaqParser: NasdaqViewParser,
    private val eqsParser: EqsReleaseParser,
    private val euronextParser: EuronextReleaseParser,
) {

    private val log = LoggerFactory.getLogger(ReportContentClient::class.java)

    fun fetch(entry: ListingEntry, includeAttachments: Boolean = false): ReportContent =
        when (entry.market) {
            Market.SE, Market.FI -> fetchNasdaq(entry, includeAttachments)
            Market.DE -> eqsParser.parse(http.get(Source.EQS, entry.detailUrl), entry.detailUrl)
            Market.NL -> fetchEuronext(entry, includeAttachments)
            Market.GPW, Market.NC -> fetchEspi(entry, includeAttachments)
            Market.US -> error("No report content for ${entry.market}")
        }

    private fun fetchEspi(entry: ListingEntry, includeAttachments: Boolean): ReportContent {
        val content = parser.parse(http.get(entry.market.espiSource(), entry.detailUrl), entry.market.espiBaseUrl(), entry.detailUrl)
        return if (includeAttachments && content.hasSupportedAttachments) content.withAttachmentsOf(entry) else content
    }

    private fun fetchNasdaq(entry: ListingEntry, includeAttachments: Boolean): ReportContent {
        val content = nasdaqParser.parse(http.get(Source.NASDAQ_NORDIC, entry.detailUrl), entry.detailUrl)
        return if (includeAttachments && content.hasReadableAttachments) content.withNasdaqAttachments(entry) else content
    }

    private fun fetchEuronext(entry: ListingEntry, includeAttachments: Boolean): ReportContent {
        val content = euronextParser.content(http.get(Source.EURONEXT, entry.detailUrl), entry.detailUrl)
        val readPdfs = (includeAttachments || content.text.length < SHORT_EURONEXT_BODY_CHARS) && content.hasReadableAttachments
        return if (readPdfs) content.withAttachmentText(entry) { attachments.readEuronextPdfs(content.attachmentUrls) }.withPdfsConsumed() else content
    }

    private fun ReportContent.withPdfsConsumed(): ReportContent = if (attachmentText.isEmpty()) this else copy(attachmentUrls = emptyList())

    private fun ReportContent.withAttachmentsOf(entry: ListingEntry): ReportContent =
        withAttachmentText(entry) { locator.find(entry)?.let(attachments::read) }

    private fun ReportContent.withNasdaqAttachments(entry: ListingEntry): ReportContent =
        withAttachmentText(entry) { attachments.readNasdaqPdfs(attachmentUrls) }

    private fun ReportContent.withAttachmentText(entry: ListingEntry, load: () -> AttachmentReading?): ReportContent =
        try {
            val reading = load()
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

    private fun Market.espiSource() = when (this) {
        Market.GPW -> Source.GPW_LISTING
        Market.NC -> Source.NC_LISTING
        Market.SE, Market.DE, Market.NL, Market.FI, Market.US -> error("No ESPI listing for $this")
    }

    private fun Market.espiBaseUrl() = when (this) {
        Market.GPW -> "https://www.gpw.pl/"
        Market.NC -> "https://newconnect.pl/"
        Market.SE, Market.DE, Market.NL, Market.FI, Market.US -> error("No ESPI page for $this")
    }

    private companion object {
        const val SHORT_EURONEXT_BODY_CHARS = 1500
    }
}
