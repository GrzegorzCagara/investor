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
    val languagePreference: Int get() = PREFERRED_LANGUAGES.indexOf(language).takeIf { it >= 0 } ?: PREFERRED_LANGUAGES.size

    fun toListingEntry(market: Market) = ListingEntry(
        market = market,
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
        val PREFERRED_LANGUAGES = listOf("en", "sv", "fi")
    }
}
