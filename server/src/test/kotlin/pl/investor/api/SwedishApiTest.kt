package pl.investor.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import pl.investor.outcome.Outcome
import pl.investor.outcome.OutcomeRepository
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.report.Report
import pl.investor.report.ReportKind
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import pl.investor.scoring.ConsensusComparison
import pl.investor.scoring.ConsensusMetric
import pl.investor.scoring.PastReaction
import pl.investor.scoring.Score
import pl.investor.scoring.ScoreComponents
import pl.investor.scoring.ScoreContext
import pl.investor.scoring.ScoreRepository
import pl.investor.scoring.Signal
import pl.investor.support.PostgresTestBase
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class SwedishApiTest : PostgresTestBase() {

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var tokenFilter: ApiTokenFilter
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var reports: ReportRepository
    @Autowired lateinit var scores: ScoreRepository
    @Autowired lateinit var outcomes: OutcomeRepository
    @Autowired lateinit var alerts: AlertRepository

    private lateinit var mvc: MockMvc
    private val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters<DefaultMockMvcBuilder>(tokenFilter).build()
        clean()
    }

    @AfterEach
    fun tearDown() = clean()

    private fun clean() {
        jdbc.sql("DELETE FROM push_log").update()
        jdbc.sql("DELETE FROM score").update()
        jdbc.sql("DELETE FROM extraction").update()
        jdbc.sql("UPDATE revenue_period SET report_id = NULL").update()
        jdbc.sql("DELETE FROM report").update()
    }

    private fun report(market: Market, daysAgo: Long, number: String = "1/2026", listing: String? = null): Long {
        val company = companies.save(Company(null, "Spółka" + UUID.randomUUID(), null, market, null, null, false, "Mips", listing = listing))
        val publishedAt = now.minus(daysAgo, ChronoUnit.DAYS)
        return checkNotNull(
            reports.saveIfNew(
                Report(null, checkNotNull(company.id), market, ReportKind.QUARTERLY, number, UUID.randomUUID().toString(), "Interim report", publishedAt, publishedAt, "https://example.test/r", ReportStatus.SCORED),
            )?.id,
        )
    }

    private fun score(reportId: Long, total: Double, signals: Set<Signal>, context: ScoreContext = ScoreContext.EMPTY) =
        scores.save(reportId, Score(total, ScoreComponents(10.0, 10.0, 10.0, 10.0, 10.0, 10.0), 0.0, emptySet(), signals, context), "Powód.", now)

    private fun outcome(reportId: Long, r5: Double, i5: Double) =
        outcomes.save(reportId, Outcome(LocalDate.now().minusDays(30), 1.0, 0.0, null, r5, i5, null, null, null, null), now)

    private fun authorized(path: String) = get(path).header("X-Investor-Token", "test-token")

    @Test
    fun `Swedish detail carries listing, consensus with source, past reactions, YTD, orders and signal labels`() {
        val id = report(Market.SE, 1, number = "Interim report (Q1 and Q3)", listing = "Large Cap Stockholm")
        val context = ScoreContext(
            ytdChangePct = -66.0,
            ordersYoyPct = 25.0,
            pastReactions = listOf(PastReaction(LocalDate.parse("2026-07-16"), 27.8), PastReaction(LocalDate.parse("2026-04-23"), 8.7)),
            consensus = listOf(
                ConsensusComparison(ConsensusMetric.REVENUE, BigDecimal("215000"), BigDecimal("216000"), "SEK", "FactSet", 6),
                ConsensusComparison(ConsensusMetric.EBIT, BigDecimal("60000"), BigDecimal("58000"), "SEK", "FactSet", 1),
            ),
        )
        score(id, 72.0, setOf(Signal.CONSISTENT_REACTION, Signal.TREND_REVERSAL), context)

        mvc.perform(authorized("/api/alerts/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("\$.market").value("SE"))
            .andExpect(jsonPath("\$.reportNumber").value("Nasdaq"))
            .andExpect(jsonPath("\$.listing").value("Large Cap Stockholm"))
            .andExpect(jsonPath("\$.ytdChangePct").value(-66.0))
            .andExpect(jsonPath("\$.ordersYoyPct").value(25.0))
            .andExpect(jsonPath("\$.pastReactions.length()").value(2))
            .andExpect(jsonPath("\$.pastReactions[0].date").value("2026-07-16"))
            .andExpect(jsonPath("\$.pastReactions[0].pct").value(27.8))
            .andExpect(jsonPath("\$.signals[0]").value("Odwrócenie trendu"))
            .andExpect(jsonPath("\$.signals[1]").value("Regularne wzrosty po raportach"))
            .andExpect(jsonPath("\$.consensus.length()").value(2))
            .andExpect(jsonPath("\$.consensus[0].label").value("Sprzedaż vs konsensus FactSet (6 analityków)"))
            .andExpect(jsonPath("\$.consensus[0].actual").value(215))
            .andExpect(jsonPath("\$.consensus[0].consensus").value(216))
            .andExpect(jsonPath("\$.consensus[0].currency").value("SEK"))
            .andExpect(jsonPath("\$.consensus[1].label").value("Zysk operacyjny vs konsensus FactSet (1 analityka)"))
            .andExpect(jsonPath("\$.consensus[1].differencePct").value(200.0 / 58))
            .andExpect(jsonPath("\$.components[4].label").value("Zapowiedzi i zamówienia"))
    }

    @Test
    fun `GPW detail without context keeps the number and empty new fields`() {
        val id = report(Market.GPW, 1)
        score(id, 60.0, emptySet())

        mvc.perform(authorized("/api/alerts/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("\$.reportNumber").value("1/2026"))
            .andExpect(jsonPath("\$.listing").doesNotExist())
            .andExpect(jsonPath("\$.ytdChangePct").doesNotExist())
            .andExpect(jsonPath("\$.pastReactions.length()").value(0))
            .andExpect(jsonPath("\$.signals.length()").value(0))
    }

    @Test
    fun `effectiveness per signal counts reports with outcomes regardless of score`() {
        val low = report(Market.SE, 40)
        score(low, 20.0, setOf(Signal.TREND_REVERSAL, Signal.ORDERS_UP))
        outcome(low, r5 = 5.0, i5 = 1.0)
        val high = report(Market.GPW, 30)
        score(high, 80.0, setOf(Signal.TREND_REVERSAL))
        outcome(high, r5 = -2.0, i5 = 0.0)
        val withoutOutcome = report(Market.GPW, 3)
        score(withoutOutcome, 75.0, setOf(Signal.TREND_REVERSAL))

        mvc.perform(authorized("/api/effectiveness"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("\$.buckets.length()").value(8))
            .andExpect(jsonPath("\$.bySignal.length()").value(4))
            .andExpect(jsonPath("\$.bySignal[0].signal").value("TREND_REVERSAL"))
            .andExpect(jsonPath("\$.bySignal[0].label").value("Odwrócenie trendu"))
            .andExpect(jsonPath("\$.bySignal[0].alerts").value(2))
            .andExpect(jsonPath("\$.bySignal[0].horizons[0].sessions").value(5))
            .andExpect(jsonPath("\$.bySignal[0].horizons[0].count").value(2))
            .andExpect(jsonPath("\$.bySignal[0].horizons[0].avgExcessPct").value(1.0))
            .andExpect(jsonPath("\$.bySignal[1].signal").value("ORDERS_UP"))
            .andExpect(jsonPath("\$.bySignal[1].alerts").value(1))
            .andExpect(jsonPath("\$.bySignal[1].horizons[0].avgExcessPct").value(4.0))
            .andExpect(jsonPath("\$.bySignal[2].alerts").value(0))
            .andExpect(jsonPath("\$.bySignal[3].signal").value("CONSISTENT_REACTION"))
    }

    @Test
    fun `effectiveness skips reports published before the counting start`() {
        val beforeStart = report(Market.SE, 12)
        score(beforeStart, 80.0, setOf(Signal.TREND_REVERSAL))
        outcome(beforeStart, r5 = 5.0, i5 = 1.0)
        val afterStart = report(Market.SE, 8)
        score(afterStart, 80.0, setOf(Signal.TREND_REVERSAL))
        outcome(afterStart, r5 = -2.0, i5 = 0.0)
        val start = now.minus(10, ChronoUnit.DAYS)

        assertEquals(listOf(afterStart), alerts.scoredOutcomes(start).map { it.id })
        assertEquals(listOf(-2.0), alerts.signalOutcomes(start).map { it.outcome.return5Pct })
    }

    @Test
    fun `unknown signal and unreadable context do not break the detail`() {
        val id = report(Market.SE, 1)
        score(id, 50.0, setOf(Signal.ORDERS_UP))
        jdbc.sql("UPDATE score SET signals = ARRAY['ORDERS_UP', 'RETIRED_SIGNAL'], context = '{\"ytdChangePct\": \"oops\"}'::jsonb WHERE report_id = :id")
            .param("id", id)
            .update()

        mvc.perform(authorized("/api/alerts/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("\$.signals.length()").value(1))
            .andExpect(jsonPath("\$.signals[0]").value("Rosnące zamówienia"))
            .andExpect(jsonPath("\$.pastReactions.length()").value(0))
    }

    @Test
    fun `stored score keeps signals and context`() {
        val id = report(Market.SE, 1)
        val context = ScoreContext(-10.0, 30.0, listOf(PastReaction(LocalDate.parse("2026-07-16"), 2.5)), emptyList())
        score(id, 50.0, setOf(Signal.ORDERS_UP), context)

        val stored = checkNotNull(scores.find(id)).score

        assertEquals(setOf(Signal.ORDERS_UP), stored.signals)
        assertEquals(context, stored.context)
    }
}
