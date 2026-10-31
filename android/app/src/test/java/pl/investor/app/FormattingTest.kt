package pl.investor.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FormattingTest {

    private val now = Instant.parse("2026-09-21T10:00:00Z")

    @Test
    fun `time formatting in Warsaw`() {
        assertEquals("08:09", Formatting.time(Instant.parse("2026-09-21T06:09:00Z"), now))
        assertEquals("wczoraj 22:14", Formatting.time(Instant.parse("2026-09-20T20:14:00Z"), now))
        assertEquals("18.09 08:09", Formatting.time(Instant.parse("2026-09-18T06:09:00Z"), now))
    }

    @Test
    fun `latest quote session date`() {
        assertEquals("30.10.2026", Formatting.sessionDate("2026-10-30"))
        assertEquals("brak", Formatting.sessionDate(null))
        assertEquals("brak", Formatting.sessionDate("nie-data"))
    }

    @Test
    fun `day boundary follows Warsaw time not UTC`() {
        assertEquals("00:20", Formatting.time(Instant.parse("2026-09-20T22:20:00Z"), now))
        assertEquals("wczoraj 23:55", Formatting.time(Instant.parse("2026-09-20T21:55:00Z"), now))
    }

    @Test
    fun `section titles`() {
        assertEquals("Dziś", Formatting.sectionTitle(Instant.parse("2026-09-21T06:09:00Z"), now))
        assertEquals("Wczoraj", Formatting.sectionTitle(Instant.parse("2026-09-20T20:14:00Z"), now))
        assertEquals("18.09", Formatting.sectionTitle(Instant.parse("2026-09-18T06:09:00Z"), now))
    }

    @Test
    fun `market labels`() {
        assertEquals("GPW", Formatting.marketLabel(Market.GPW))
        assertEquals("NC", Formatting.marketLabel(Market.NC))
        assertEquals("USA", Formatting.marketLabel(Market.US))
    }

    @Test
    fun `points drop the fraction when whole`() {
        assertEquals("26", Formatting.points(26.0))
        assertEquals("12,5", Formatting.points(12.5))
    }

    @Test
    fun `percent has a sign, a Polish comma and one decimal`() {
        assertEquals("+2,8%", Formatting.percent(2.8))
        assertEquals("−1,2%", Formatting.percent(-1.2))
        assertEquals("+2,9%", Formatting.percent(2.85))
        assertEquals("−3,1%", Formatting.percent(-3.06))
        assertEquals("+10,0%", Formatting.percent(10.0))
        assertEquals("0,0%", Formatting.percent(0.0))
        assertEquals("0,0%", Formatting.percent(-0.04))
        assertEquals("—", Formatting.percent(null))
    }

    @Test
    fun `multiple and usd amounts`() {
        assertEquals("3,1×", Formatting.multiple(3.06))
        assertEquals("2,5×", Formatting.multiple(2.5))
        assertEquals("—", Formatting.multiple(null))
        assertEquals("1,90 USD", Formatting.usd(1.9))
        assertEquals("0,42 z 2,85 USD", Formatting.aiUsage(0.4249, 2.85))
        assertEquals("0,10 USD", Formatting.aiUsage(0.1, null))
    }

    @Test
    fun `reaction line skips unknown parts`() {
        assertEquals(
            "Reakcja rynku: kurs −2,8%, WIG +0,4%, wolumen 3,1× średniej",
            Formatting.reactionLine(ReactionView(stockPct = -2.8, indexPct = 0.4, indexName = "WIG", volumeMultiple = 3.1, category = Reaction.NEGATIVE)),
        )
        assertEquals("Reakcja rynku: kurs +1,5%", Formatting.reactionLine(ReactionView(stockPct = 1.5, category = Reaction.POSITIVE)))
    }

    @Test
    fun `consensus line lists differences`() {
        assertEquals(
            "Konsensus PAP: Przychody +0,6%, EBITDA −3,1%",
            Formatting.consensusLine(
                listOf(ConsensusRowView("Przychody", 1696.7, 1686.3, 0.6), ConsensusRowView("EBITDA", 90.0, 92.9, -3.1)),
            ),
        )
    }

    @Test
    fun `horizon cell shows the average with the count or a dash`() {
        assertEquals("+10,0%", Formatting.horizonExcess(HorizonStats(5, 1, 10.0)))
        assertEquals("(1)", Formatting.horizonCount(HorizonStats(5, 1, 10.0)))
        assertEquals("—", Formatting.horizonExcess(HorizonStats(20, 0, null)))
        assertEquals("", Formatting.horizonCount(HorizonStats(20, 0, null)))
        assertEquals("—", Formatting.horizonExcess(null))
        assertEquals("", Formatting.horizonCount(null))
    }

    @Test
    fun `history line uses the 20 session excess`() {
        val item = OutcomeItem(1, "ASBIS", Market.GPW, EventType.RESULTS, Instant.parse("2026-05-14T15:30:00Z"), 82, excess20Pct = -1.25)
        assertEquals("14.05 · po 20 sesjach −1,3%", Formatting.historyLine(item, now))
        assertEquals("14.05 · po 20 sesjach —", Formatting.historyLine(item.copy(excess20Pct = null), now))
    }

    @Test
    fun `date adds the year only for earlier years`() {
        assertEquals("18.09", Formatting.date(Instant.parse("2026-09-18T06:09:00Z"), now))
        assertEquals("30.12.2025", Formatting.date(Instant.parse("2025-12-30T10:00:00Z"), now))
    }

    @Test
    fun `relative time`() {
        assertEquals("nigdy", Formatting.relative(null, now))
        assertEquals("przed chwilą", Formatting.relative(now.minusSeconds(20), now))
        assertEquals("5 min temu", Formatting.relative(now.minusSeconds(300), now))
        assertEquals("3 godz. temu", Formatting.relative(now.minusSeconds(3 * 3600 + 100), now))
        assertEquals("1 dzień temu", Formatting.relative(now.minusSeconds(30 * 3600), now))
        assertEquals("4 dni temu", Formatting.relative(now.minusSeconds(4 * 86400), now))
    }

    @Test
    fun `labels for sources, pools and reactions`() {
        assertEquals("Komunikaty GPW", Formatting.sourceLabel("GPW_LISTING"))
        assertEquals("NOWE_ZRODLO", Formatting.sourceLabel("NOWE_ZRODLO"))
        assertEquals("GPW/NC", Formatting.aiPoolLabel("GPW"))
        assertEquals("USA", Formatting.aiPoolLabel("US"))
        assertEquals("Spadek w dniu raportu", Formatting.reactionLabel(Reaction.NEGATIVE))
        assertEquals("Płasko", Formatting.reactionLabel(Reaction.FLAT))
        assertEquals("Wzrost", Formatting.reactionLabel(Reaction.POSITIVE))
    }
}
