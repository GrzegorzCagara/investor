package pl.investor.source.eqs

import pl.investor.report.Market
import pl.investor.report.ReportKind
import pl.investor.source.gpw.ListingEntry
import java.time.Instant

data class EqsNewsItem(
    val newsId: String,
    val company: String,
    val isin: String,
    val category: String,
    val title: String?,
    val publishedAt: Instant,
    val newsUrl: String,
) {
    val isCovered: Boolean get() = EqsCategories.covers(isin, category)

    fun toListingEntry() = ListingEntry(
        market = Market.DE,
        companyName = company,
        isin = isin,
        kind = ReportKind.CURRENT,
        number = category,
        sourceRef = newsId,
        title = title,
        publishedAt = publishedAt,
        detailUrl = newsUrl,
    )
}
