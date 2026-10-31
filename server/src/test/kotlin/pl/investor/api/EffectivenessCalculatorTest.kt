package pl.investor.api

import pl.investor.outcome.Outcome
import pl.investor.report.EventType
import pl.investor.report.Market
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EffectivenessCalculatorTest {

    private val start = Instant.parse("2026-08-06T15:00:00Z")

    private fun outcome(
        reaction: Double? = null,
        reactionIndex: Double? = null,
        r5: Double? = null, i5: Double? = null,
        r20: Double? = null, i20: Double? = null,
        r60: Double? = null, i60: Double? = null,
    ) = Outcome(LocalDate.of(2026, 8, 6), reaction, reactionIndex, null, r5, i5, r20, i20, r60, i60)

    private fun result(id: Long, total: Double, outcome: Outcome? = null, hoursLater: Long = id) = ScoredOutcome(
        id = id,
        companyName = "Firma$id",
        market = Market.GPW,
        eventType = EventType.RESULTS,
        publishedAt = start.plusSeconds(hoursLater * 3600),
        total = total,
        outcome = outcome,
    )

    private fun EffectivenessResponse.bucket(label: String) = buckets.single { it.eventType == EventType.RESULTS && it.label == label }

    private fun EffectivenessResponse.reaction(reaction: Reaction) = byReaction.single { it.reaction == reaction }

    private fun List<HorizonStats>.at(sessions: Int) = single { it.sessions == sessions }

    @Test
    fun `scores are bucketed by the displayed score and below 50 is excluded`() {
        val response = EffectivenessCalculator.calculate(
            listOf(result(1, 49.4), result(2, 49.5), result(3, 59.4), result(4, 59.5), result(5, 79.5), result(6, 100.0)),
        )

        assertEquals(listOf("50–59", "60–69", "70–79", "80+"), response.buckets.map { it.label })
        assertEquals(2, response.bucket("50–59").alerts)
        assertEquals(1, response.bucket("60–69").alerts)
        assertEquals(0, response.bucket("70–79").alerts)
        assertEquals(2, response.bucket("80+").alerts)
        assertEquals(listOf(6L, 5L, 4L, 3L, 2L), response.items.map { it.id })
        assertEquals(listOf(100, 80, 60, 59, 50), response.items.map { it.score })
    }

    @Test
    fun `empty input still has every bucket with zero alerts and null averages`() {
        val response = EffectivenessCalculator.calculate(emptyList())

        assertEquals(EventType.entries.size * 4, response.buckets.size)
        assertEquals(Reaction.entries.toList(), response.byReaction.map { it.reaction })
        (response.buckets.map { it.alerts to it.horizons } + response.byReaction.map { it.alerts to it.horizons }).forEach { (alerts, horizons) ->
            assertEquals(0, alerts)
            assertEquals(listOf(5, 20, 60), horizons.map { it.sessions })
            horizons.forEach {
                assertEquals(0, it.count)
                assertNull(it.avgExcessPct)
            }
        }
        assertEquals(emptyList(), response.items)
    }

    @Test
    fun `average excess uses only items where both returns are known`() {
        val response = EffectivenessCalculator.calculate(
            listOf(
                result(1, 72.0, outcome(r5 = 10.0, i5 = 2.0, r20 = 5.0, i20 = null)),
                result(2, 75.0, outcome(r5 = -1.0, i5 = 1.0, r20 = 4.0, i20 = 1.0)),
                result(3, 78.0, outcome(r5 = null, i5 = 3.0)),
                result(4, 74.0),
            ),
        )

        val horizons = response.bucket("70–79").horizons
        assertEquals(4, response.bucket("70–79").alerts)
        assertEquals(2, horizons.at(5).count)
        assertEquals(3.0, horizons.at(5).avgExcessPct!!, 1e-9)
        assertEquals(1, horizons.at(20).count)
        assertEquals(3.0, horizons.at(20).avgExcessPct!!, 1e-9)
        assertEquals(0, horizons.at(60).count)
        assertNull(horizons.at(60).avgExcessPct)
        val item = response.items.single { it.id == 1L }
        assertEquals(8.0, item.excess5Pct!!, 1e-9)
        assertNull(item.excess20Pct)
        assertNull(item.excess60Pct)
    }

    @Test
    fun `reaction split uses the excess reaction with a one point flat band`() {
        val response = EffectivenessCalculator.calculate(
            listOf(
                result(1, 80.0, outcome(reaction = 3.0, reactionIndex = 2.5, r5 = 12.0, i5 = 2.0)),
                result(2, 80.0, outcome(reaction = -0.9, reactionIndex = null, r5 = 6.0, i5 = 2.0)),
                result(3, 80.0, outcome(reaction = 0.5, reactionIndex = 2.0)),
                result(4, 80.0, outcome(reaction = 1.0, reactionIndex = null, r5 = 1.0, i5 = 0.0)),
                result(5, 80.0, outcome(reaction = null, reactionIndex = 1.0, r5 = 50.0, i5 = 0.0)),
                result(6, 80.0),
                result(7, 40.0, outcome(reaction = -5.0, reactionIndex = 0.0)),
            ),
        )

        val flat = response.reaction(Reaction.FLAT)
        assertEquals(2, flat.alerts)
        assertEquals(2, flat.horizons.at(5).count)
        assertEquals(7.0, flat.horizons.at(5).avgExcessPct!!, 1e-9)
        assertEquals(1, response.reaction(Reaction.NEGATIVE).alerts)
        assertEquals(0, response.reaction(Reaction.NEGATIVE).horizons.at(5).count)
        val positive = response.reaction(Reaction.POSITIVE)
        assertEquals(1, positive.alerts)
        assertEquals(1.0, positive.horizons.at(5).avgExcessPct!!, 1e-9)
        assertEquals(6, response.bucket("80+").alerts)
        val item = response.items.single { it.id == 2L }
        assertEquals(-0.9, item.reactionPct!!, 1e-9)
        assertNull(item.reactionIndexPct)
    }

    @Test
    fun `items are newest first and capped at 100`() {
        val response = EffectivenessCalculator.calculate((1L..120L).map { result(it, 60.0) }.shuffled())

        assertEquals(100, response.items.size)
        assertEquals(120L, response.items.first().id)
        assertEquals(21L, response.items.last().id)
        assertEquals(120, response.bucket("60–69").alerts)
    }

    @Test
    fun `reaction category follows the shared rule`() {
        assertEquals(Reaction.FLAT, outcome(reaction = 0.99).reactionCategory())
        assertEquals(Reaction.POSITIVE, outcome(reaction = 1.0).reactionCategory())
        assertEquals(Reaction.NEGATIVE, outcome(reaction = -2.0, reactionIndex = -0.5).reactionCategory())
        assertEquals(Reaction.FLAT, outcome(reaction = -2.0, reactionIndex = -1.5).reactionCategory())
        assertNull(outcome(reaction = null, reactionIndex = 1.0).reactionCategory())
    }
}
