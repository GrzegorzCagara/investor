package pl.investor.api

import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import pl.investor.extraction.AiPool
import pl.investor.extraction.Amount
import pl.investor.extraction.ExtractedPeriod
import pl.investor.extraction.Extraction
import pl.investor.extraction.ExtractionRepository
import pl.investor.extraction.Outlook
import pl.investor.extraction.OutlookTone
import pl.investor.extraction.ReportCategory
import pl.investor.extraction.VerifiedExtraction
import pl.investor.history.DailyQuote
import pl.investor.history.PeriodKind
import pl.investor.history.QuarterResult
import pl.investor.history.QuarterResultRepository
import pl.investor.history.QuoteRepository
import pl.investor.history.RevenuePeriod
import pl.investor.history.RevenuePeriodRepository
import pl.investor.notification.DeviceRepository
import pl.investor.notification.PushKind
import pl.investor.notification.PushLogRepository
import pl.investor.notification.PushMessage
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
import pl.investor.scoring.Warning
import pl.investor.support.PostgresTestBase
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest : PostgresTestBase() {

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var tokenFilter: ApiTokenFilter
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var reports: ReportRepository
    @Autowired lateinit var scores: ScoreRepository
    @Autowired lateinit var pushLog: PushLogRepository
    @Autowired lateinit var devices: DeviceRepository
    @Autowired lateinit var extractions: ExtractionRepository
    @Autowired lateinit var revenuePeriods: RevenuePeriodRepository
    @Autowired lateinit var quarterResults: QuarterResultRepository
    @Autowired lateinit var quotes: QuoteRepository
    @LocalServerPort var port: Int = 0

    private lateinit var mvc: MockMvc
    private val base = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(1, ChronoUnit.DAYS)

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters<DefaultMockMvcBuilder>(tokenFilter).build()
        jdbc.sql("DELETE FROM push_log").update()
        jdbc.sql("DELETE FROM score").update()
        jdbc.sql("DELETE FROM extraction").update()
        jdbc.sql("UPDATE revenue_period SET report_id = NULL").update()
        jdbc.sql("DELETE FROM report").update()
        jdbc.sql("DELETE FROM device").update()
    }

    private fun report(name: String, publishedAt: Instant, status: ReportStatus, shortName: String? = null): Report {
        val company = companies.save(Company(null, name + UUID.randomUUID(), null, Market.GPW, null, null, false, shortName ?: name))
        val saved = reports.saveIfNew(
            Report(null, checkNotNull(company.id), Market.GPW, ReportKind.QUARTERLY, "1/2026", UUID.randomUUID().toString(), "Raport", publishedAt, publishedAt, "https://example.test/r", status),
        )
        return checkNotNull(saved)
    }

    private fun score(reportId: Long, total: Double, reason: String = "Przychody wzrosły o 91%. Drugie zdanie.") =
        scores.save(reportId, Score(total, ScoreComponents(25.0, 15.0, 10.0, 5.0, 5.0, 10.0), 2.5, setOf(Warning.NO_AI, Warning.LOW_LIQUIDITY)), reason, base)

    private fun authorized(path: String) = get(path).header("X-Investor-Token", "test-token")

    @Test
    fun `missing token gives 401`() {
        mvc.perform(get("/api/alerts")).andExpect(status().isUnauthorized).andExpect(content().string(""))
        mvc.perform(get("/api/alerts").header("X-Investor-Token", "wrong")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/devices").header("X-Investor-Token", "wrong")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `alerts list is wrapped and newest first`() {
        val older = report("Alfa", base, ReportStatus.SCORED)
        val newer = report("Beta", base.plusSeconds(600), ReportStatus.SCORED)
        val pending = report("Gamma", base.plusSeconds(300), ReportStatus.PENDING_AI)
        val hidden = report("Delta", base.plusSeconds(900), ReportStatus.IRRELEVANT)
        score(checkNotNull(older.id), 64.5)
        score(checkNotNull(newer.id), 83.0)

        mvc.perform(authorized("/api/alerts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("\$.items.length()").value(3))
            .andExpect(jsonPath("\$.items[0].id").value(newer.id))
            .andExpect(jsonPath("\$.items[0].companyName").value("Beta"))
            .andExpect(jsonPath("\$.items[0].market").value("GPW"))
            .andExpect(jsonPath("\$.items[0].eventType").value("RESULTS"))
            .andExpect(jsonPath("\$.items[0].score").value(83))
            .andExpect(jsonPath("\$.items[0].belowThreshold").value(false))
            .andExpect(jsonPath("\$.items[0].summary").value("Przychody wzrosły o 91%."))
            .andExpect(jsonPath("\$.items[0].publishedAt").value(base.plusSeconds(600).toString()))
            .andExpect(jsonPath("\$.items[1].id").value(pending.id))
            .andExpect(jsonPath("\$.items[1].score").doesNotExist())
            .andExpect(jsonPath("\$.items[1].summary").value("Czekam na AI"))
            .andExpect(jsonPath("\$.items[2].id").value(older.id))
            .andExpect(jsonPath("\$.items[2].score").value(65))
            .andExpect(jsonPath("\$.items[2].belowThreshold").value(true))
        mvc.perform(authorized("/api/alerts?limit=1")).andExpect(jsonPath("\$.items.length()").value(1))
        assertEquals(ReportStatus.IRRELEVANT, reports.findById(checkNotNull(hidden.id)).status)
    }

    @Test
    fun `unscored reports carry a status summary`() {
        report("Alfa", base, ReportStatus.UNSCORED_NO_AI)
        report("Beta", base.plusSeconds(10), ReportStatus.UNSCORED_NO_DATA)

        mvc.perform(authorized("/api/alerts"))
            .andExpect(jsonPath("\$.items[0].summary").value("Bez oceny: brak danych finansowych"))
            .andExpect(jsonPath("\$.items[1].summary").value("Bez oceny: wyczerpany limit AI"))
            .andExpect(jsonPath("\$.items[1].belowThreshold").value(false))
    }

    @Test
    fun `alert detail has six components in the fixed order with Polish labels`() {
        val report = report("Alfa", base, ReportStatus.SCORED)
        score(checkNotNull(report.id), 72.5)

        mvc.perform(authorized("/api/alerts/${report.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("\$.score").value(73))
            .andExpect(jsonPath("\$.reportNumber").value("1/2026"))
            .andExpect(jsonPath("\$.reportKind").value("QUARTERLY"))
            .andExpect(jsonPath("\$.components.length()").value(6))
            .andExpect(jsonPath("\$.components[0].label").value("Niespodzianka"))
            .andExpect(jsonPath("\$.components[0].points").value(25.0))
            .andExpect(jsonPath("\$.components[0].max").value(30.0))
            .andExpect(jsonPath("\$.components[1].label").value("Wzrost przychodów"))
            .andExpect(jsonPath("\$.components[2].label").value("Jakość: zysk i marża"))
            .andExpect(jsonPath("\$.components[3].label").value("Seria mocnych raportów"))
            .andExpect(jsonPath("\$.components[4].label").value("Zapowiedzi zarządu"))
            .andExpect(jsonPath("\$.components[5].label").value("Wycena („czy już w cenie”)"))
            .andExpect(jsonPath("\$.components[5].max").value(15.0))
            .andExpect(jsonPath("\$.penalty").value(2.5))
            .andExpect(jsonPath("\$.warnings.length()").value(2))
            .andExpect(jsonPath("\$.warnings[?(@ == 'Ocena bez AI')]").exists())
            .andExpect(jsonPath("\$.warnings[?(@ == 'Niska płynność')]").exists())
            .andExpect(jsonPath("\$.reason").value("Przychody wzrosły o 91%. Drugie zdanie."))
            .andExpect(jsonPath("\$.revenueHistory.length()").value(0))
            .andExpect(jsonPath("\$.sourceUrl").value("https://example.test/r"))
    }

    @Test
    fun `unknown or unlisted alert gives 404`() {
        val hidden = report("Alfa", base, ReportStatus.IRRELEVANT)

        mvc.perform(authorized("/api/alerts/999999")).andExpect(status().isNotFound)
        mvc.perform(authorized("/api/alerts/${hidden.id}")).andExpect(status().isNotFound)
    }

    @Test
    fun `device registration returns 204 and stores the token`() {
        mvc.perform(
            post("/api/devices").header("X-Investor-Token", "test-token").contentType("application/json").content("""{"token":"fcm-1"}"""),
        ).andExpect(status().isNoContent)

        assertEquals(listOf("fcm-1"), devices.tokens())
    }

    @Test
    fun `status reports median latency`() {
        listOf(120L, 300L).forEachIndexed { index, latency ->
            val published = base.plusSeconds(index * 1000L)
            val report = report("Firma$index", published, ReportStatus.SCORED)
            pushLog.save(PushMessage("t", "b", emptyMap(), PushKind.RESULTS, report.id), published.plusSeconds(latency))
        }

        mvc.perform(authorized("/api/status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("\$.medianLatencySeconds7d").value(210))
            .andExpect(jsonPath("\$.aiSpentUsd.${AiPool.GPW.name}").exists())
            .andExpect(jsonPath("\$.aiSpentUsd.${AiPool.US.name}").exists())
            .andExpect(jsonPath("\$.aiLimitUsd.${AiPool.GPW.name}").value(1.90))
            .andExpect(jsonPath("\$.aiLimitUsd.${AiPool.US.name}").value(2.85))
            .andExpect(jsonPath("\$.sources").isArray)
            .andExpect(jsonPath("\$.fcmConfigured").value(false))
    }

    @Test
    fun `status reports the newest WIG quote date`() {
        jdbc.sql("DELETE FROM daily_quote WHERE instrument = 'WIG'").update()
        mvc.perform(authorized("/api/status")).andExpect(jsonPath("\$.latestQuoteDate").value(nullValue()))

        listOf(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 29)).forEach { quotes.save(DailyQuote("WIG", it, BigDecimal.TEN, 1, null)) }
        quotes.save(DailyQuote("NCINDEX", LocalDate.of(2026, 10, 30), BigDecimal.TEN, 1, null))

        mvc.perform(authorized("/api/status")).andExpect(jsonPath("\$.latestQuoteDate").value("2026-10-29"))
    }

    private fun extraction(reportId: Long, kind: PeriodKind, tone: OutlookTone = OutlookTone.NONE, quote: String? = null) {
        val period = ExtractedPeriod(kind, LocalDate.of(2026, 6, 30), "PLN", Amount(BigDecimal.TEN, "10"), null, null, null, null, null)
        val extraction = Extraction(ReportCategory.RESULTS, listOf(period), Outlook(tone, quote), false, null, false)
        extractions.save(reportId, VerifiedExtraction(extraction, emptySet()))
    }

    private fun rawStatus(path: String, method: String = "GET"): Int =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            .statusCode()

    @Test
    fun `encoded and parameterized paths cannot skip the token check`() {
        listOf("/%61pi/alerts", "/api;x=1/alerts", "/%61pi/admin/backfill").forEach { path ->
            mvc.perform(get(path)).andExpect(status().isUnauthorized)
            val raw = rawStatus(path)
            assertTrue(raw != 200, "$path answered $raw")
        }
        listOf("/%61pi/admin/backfill", "/api;x=1/admin/backfill").forEach { path ->
            val raw = rawStatus(path, "POST")
            assertTrue(raw !in BACKFILL_CONTROLLER_STATUSES, "$path reached the backfill controller with $raw")
        }
        mvc.perform(post("/%61pi/admin/backfill")).andExpect(status().isUnauthorized)
        mvc.perform(get("/actuator/health")).andExpect(status().isOk)
        assertEquals(200, rawStatus("/actuator/health"))
    }

    @Test
    fun `belowThreshold uses the raw total while the score is rounded`() {
        val report = report("Alfa", base, ReportStatus.SCORED)
        score(checkNotNull(report.id), 69.6)

        mvc.perform(authorized("/api/alerts"))
            .andExpect(jsonPath("\$.items[0].score").value(70))
            .andExpect(jsonPath("\$.items[0].belowThreshold").value(true))
    }

    @Test
    fun `limit is capped at 500`() {
        val company = companies.save(Company(null, "Masowa" + UUID.randomUUID(), null, Market.GPW, null, null, false, "Masowa"))
        jdbc.sql(
            """
            INSERT INTO report (company_id, market, kind, number, source_ref, published_at, detected_at, detail_url, status, status_changed_at)
            SELECT :companyId, 'GPW', 'QUARTERLY', 'n', 'bulk-' || g, now(), now(), 'u', 'PENDING_AI', now() FROM generate_series(1, 501) g
            """
        ).param("companyId", company.id).update()

        mvc.perform(authorized("/api/alerts?limit=100000")).andExpect(jsonPath("\$.items.length()").value(500))
        mvc.perform(authorized("/api/alerts?limit=0")).andExpect(jsonPath("\$.items.length()").value(1))
        mvc.perform(authorized("/api/alerts")).andExpect(jsonPath("\$.items.length()").value(100))
    }

    @Test
    fun `monthly revenue history has the eight newest months oldest first`() {
        val report = report("Alfa", base, ReportStatus.SCORED)
        score(checkNotNull(report.id), 80.0)
        extraction(checkNotNull(report.id), PeriodKind.MONTH)
        (0L until 10L).forEach { i ->
            val end = LocalDate.of(2026, 1, 31).plusMonths(i).let { it.withDayOfMonth(it.lengthOfMonth()) }
            revenuePeriods.save(RevenuePeriod(report.companyId, PeriodKind.MONTH, end, "PLN", BigDecimal(100 + i), null, base.minusSeconds(100 - i), null))
        }

        mvc.perform(authorized("/api/alerts/${report.id}"))
            .andExpect(jsonPath("\$.revenueHistory.length()").value(8))
            .andExpect(jsonPath("\$.revenueHistory[0].label").value("03.2026"))
            .andExpect(jsonPath("\$.revenueHistory[0].value").value(102.0))
            .andExpect(jsonPath("\$.revenueHistory[0].currency").value("PLN"))
            .andExpect(jsonPath("\$.revenueHistory[7].label").value("10.2026"))
    }

    @Test
    fun `quarterly revenue history has the eight newest quarters oldest first`() {
        val report = report("Alfa", base, ReportStatus.SCORED)
        score(checkNotNull(report.id), 80.0)
        extraction(checkNotNull(report.id), PeriodKind.QUARTER)
        (0L until 10L).forEach { i ->
            val end = LocalDate.of(2024, 3, 31).plusMonths(3 * i).let { it.withDayOfMonth(it.lengthOfMonth()) }
            quarterResults.save(QuarterResult(report.companyId, end, BigDecimal(1000 + i), null, null, end.plusDays(45)))
        }

        mvc.perform(authorized("/api/alerts/${report.id}"))
            .andExpect(jsonPath("\$.revenueHistory.length()").value(8))
            .andExpect(jsonPath("\$.revenueHistory[0].label").value("2024 Q3"))
            .andExpect(jsonPath("\$.revenueHistory[0].value").value(1002.0))
            .andExpect(jsonPath("\$.revenueHistory[0].currency").value("PLN"))
            .andExpect(jsonPath("\$.revenueHistory[7].label").value("2026 Q2"))
    }

    @Test
    fun `outlook quote is present only when the tone is not NONE`() {
        val withTone = report("Alfa", base, ReportStatus.SCORED)
        val withoutTone = report("Beta", base.plusSeconds(10), ReportStatus.SCORED)
        score(checkNotNull(withTone.id), 80.0)
        score(checkNotNull(withoutTone.id), 80.0)
        extraction(checkNotNull(withTone.id), PeriodKind.QUARTER, OutlookTone.POSITIVE, "Spodziewamy się wzrostu")
        extraction(checkNotNull(withoutTone.id), PeriodKind.QUARTER, OutlookTone.NONE, "stary cytat")

        mvc.perform(authorized("/api/alerts/${withTone.id}")).andExpect(jsonPath("\$.outlookQuote").value("Spodziewamy się wzrostu"))
        mvc.perform(authorized("/api/alerts/${withoutTone.id}")).andExpect(jsonPath("\$.outlookQuote").doesNotExist())
    }

    private companion object {
        val BACKFILL_CONTROLLER_STATUSES = setOf(202, 409)
    }
}
