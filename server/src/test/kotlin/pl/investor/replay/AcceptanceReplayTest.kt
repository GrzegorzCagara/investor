package pl.investor.replay

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import pl.investor.extraction.ExtractionRepository
import pl.investor.extraction.OutlookTone
import pl.investor.pipeline.CompanyDataRefresher
import pl.investor.pipeline.MonthlySalesBackfillJob
import pl.investor.pipeline.PendingReportsJob
import pl.investor.pipeline.ReportPipeline
import pl.investor.report.CompanyRepository
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import pl.investor.scoring.ScoreRepository
import pl.investor.scoring.ScoringInputAssembler
import pl.investor.scoring.Warning
import pl.investor.settings.SettingRepository
import pl.investor.source.gpw.ListingEntry
import pl.investor.source.quotes.DailyQuotesJob
import pl.investor.support.PostgresTestBase
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Import(ReplayConfig::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AcceptanceReplayTest : PostgresTestBase() {

    companion object {
        private val anthropic = AnthropicStub()
        private val FIVE_MINUTES: Duration = Duration.ofMinutes(5)
        private val DETECTION_DELAY: Duration = Duration.ofMinutes(3)

        @JvmStatic
        @DynamicPropertySource
        fun anthropicUrl(registry: DynamicPropertyRegistry) {
            registry.add("investor.anthropic.base-url") { anthropic.baseUrl }
        }
    }

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var clock: MutableClock
    @Autowired lateinit var http: FixtureHttpClient
    @Autowired lateinit var notifier: RecordingPushNotifier
    @Autowired lateinit var pipeline: ReportPipeline
    @Autowired lateinit var pendingJob: PendingReportsJob
    @Autowired lateinit var backfill: MonthlySalesBackfillJob
    @Autowired lateinit var quotesJob: DailyQuotesJob
    @Autowired lateinit var refresher: CompanyDataRefresher
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var reports: ReportRepository
    @Autowired lateinit var scores: ScoreRepository
    @Autowired lateinit var extractions: ExtractionRepository
    @Autowired lateinit var assembler: ScoringInputAssembler
    @Autowired lateinit var settings: SettingRepository

    private lateinit var harness: ReplayHarness
    private val dumps = mutableMapOf<String, String>()

    @BeforeAll
    fun prepare() {
        harness = ReplayHarness(
            jdbc, clock, http, anthropic, notifier, pipeline, pendingJob, backfill, quotesJob, refresher,
            companies, reports, scores, extractions, assembler, settings,
        )
        harness.reset()
        harness.loadQuotes()
        harness.prepareAsInstalled("ASBIS")
    }

    @AfterAll
    fun writeDump() {
        listOf(asbisRb23, asbisHalfYear, asbisJulySales, xtbPreliminary, creepyJarPreliminary)
        val target = Paths.get("build", "replay-results.txt")
        Files.createDirectories(target.parent)
        val notCaptured = harness.notCapturedRequests().joinToString("\n")
        Files.writeString(target, dumps.entries.sortedBy { it.key }.joinToString("\n\n") { it.value } + "\n\nNOT CAPTURED:\n$notCaptured\n")
    }

    private fun replayOf(name: String, entry: ListingEntry): ReplayResult {
        val result = harness.replay(entry, entry.publishedAt.plus(DETECTION_DELAY))
        dumps[name] = describe(name, entry, result)
        return result
    }

    private fun describe(name: String, entry: ListingEntry, r: ReplayResult) = """
        CASE $name
        published=${entry.publishedAt} kind=${entry.kind} title=${entry.title}
        status=${r.status} elapsed=${r.elapsed} pushes=${r.pushes.map { it.title + " | " + it.body }}
        total=${r.score?.total} components=${r.score?.components} penalty=${r.score?.penalty} warnings=${r.score?.warnings}
        input=${r.input}
        extraction=${r.extraction}
        reason=${r.reason}
        servedFixtures=${harness.servedFixtures().takeLast(2)}
    """.trimIndent()

    private val asbisRb23 by lazy { replayOf("1 RB 23", harness.listingEntry("listing-2026-09-21-p1.html", "497297")) }
    private val asbisHalfYear by lazy { replayOf("2 H1", harness.listingEntry("listing-2026-08-06-p2.html", "494951")) }
    private val asbisJulySales by lazy { replayOf("3 July sales", harness.listingEntry("listing-2026-08-21-p1.html", "495649")) }
    private val xtbPreliminary by lazy { replayOf("4 XTB", harness.listingEntry("listing-2026-07-29-p1.html", "494560")) }
    private val creepyJarPreliminary by lazy { replayOf("5 CRJ", harness.listingEntry("listing-2026-05-12-p1.html", "488521")) }

    private fun ReplayResult.total() = checkNotNull(score) { "no score, status $status" }.total

    private fun ReplayResult.scoringInput() = checkNotNull(input) { "no scoring input, status $status" }

    private fun ReplayResult.assertPushedWithinFiveMinutes() {
        assertEquals(1, pushes.size, "no push, status $status")
        assertTrue(elapsed < FIVE_MINUTES, "push after $elapsed")
    }

    @Test
    fun `1 ASBIS RB 23 2026 extracts plus 91 percent year on year`() {
        assertEquals(91.0, asbisRb23.scoringInput().revenueYoyPct!!, 1.0)
    }

    @Test
    fun `1 ASBIS RB 23 2026 scores at least 70`() {
        assertTrue(asbisRb23.total() >= 70)
    }

    @Test
    fun `1 ASBIS RB 23 2026 is pushed once`() {
        assertEquals(1, asbisRb23.pushes.size)
    }

    @Test
    fun `1 ASBIS RB 23 2026 is pushed within five minutes`() {
        asbisRb23.assertPushedWithinFiveMinutes()
    }

    @Test
    fun `2 ASBIS half-year 2026 scores at least 70`() {
        assertTrue(asbisHalfYear.total() >= 70)
    }

    @Test
    fun `2 ASBIS half-year 2026 is pushed once`() {
        assertEquals(1, asbisHalfYear.pushes.size)
    }

    @Disabled("see faza-1-wyniki-odtworzen.md")
    @Test
    fun `2 ASBIS half-year 2026 has a positive outlook`() {
        assertEquals(OutlookTone.POSITIVE, asbisHalfYear.scoringInput().outlook)
    }

    @Test
    fun `2 ASBIS half-year 2026 is pushed within five minutes`() {
        asbisHalfYear.assertPushedWithinFiveMinutes()
    }

    @Disabled("see faza-1-wyniki-odtworzen.md")
    @Test
    fun `3 ASBIS July sales scores at least 70`() {
        assertTrue(asbisJulySales.total() >= 70)
    }

    @Disabled("see faza-1-wyniki-odtworzen.md")
    @Test
    fun `3 ASBIS July sales is pushed once`() {
        assertEquals(1, asbisJulySales.pushes.size)
    }

    @Test
    fun `3 ASBIS July sales follows at least three strong months`() {
        assertTrue(asbisJulySales.scoringInput().consecutiveStrongBefore >= 3)
    }

    @Disabled("see faza-1-wyniki-odtworzen.md")
    @Test
    fun `3 ASBIS July sales is pushed within five minutes`() {
        asbisJulySales.assertPushedWithinFiveMinutes()
    }

    @Test
    fun `4 XTB preliminary Q2 2026 scores at least 70`() {
        assertTrue(xtbPreliminary.total() >= 70)
    }

    @Test
    fun `4 XTB preliminary Q2 2026 is pushed once`() {
        assertEquals(1, xtbPreliminary.pushes.size)
    }

    @Test
    fun `4 XTB preliminary Q2 2026 is pushed within five minutes`() {
        xtbPreliminary.assertPushedWithinFiveMinutes()
    }

    @Test
    fun `5 Creepy Jar preliminary Q1 2026 carries the one-off warning`() {
        assertTrue(Warning.ONE_OFF_QUARTER in checkNotNull(creepyJarPreliminary.score) { "no score, status ${creepyJarPreliminary.status}" }.warnings)
    }

    @Test
    fun `5 Creepy Jar preliminary Q1 2026 is scored within five minutes`() {
        assertEquals(ReportStatus.SCORED, creepyJarPreliminary.status)
        assertTrue(creepyJarPreliminary.elapsed < FIVE_MINUTES)
    }
}
