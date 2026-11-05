package pl.investor.source.euronext

import pl.investor.report.Market
import pl.investor.report.ReportKind
import pl.investor.source.gpw.ListingEntry
import java.time.Instant

data class EuronextRow(val nid: String, val company: String, val title: String, val publishedAt: Instant)

data class EuronextIssuer(val isin: String?, val ticker: String?)

data class EuronextNewsItem(val row: EuronextRow, val isin: String, val ticker: String, val detailUrl: String) {

    fun published() = PublishedRelease(isin, row.title, row.publishedAt)

    fun toListingEntry() = ListingEntry(
        market = Market.NL,
        companyName = row.company,
        isin = isin,
        kind = ReportKind.CURRENT,
        number = EURONEXT_REPORT_NUMBER,
        sourceRef = row.nid,
        title = row.title,
        publishedAt = row.publishedAt,
        detailUrl = detailUrl,
        exchangeTicker = ticker,
    )

    companion object {
        const val EURONEXT_REPORT_NUMBER = "Euronext"
    }
}
