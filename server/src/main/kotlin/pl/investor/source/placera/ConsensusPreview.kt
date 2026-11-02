package pl.investor.source.placera

import pl.investor.scoring.PastReaction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class ConsensusEstimate(val consensus: BigDecimal, val prevYear: BigDecimal?, val analysts: Int?)

data class PlaceraArticle(
    val companyName: String,
    val isin: String?,
    val reportDate: LocalDate,
    val currency: String,
    val revenue: ConsensusEstimate?,
    val ebit: ConsensusEstimate?,
    val source: String,
    val pastReactions: List<PastReaction>,
    val publishedAt: Instant,
) {
    fun toPreview(url: String, resolvedIsin: String, fetchedAt: Instant) = ConsensusPreview(
        url = url,
        isin = resolvedIsin,
        companyName = companyName,
        reportDate = reportDate,
        currency = currency,
        revenue = revenue,
        ebit = ebit,
        source = source,
        pastReactions = pastReactions,
        publishedAt = publishedAt,
        fetchedAt = fetchedAt,
    )
}

data class ConsensusPreview(
    val url: String,
    val isin: String,
    val companyName: String,
    val reportDate: LocalDate,
    val currency: String,
    val revenue: ConsensusEstimate?,
    val ebit: ConsensusEstimate?,
    val source: String,
    val pastReactions: List<PastReaction>,
    val publishedAt: Instant,
    val fetchedAt: Instant,
)

class UnparseablePreviewException(message: String) : RuntimeException(message)
