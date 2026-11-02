package pl.investor.source.nasdaq

import pl.investor.report.Market
import pl.investor.source.gpw.ListingEntry
import java.time.Instant

data class NasdaqNewsItem(
    val disclosureId: String,
    val headline: String,
    val language: String,
    val category: String,
    val messageUrl: String,
    val publishedAt: Instant,
    val market: String,
    val company: String,
) {
    val isEnglish: Boolean get() = language == ENGLISH

    fun toListingEntry() = ListingEntry(
        market = Market.SE,
        companyName = company,
        isin = null,
        kind = NasdaqCategories.kindOf(category),
        number = category,
        sourceRef = disclosureId,
        title = headline,
        publishedAt = publishedAt,
        detailUrl = messageUrl,
    )

    private companion object {
        const val ENGLISH = "en"
    }
}
