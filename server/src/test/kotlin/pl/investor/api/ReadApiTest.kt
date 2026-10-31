package pl.investor.api

import org.hamcrest.Matchers.nullValue
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
import pl.investor.scoring.Score
import pl.investor.scoring.ScoreComponents
import pl.investor.scoring.ScoreRepository
import pl.investor.source.consensus.ConsensusNote
import pl.investor.source.consensus.ConsensusRepository
import pl.investor.source.consensus.ConsensusRow
import pl.investor.support.PostgresTestBase
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadApiTest : PostgresTestBase() {

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var tokenFilter: ApiTokenFilter
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var reports: ReportRepository
    @Autowired lateinit var scores: ScoreRepository
    @Autowired lateinit var outcomes: OutcomeRepository
    @Autowired lateinit var consensus: ConsensusRepository

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

    private fun company(name: String, market: Market = Market.GPW): Long =
        checkNotNull(companies.save(Company(null, name + UUID.randomUUID(), null, market, null, null, false, name)).id)

    private fun report(companyId: Long, daysAgo: Long, status: ReportStatus = ReportStatus.SCORED, total: Double? = null, market: Market = Market.GPW): Long {
        val publishedAt = now.minus(daysAgo, ChronoUnit.DAYS)
        val id = checkNotNull(
            reports.saveIfNew(
                Report(null, companyId, market, ReportKind.QUARTERLY, "1/2026", UUID.randomUUID().toString(), "Raport", publishedAt, publishedAt, "https://example.test/r", status),
            )?.id,
        )
        total?.let { scores.save(id, Score(it, ScoreComponents(10.0, 10.0, 10.0, 10.0, 10.0, 10.0), 0.0, emptySet()), "Powód.", publishedAt) }
        return id
    }

    private fun outcome(
        reportId: Long,
        baseDate: LocalDate = LocalDate.now().minusDays(30),
        reaction: Double? = null,
        reactionIndex: Double? = null,
        volume: Double? = null,
        r5: Double? = null,
        i5: Double? = null,
    ) = outcomes.save(reportId, Outcome(baseDate, reaction, reactionIndex, volume, r5, i5, null, null, null, null), now)

    private fun authorized(path: String) = get(path).header("X-Investor-Token", "test-token")

    @Test
    fun `new endpoints require the token`() {
        listOf("/api/ranking", "/api/effectiveness").forEach { path ->
            mvc.perform(get(path)).andExpect(status().isUnauthorized)
            mvc.perform(get(path).header("X-Investor-Token", "wrong")).andExpect(status().isUnauthorized)
        }
    }

    @Test
    fun `ranking keeps the latest scored result per company from the last 30 days, best first`() {
        val alfa = company("Alfa")
        report(alfa, 20, total = 90.0)
        val alfaLatest = report(alfa, 2, total = 60.0)
        report(alfa, 1, ReportStatus.PENDING_AI)
        val beta = company("Beta", Market.NC)
        val betaLatest = report(beta, 5, total = 75.0, market = Market.NC)
        report(company("Gamma"), 40, total = 95.0)
        report(company("Delta"), 1, ReportStatus.UNSCORED_NO_DATA)
        val epsilonLatest = report(company("Epsilon"), 3, ReportStatus.PUSH_PENDING, total = 75.0)

        mvc.perform(authorized("/api/ranking"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("\$.items.length()").value(3))
            .andExpect(jsonPath("\$.items[0].id").value(epsilonLatest))
            .andExpect(jsonPath("\$.items[0].score").value(75))
            .andExpect(jsonPath("\$.items[0].belowThreshold").value(false))
            .andExpect(jsonPath("\$.items[1].id").value(betaLatest))
            .andExpect(jsonPath("\$.items[1].market").value("NC"))
            .andExpect(jsonPath("\$.items[2].id").value(alfaLatest))
            .andExpect(jsonPath("\$.items[2].companyName").value("Alfa"))
            .andExpect(jsonPath("\$.items[2].score").value(60))
            .andExpect(jsonPath("\$.items[2].belowThreshold").value(true))
            .andExpect(jsonPath("\$.items[2].eventType").value("RESULTS"))
            .andExpect(jsonPath("\$.items[2].summary").value("Powód."))
            .andExpect(jsonPath("\$.items[2].status").value("SCORED"))

        mvc.perform(authorized("/api/ranking?market=NC"))
            .andExpect(jsonPath("\$.items.length()").value(1))
            .andExpect(jsonPath("\$.items[0].id").value(betaLatest))
        mvc.perform(authorized("/api/ranking?market=US")).andExpect(jsonPath("\$.items.length()").value(0))
        mvc.perform(authorized("/api/ranking?market=XX")).andExpect(status().isBadRequest)
    }

    @Test
    fun `ranking orders by the displayed score and then newest first`() {
        val older = report(company("Alfa"), 20, total = 82.4)
        val newer = report(company("Beta"), 1, total = 81.6)

        mvc.perform(authorized("/api/ranking"))
            .andExpect(jsonPath("\$.items.length()").value(2))
            .andExpect(jsonPath("\$.items[0].id").value(newer))
            .andExpect(jsonPath("\$.items[1].id").value(older))
    }

    @Test
    fun `effectiveness includes a total of 49,5 and excludes 49,4`() {
        val included = report(company("Alfa"), 3, total = 49.5)
        report(company("Beta"), 2, total = 49.4)

        mvc.perform(authorized("/api/effectiveness"))
            .andExpect(jsonPath("\$.buckets[0].alerts").value(1))
            .andExpect(jsonPath("\$.items.length()").value(1))
            .andExpect(jsonPath("\$.items[0].id").value(included))
            .andExpect(jsonPath("\$.items[0].score").value(50))
    }

    @Test
    fun `report has an index on company and publication time`() {
        val count = jdbc.sql("SELECT count(*) FROM pg_indexes WHERE tablename = 'report' AND indexname = 'report_company_published_idx'")
            .query(Long::class.java)
            .single()
        assertEquals(1L, count)
    }

    @Test
    fun `US detail has no index name and no reaction without an outcome`() {
        val us = company("Gamma", Market.US)
        val withOutcome = report(us, 3, total = 70.0, market = Market.US)
        outcome(withOutcome, reaction = 2.0, reactionIndex = null)
        val withoutOutcome = report(us, 1, total = 70.0, market = Market.US)

        mvc.perform(authorized("/api/alerts/$withOutcome"))
            .andExpect(jsonPath("\$.reaction.stockPct").value(2.0))
            .andExpect(jsonPath("\$.reaction.indexName").value(nullValue()))
        mvc.perform(authorized("/api/alerts/$withoutOutcome"))
            .andExpect(jsonPath("\$.reaction").value(nullValue()))
    }

    @Test
    fun `history excludes a report published at the same moment`() {
        val alfa = company("Alfa")
        val earlier = report(alfa, 9, total = 60.0)
        report(alfa, 4, total = 65.0)
        val current = report(alfa, 4, total = 70.0)

        mvc.perform(authorized("/api/alerts/$current"))
            .andExpect(jsonPath("\$.companyHistory.length()").value(1))
            .andExpect(jsonPath("\$.companyHistory[0].id").value(earlier))
    }

    @Test
    fun `effectiveness has every bucket and newest items first`() {
        val alfa = company("Alfa")
        val older = report(alfa, 30, total = 82.0)
        outcome(older, reaction = 0.2, reactionIndex = 0.0, r5 = 12.0, i5 = 2.0)
        val newer = report(company("Beta"), 10, total = 64.6)
        outcome(newer, reaction = -3.0, reactionIndex = 1.0, r5 = -4.0, i5 = 1.0)
        report(company("Gamma"), 5, total = 49.0)
        val unknown = report(company("Delta"), 2, total = 55.0)

        mvc.perform(authorized("/api/effectiveness"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("\$.buckets.length()").value(4))
            .andExpect(jsonPath("\$.buckets[0].eventType").value("RESULTS"))
            .andExpect(jsonPath("\$.buckets[0].label").value("50–59"))
            .andExpect(jsonPath("\$.buckets[0].alerts").value(1))
            .andExpect(jsonPath("\$.buckets[0].horizons[0].sessions").value(5))
            .andExpect(jsonPath("\$.buckets[0].horizons[0].count").value(0))
            .andExpect(jsonPath("\$.buckets[0].horizons[0].avgExcessPct").doesNotExist())
            .andExpect(jsonPath("\$.buckets[1].label").value("60–69"))
            .andExpect(jsonPath("\$.buckets[1].horizons[0].count").value(1))
            .andExpect(jsonPath("\$.buckets[1].horizons[0].avgExcessPct").value(-5.0))
            .andExpect(jsonPath("\$.buckets[2].label").value("70–79"))
            .andExpect(jsonPath("\$.buckets[2].alerts").value(0))
            .andExpect(jsonPath("\$.buckets[3].label").value("80+"))
            .andExpect(jsonPath("\$.buckets[3].horizons[0].avgExcessPct").value(10.0))
            .andExpect(jsonPath("\$.buckets[3].horizons[1].sessions").value(20))
            .andExpect(jsonPath("\$.buckets[3].horizons[2].sessions").value(60))
            .andExpect(jsonPath("\$.byReaction.length()").value(3))
            .andExpect(jsonPath("\$.byReaction[0].reaction").value("NEGATIVE"))
            .andExpect(jsonPath("\$.byReaction[0].alerts").value(1))
            .andExpect(jsonPath("\$.byReaction[1].reaction").value("FLAT"))
            .andExpect(jsonPath("\$.byReaction[1].alerts").value(1))
            .andExpect(jsonPath("\$.byReaction[2].reaction").value("POSITIVE"))
            .andExpect(jsonPath("\$.byReaction[2].alerts").value(0))
            .andExpect(jsonPath("\$.items.length()").value(3))
            .andExpect(jsonPath("\$.items[0].id").value(unknown))
            .andExpect(jsonPath("\$.items[0].score").value(55))
            .andExpect(jsonPath("\$.items[0].reactionPct").doesNotExist())
            .andExpect(jsonPath("\$.items[1].id").value(newer))
            .andExpect(jsonPath("\$.items[1].companyName").value("Beta"))
            .andExpect(jsonPath("\$.items[1].market").value("GPW"))
            .andExpect(jsonPath("\$.items[1].eventType").value("RESULTS"))
            .andExpect(jsonPath("\$.items[1].score").value(65))
            .andExpect(jsonPath("\$.items[1].reactionPct").value(-3.0))
            .andExpect(jsonPath("\$.items[1].reactionIndexPct").value(1.0))
            .andExpect(jsonPath("\$.items[1].excess5Pct").value(-5.0))
            .andExpect(jsonPath("\$.items[1].excess20Pct").doesNotExist())
            .andExpect(jsonPath("\$.items[2].id").value(older))
    }

    @Test
    fun `detail carries the reaction, consensus rows and the company history`() {
        val alfa = company("Alfa")
        val first = report(alfa, 40, total = 82.0)
        outcome(first, r5 = 12.0, i5 = 2.0)
        val second = report(alfa, 20, total = 40.0)
        report(alfa, 15, ReportStatus.UNSCORED_NO_AI)
        val current = report(alfa, 10, total = 70.0)
        outcome(current, reaction = 0.4, reactionIndex = 1.0, volume = 2.5)
        consensus.save(
            current,
            ConsensusNote(
                "https://example.test/konsensus",
                now,
                listOf(ConsensusRow("Przychody", BigDecimal("1696.7"), BigDecimal("1686.3"), 0.6), ConsensusRow("EBIT", BigDecimal("52.1"), BigDecimal("48.0"), 8.5)),
            ),
        )
        report(alfa, 5, total = 90.0)
        report(company("Beta"), 30, total = 85.0)

        mvc.perform(authorized("/api/alerts/$current"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("\$.score").value(70))
            .andExpect(jsonPath("\$.reaction.stockPct").value(0.4))
            .andExpect(jsonPath("\$.reaction.indexPct").value(1.0))
            .andExpect(jsonPath("\$.reaction.indexName").value("WIG"))
            .andExpect(jsonPath("\$.reaction.volumeMultiple").value(2.5))
            .andExpect(jsonPath("\$.reaction.category").value("FLAT"))
            .andExpect(jsonPath("\$.consensus.length()").value(2))
            .andExpect(jsonPath("\$.consensus[0].label").value("Przychody"))
            .andExpect(jsonPath("\$.consensus[0].actual").value(1696.7))
            .andExpect(jsonPath("\$.consensus[0].consensus").value(1686.3))
            .andExpect(jsonPath("\$.consensus[0].differencePct").value(0.6))
            .andExpect(jsonPath("\$.consensus[1].label").value("EBIT"))
            .andExpect(jsonPath("\$.companyHistory.length()").value(2))
            .andExpect(jsonPath("\$.companyHistory[0].id").value(second))
            .andExpect(jsonPath("\$.companyHistory[0].score").value(40))
            .andExpect(jsonPath("\$.companyHistory[0].excess5Pct").doesNotExist())
            .andExpect(jsonPath("\$.companyHistory[1].id").value(first))
            .andExpect(jsonPath("\$.companyHistory[1].score").value(82))
            .andExpect(jsonPath("\$.companyHistory[1].companyName").value("Alfa"))
            .andExpect(jsonPath("\$.companyHistory[1].excess5Pct").value(10.0))
    }

    @Test
    fun `detail history is capped at five and reaction waits for the session close`() {
        val alfa = company("Alfa")
        val history = (20L downTo 14L).map { report(alfa, it, total = 60.0) }
        val current = report(alfa, 1, total = 70.0)
        outcome(current, baseDate = LocalDate.now().plusDays(3), reaction = 2.0, reactionIndex = 0.0)
        val ncReport = report(company("Beta", Market.NC), 1, total = 70.0, market = Market.NC)
        outcome(ncReport, reaction = 2.0)

        mvc.perform(authorized("/api/alerts/$current"))
            .andExpect(jsonPath("\$.reaction").doesNotExist())
            .andExpect(jsonPath("\$.consensus.length()").value(0))
            .andExpect(jsonPath("\$.companyHistory.length()").value(5))
            .andExpect(jsonPath("\$.companyHistory[0].id").value(history.last()))
            .andExpect(jsonPath("\$.companyHistory[4].id").value(history[2]))
        mvc.perform(authorized("/api/alerts/$ncReport"))
            .andExpect(jsonPath("\$.reaction.indexName").value("NCINDEX"))
            .andExpect(jsonPath("\$.reaction.indexPct").doesNotExist())
            .andExpect(jsonPath("\$.reaction.category").value("POSITIVE"))
            .andExpect(jsonPath("\$.companyHistory.length()").value(0))
    }
}
