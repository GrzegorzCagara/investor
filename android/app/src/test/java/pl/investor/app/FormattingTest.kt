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
        assertEquals("SE", Formatting.marketLabel(Market.SE))
        assertEquals("Szwecja", Formatting.marketName(Market.SE))
        assertEquals("DE", Formatting.marketLabel(Market.DE))
        assertEquals("Niemcy", Formatting.marketName(Market.DE))
        assertEquals("GPW", Formatting.marketName(Market.GPW))
        assertEquals("USA", Formatting.marketName(Market.US))
    }

    @Test
    fun `only covered markets are offered as filters and switches`() {
        assertEquals(listOf(Market.GPW, Market.NC, Market.SE, Market.DE), Market.covered)
    }

    @Test
    fun `detail title adds the listing when known`() {
        val detail = detail(Market.SE).copy(listing = "Large Cap Stockholm")
        assertEquals("Mips (SE · Large Cap Stockholm)", Formatting.detailTitle(detail))
        assertEquals("Mips (GPW)", Formatting.detailTitle(detail(Market.GPW)))
        assertEquals("Nasdaq · SE · raport kwartalny, 08:00", Formatting.detailHeadline(detail, now))
    }

    @Test
    fun `German detail title and headline`() {
        val detail = detail(Market.DE).copy(reportNumber = "EQS", listing = "Prime Standard")
        assertEquals("Mips (DE · Prime Standard)", Formatting.detailTitle(detail))
        assertEquals("EQS · DE · raport kwartalny, 08:00", Formatting.detailHeadline(detail, now))
    }

    @Test
    fun `whole percent lines for YTD and orders`() {
        assertEquals("Kurs od początku roku: −66%", Formatting.ytdLine(-66.0))
        assertEquals("Zamówienia r/r: +25%", Formatting.ordersLine(25.0))
        assertEquals("Zamówienia r/r: +1%", Formatting.ordersLine(0.5))
        assertEquals("0%", Formatting.wholePercent(-0.4))
        assertEquals("—", Formatting.wholePercent(null))
    }

    @Test
    fun `past reaction date and sign`() {
        assertEquals("16.07.26", Formatting.reactionDate("2026-07-16"))
        assertEquals("zła-data", Formatting.reactionDate("zła-data"))
        assertEquals(1, Formatting.sign(27.8))
        assertEquals(-1, Formatting.sign(-0.5))
        assertEquals(0, Formatting.sign(-0.04))
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
            listOf("Konsensus PAP: Przychody +0,6%, EBITDA −3,1%"),
            Formatting.consensusLines(
                listOf(ConsensusRowView("Przychody", 1696.7, 1686.3, 0.6), ConsensusRowView("EBITDA", 90.0, 92.9, -3.1)),
            ),
        )
        assertEquals(emptyList<String>(), Formatting.consensusLines(emptyList()))
    }

    @Test
    fun `sourced consensus rows show amounts in the currency without the PAP prefix`() {
        assertEquals(
            listOf(
                "Sprzedaż vs konsensus FactSet (6 analityków): 215,0 wobec 216,0 mln SEK (−0,5%)",
                "Zysk operacyjny vs konsensus FactSet (6 analityków): 60,0 wobec 58,0 mln SEK (+3,4%)",
            ),
            Formatting.consensusLines(
                listOf(
                    ConsensusRowView("Sprzedaż vs konsensus FactSet (6 analityków)", 215.0, 216.0, -0.46, "SEK"),
                    ConsensusRowView("Zysk operacyjny vs konsensus FactSet (6 analityków)", 60.0, 58.0, 3.4, "SEK"),
                ),
            ),
        )
        assertEquals(
            listOf("Umsatz vs konsensus: 82,2 wobec 80,0 mln EUR (+2,8%)"),
            Formatting.consensusLines(listOf(ConsensusRowView("Umsatz vs konsensus", 82.2, 80.0, 2.75, "EUR"))),
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
        assertEquals("14.05 · Wyniki · po 20 sesjach −1,3%", Formatting.historyLine(item, now))
        assertEquals("14.05 · Wyniki · po 20 sesjach —", Formatting.historyLine(item.copy(excess20Pct = null), now))
        assertEquals("14.05 · Wydarzenie · po 20 sesjach −1,3%", Formatting.historyLine(item.copy(eventType = EventType.NEWS), now))
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
        assertEquals("Wydarzenia", Formatting.aiPoolLabel("NEWS"))
        assertEquals("Szwecja – wyniki", Formatting.aiPoolLabel("SE"))
        assertEquals("Szwecja – wydarzenia", Formatting.aiPoolLabel("SE_NEWS"))
        assertEquals("Niemcy – wyniki", Formatting.aiPoolLabel("DE"))
        assertEquals("Niemcy – wydarzenia", Formatting.aiPoolLabel("DE_NEWS"))
        assertEquals("Nasdaq (komunikaty)", Formatting.sourceLabel("NASDAQ_NORDIC"))
        assertEquals("Avanza", Formatting.sourceLabel("AVANZA"))
        assertEquals("Yahoo (indeks)", Formatting.sourceLabel("YAHOO"))
        assertEquals("Placera (konsensus)", Formatting.sourceLabel("PLACERA"))
        assertEquals("XTB (lista akcji)", Formatting.sourceLabel("XTB"))
        assertEquals("EQS News", Formatting.sourceLabel("EQS"))
        assertEquals("Spadek w dniu raportu", Formatting.reactionLabel(Reaction.NEGATIVE))
        assertEquals("Płasko", Formatting.reactionLabel(Reaction.FLAT))
        assertEquals("Wzrost", Formatting.reactionLabel(Reaction.POSITIVE))
    }

    @Test
    fun `event type labels`() {
        assertEquals("Wyniki", Formatting.eventTypeLabel(EventType.RESULTS))
        assertEquals("Wydarzenia", Formatting.eventTypeLabel(EventType.NEWS))
        assertEquals("Wyniki", Formatting.alertTypeLabel(EventType.RESULTS))
        assertEquals("Wydarzenie", Formatting.alertTypeLabel(EventType.NEWS))
    }

    @Test
    fun `alert line shows the time, the type and the summary`() {
        val alert = AlertItem(
            id = 1,
            companyName = "Mirbud",
            market = Market.GPW,
            eventType = EventType.NEWS,
            publishedAt = Instant.parse("2026-09-21T06:09:00Z"),
            score = 78,
            belowThreshold = false,
            summary = "Umowa na 18,4 mln PLN.",
            status = ReportStatus.SCORED,
        )
        assertEquals("08:09 · Wydarzenie · Umowa na 18,4 mln PLN.", Formatting.alertLine(alert, now))
        assertEquals("08:09 · Wyniki · Umowa na 18,4 mln PLN.", Formatting.alertLine(alert.copy(eventType = EventType.RESULTS), now))
    }

    @Test
    fun `contract value adds the revenue share when known`() {
        assertEquals(
            "18,4 mln PLN (11,8% rocznych przychodów)",
            Formatting.contractValueLine(NewsView("Umowa", "pozytywny", "18,4 mln PLN", 11.84)),
        )
        assertEquals(
            "0,2 mln PLN (<0,1% rocznych przychodów)",
            Formatting.contractValueLine(NewsView("Umowa", "pozytywny", "0,2 mln PLN", 0.04)),
        )
        assertEquals("18,4 mln PLN", Formatting.contractValueLine(NewsView("Umowa", "pozytywny", "18,4 mln PLN", null)))
        assertEquals(null, Formatting.contractValueLine(NewsView("Dywidenda", "pozytywny", null, null)))
    }

    private fun detail(market: Market) = AlertDetail(
        id = 1,
        companyName = "Mips",
        market = market,
        reportNumber = "Nasdaq",
        reportKind = ReportKind.QUARTERLY,
        publishedAt = Instant.parse("2026-09-21T06:00:00Z"),
        components = emptyList(),
        penalty = 0.0,
        warnings = emptyList(),
        reason = "",
        revenueHistory = emptyList(),
        sourceUrl = "https://example.test/r",
    )
}
