package pl.investor.api

import pl.investor.outcome.Outcome
import pl.investor.report.EventType
import pl.investor.report.Market
import pl.investor.scoring.roundedHalfUp
import java.time.Instant
import kotlin.math.abs

data class ScoredOutcome(
    val id: Long,
    val companyName: String,
    val market: Market,
    val eventType: EventType,
    val publishedAt: Instant,
    val total: Double,
    val outcome: Outcome?,
) {
    val score: Int get() = total.roundedHalfUp().toInt()
}

fun Outcome.reactionCategory(): Reaction? = reactionPct?.let { reactionCategory(it, reactionIndexPct) }

fun reactionCategory(stockPct: Double, indexPct: Double?): Reaction {
    val excess = stockPct - (indexPct ?: 0.0)
    return when {
        abs(excess) < FLAT_BAND_PCT -> Reaction.FLAT
        excess > 0 -> Reaction.POSITIVE
        else -> Reaction.NEGATIVE
    }
}

fun ScoredOutcome.toOutcomeItem() = OutcomeItem(
    id = id,
    companyName = companyName,
    market = market,
    eventType = eventType,
    publishedAt = publishedAt,
    score = score,
    reactionPct = outcome?.reactionPct,
    reactionIndexPct = outcome?.reactionIndexPct,
    excess5Pct = outcome?.excessAfter(5),
    excess20Pct = outcome?.excessAfter(20),
    excess60Pct = outcome?.excessAfter(60),
)

private const val FLAT_BAND_PCT = 1.0

private fun Outcome.excessAfter(sessions: Int): Double? {
    val (stock, index) = when (sessions) {
        5 -> return5Pct to index5Pct
        20 -> return20Pct to index20Pct
        60 -> return60Pct to index60Pct
        else -> throw IllegalArgumentException("Unsupported horizon $sessions")
    }
    return if (stock != null && index != null) stock - index else null
}

object EffectivenessCalculator {

    const val MIN_SCORE = 50
    private const val MAX_ITEMS = 100
    private val HORIZONS = listOf(5, 20, 60)
    private val SCORE_BUCKETS = listOf(
        ScoreRange("50–59", 50..59),
        ScoreRange("60–69", 60..69),
        ScoreRange("70–79", 70..79),
        ScoreRange("80+", 80..Int.MAX_VALUE),
    )

    fun calculate(results: List<ScoredOutcome>): EffectivenessResponse {
        val population = results.filter { it.score >= MIN_SCORE }
        val buckets = EventType.entries.flatMap { eventType ->
            val ofType = population.filter { it.eventType == eventType }
            SCORE_BUCKETS.map { range ->
                val members = ofType.filter { it.score in range.scores }
                ScoreBucketView(eventType, range.label, members.size, members.horizons())
            }
        }
        val byReaction = population
            .mapNotNull { result -> result.outcome?.reactionCategory()?.let { it to result } }
            .groupBy({ it.first }, { it.second })
            .let { grouped ->
                Reaction.entries.map { reaction ->
                    val members = grouped[reaction].orEmpty()
                    ReactionBucketView(reaction, members.size, members.horizons())
                }
            }
        val items = population
            .sortedWith(compareByDescending<ScoredOutcome> { it.publishedAt }.thenByDescending { it.id })
            .take(MAX_ITEMS)
            .map { it.toOutcomeItem() }
        return EffectivenessResponse(buckets, byReaction, items)
    }

    private fun List<ScoredOutcome>.horizons(): List<HorizonStats> = HORIZONS.map { sessions ->
        val excesses = mapNotNull { it.outcome?.excessAfter(sessions) }
        HorizonStats(sessions, excesses.size, excesses.takeIf { it.isNotEmpty() }?.average())
    }

    private data class ScoreRange(val label: String, val scores: IntRange)
}
