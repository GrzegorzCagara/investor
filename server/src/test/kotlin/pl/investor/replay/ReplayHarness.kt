package pl.investor.replay

import com.google.firebase.messaging.BatchResponse
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.SendResponse
import io.mockk.every
import io.mockk.mockk
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.extraction.Extraction
import pl.investor.extraction.ExtractionRepository
import pl.investor.monitoring.SourceHealthTracker
import pl.investor.notification.DeviceRepository
import pl.investor.notification.PushKind
import pl.investor.notification.PushLogRepository
import pl.investor.notification.PushMessage
import pl.investor.notification.PushNotifier
import pl.investor.pipeline.CompanyDataRefresher
import pl.investor.pipeline.MonthlySalesBackfillJob
import pl.investor.pipeline.PendingReportsJob
import pl.investor.pipeline.ReportPipeline
import pl.investor.pipeline.isMonthlySales
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.report.Report
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import pl.investor.scoring.Score
import pl.investor.scoring.ScoreRepository
import pl.investor.scoring.ScoringInput
import pl.investor.scoring.ScoringInputAssembler
import pl.investor.settings.SettingRepository
import pl.investor.source.PoliteHttpClient
import pl.investor.source.Source
import pl.investor.source.SourceUnavailableException
import pl.investor.source.gpw.EspiListingParser
import pl.investor.source.gpw.ListingEntry
import pl.investor.source.quotes.DailyQuotesJob
import pl.investor.support.toWarsawDate
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

data class ReplayResult(
    val status: ReportStatus,
    val score: Score?,
    val pushes: List<PushMessage>,
    val elapsed: Duration,
    val input: ScoringInput?,
    val reason: String?,
    val extraction: Extraction?,
)

class MutableClock(initial: Instant) : Clock() {

    @Volatile
    private var now: Instant = initial

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = fixed(now, zone)

    override fun instant(): Instant = now

    fun set(instant: Instant) {
        now = instant
    }

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}

internal object Fixtures {

    fun bytes(path: String): ByteArray? =
        Fixtures::class.java.getResourceAsStream("/fixtures/$path")?.use { it.readBytes() }

    fun text(path: String): String? = bytes(path)?.decodeToString()

    fun directory(path: String): Path =
        Paths.get(checkNotNull(Fixtures::class.java.getResource("/fixtures/$path")) { "missing fixture directory $path" }.toURI())
}

class FixtureHttpClient(clock: Clock, tracker: SourceHealthTracker) : PoliteHttpClient(clock, "replay@example.com", tracker) {

    val unmapped = CopyOnWriteArrayList<String>()
    val notCaptured = CopyOnWriteArrayList<String>()

    override fun get(source: Source, url: String): String {
        GPW_DETAIL.matchEntire(url)?.let { return gpwDetail(source, it.groupValues[1], url) }
        if (url == SYMBOLS_URL) return fixture("biznesradar/symbols-raw.js", url)
        QUARTERLY.matchEntire(url)?.let { return fixture("biznesradar/${it.groupValues[1]}-pl-q.html", url) }
        VALUATION.matchEntire(url)?.let { return fixture("biznesradar/${it.groupValues[1]}-valuation.html", url) }
        BANKIER_LIST.matchEntire(url)?.let { return bankierList(source, it.groupValues[1], url) }
        BANKIER_ARTICLE.matchEntire(url)?.let { return bankierArticle(it.groupValues[1], url) }
        unmapped(url)
    }

    override fun probe(source: Source, url: String): String? {
        val slug = BANKIER_LIST.matchEntire(url)?.groupValues?.get(1) ?: unmapped(url)
        return Fixtures.text("bankier/${slug.lowercase()}-komunikaty.html").also { if (it == null) notCaptured += url }
    }

    override fun reportFailure(source: Source, message: String) = Unit

    override fun postForm(source: Source, url: String, form: List<Pair<String, String>>): String = unmapped("$url ${form.toMap()}")

    override fun getBytes(source: Source, url: String, headers: Map<String, String>): ByteArray {
        GPW_ARCHIVE.matchEntire(url)?.let { match ->
            val (type, day, month, year) = match.destructured
            val path = if (type == "10") "quotes/gpw-$year-$month-$day.xls" else "index/gpw-indices-$year-$month-$day.xls"
            return Fixtures.bytes(path) ?: unmapped(url)
        }
        NC_DAILY.matchEntire(url)?.let { match ->
            val (year, month, day, extension) = match.destructured
            return Fixtures.bytes("quotes/nc-$year-$month-$day.$extension") ?: NON_SESSION_PAGE
        }
        ATTACHMENT.matchEntire(url)?.let { return Fixtures.bytes("attachments/${PDF_FILES[it.groupValues[1]] ?: unmapped(url)}") ?: unmapped(url) }
        unmapped(url)
    }

    private fun gpwDetail(source: Source, id: String, url: String): String {
        if (id in UNCAPTURED_REPORTS) {
            notCaptured += url
            throw SourceUnavailableException(source, IllegalStateException("no fixture for $url"))
        }
        return fixture("reports/${REPORT_FILES[id] ?: unmapped(url)}", url)
    }

    private fun fixture(path: String, url: String): String = Fixtures.text(path) ?: unmapped(url)

    private fun bankierList(source: Source, slug: String, url: String): String =
        Fixtures.text("bankier/${slug.lowercase()}-komunikaty.html") ?: run {
            notCaptured += url
            throw SourceUnavailableException(source, IllegalStateException("no fixture for $url"))
        }

    private fun bankierArticle(file: String, url: String): String {
        val path = ARTICLE_FILES[file]
        if (path == null) notCaptured += url
        return path?.let { fixture(it, url) } ?: EMPTY_ARTICLE
    }

    private fun unmapped(request: String): Nothing {
        unmapped += request
        throw IllegalStateException("No fixture mapped for $request")
    }

    private companion object {
        const val SYMBOLS_URL = "https://www.biznesradar.pl/service-data-js/8"
        const val EMPTY_ARTICLE = "<html><body></body></html>"
        val NON_SESSION_PAGE = "<!DOCTYPE html><html><body>Brak notowań</body></html>".toByteArray()
        val GPW_DETAIL = Regex("""https://www\.gpw\.pl/komunikat\?geru_id=(\d+)""")
        val QUARTERLY = Regex("""https://www\.biznesradar\.pl/raporty-finansowe-rachunek-zyskow-i-strat/(\w+),Q""")
        val VALUATION = Regex("""https://www\.biznesradar\.pl/wskazniki-wartosci-rynkowej/(\w+)""")
        val BANKIER_LIST = Regex("""https://www\.bankier\.pl/gielda/notowania/akcje/([^/]+)/komunikaty""")
        val BANKIER_ARTICLE = Regex("""https://www\.bankier\.pl/wiadomosc/(.+)""")
        val GPW_ARCHIVE = Regex("""https://www\.gpw\.pl/archiwum-notowan\?fetch=1&type=(10|1)&instrument=&date=(\d{2})-(\d{2})-(\d{4})""")
        val NC_DAILY = Regex("""https://newconnect\.pl/pub/NEWCONNECT/statystyki/statystyki_dzienne/NC_(\d{4})(\d{2})(\d{2})\.(xlsx?)""")
        val ATTACHMENT = Regex("""https://bonnier\.pl/static/att/emitent/[^/]+/\d{8}_\d{6}_\d+_(.+\.pdf)""")
        val UNCAPTURED_REPORTS = setOf("472431")
        val REPORT_FILES = mapOf(
            "497297" to "asbis-rb-23-2026.html",
            "494951" to "asbis-ps-2026.html",
            "494560" to "xtb-prelim-q2-2026.html",
            "488521" to "crj-prelim-q1-2026.html",
            "488507" to "irrelevant-nwz.html",
            "488512" to "correction.html",
            "473946" to "asbis-rb-sales-2025-09.html",
            "475584" to "asbis-rb-sales-2025-10.html",
            "477234" to "asbis-rb-sales-2025-11.html",
            "479118" to "asbis-rb-sales-2025-12.html",
            "480594" to "asbis-rb-sales-2026-01.html",
            "482189" to "asbis-rb-sales-2026-02.html",
            "484128" to "asbis-rb-sales-2026-03.html",
            "486464" to "asbis-rb-sales-2026-04.html",
            "489193" to "asbis-rb-sales-2026-05.html",
            "491914" to "asbis-rb-sales-2026-06.html",
            "494055" to "asbis-rb-sales-2026-07.html",
            "495649" to "asbis-rb-sales-2026-08.html",
        )
        val ARTICLE_FILES = mapOf(
            "ASBISc-Wyniki-finansowe-PS-2026-9178519.html" to "bankier/asbis-article-ps-2026.html",
            "XTB-Wstepne-wyniki-finansowe-i-operacyjne-za-I-polrocze-2026-roku-9175226.html" to "bankier/xtb-article-prelim-q2-2026.html",
        )
        val PDF_FILES = mapOf(
            "Raport_polroczny_H1_2026.pdf" to "asbis-ps-2026-1.pdf",
            "Sprawozdanie_finansowe_H1_2026.pdf" to "asbis-ps-2026-2.pdf",
            "2026_Q2_ASBIS_EN_report_Q2_andH1_2026.pdf" to "asbis-ps-2026-3.pdf",
            "ASBISc_FS_H1_2026.pdf" to "asbis-ps-2026-4.pdf",
            "Zalacznik_do_RB_nr_18_2026_PL.pdf" to "xtb-prelim-q2-2026-1.pdf",
        )
    }
}

class AnthropicStub {

    private class RecordedFixture(val name: String, val needles: List<String>, val body: String)

    private val mapper = jacksonObjectMapper()

    val unmatched = CopyOnWriteArrayList<String>()
    val served = CopyOnWriteArrayList<String>()

    private val extractionFixtures: List<RecordedFixture> by lazy {
        Files.list(Fixtures.directory("ai")).use { files ->
            files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .filter { it.fileName.toString() != REASON_FIXTURE }
                .map { path -> recorded(path.fileName.toString().removeSuffix(".json"), Files.readString(path)) }
                .filter { it.needles.isNotEmpty() }
                .toList()
        }
    }

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request.body.readUtf8())
        }
        start()
    }

    val baseUrl: String = server.url("/").toString().trimEnd('/')

    private fun answer(body: String): MockResponse {
        if (REASON_MARKER in body) return json(checkNotNull(Fixtures.text("ai/$REASON_FIXTURE")), "reason-canned")
        val userText = userText(body)
        val fullMatches = extractionFixtures.filter { fixture -> fixture.needles.all { it in userText } }
        val best = fullMatches.maxByOrNull { it.needles.size }
        val ambiguous = best != null && fullMatches.count { it.needles.size == best.needles.size } > 1
        if (best == null && ATTACHMENTS_MARKER !in userText) {
            served += "body-without-numbers"
            return json(BODY_WITHOUT_NUMBERS, "body-without-numbers")
        }
        if (best == null || ambiguous) {
            unmatched += "${if (ambiguous) "ambiguous" else "no fixture"}: ${userText.take(300)}"
            return MockResponse().setResponseCode(400).setHeader("content-type", "application/json")
                .setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"no recorded response for this report"}}""")
        }
        return json(best.body, best.name)
    }

    private fun json(body: String, name: String): MockResponse {
        served += name
        return MockResponse().setHeader("content-type", "application/json").setBody(body)
    }

    private fun userText(body: String): String {
        val content = mapper.readTree(body).path("messages").path(0).path("content")
        return if (content.isArray) content.joinToString("\n") { it.path("text").asString("") } else content.asString("")
    }

    private fun recorded(name: String, body: String): RecordedFixture {
        val text = mapper.readTree(body).path("content").firstNotNullOf { block -> block.path("text").asString("").takeIf { it.isNotEmpty() } }
        val extraction = mapper.readValue<Extraction>(text)
        val amounts = extraction.periods.flatMap {
            listOfNotNull(it.revenue, it.revenuePrevYear, it.operatingProfit, it.operatingProfitPrevYear, it.netProfit, it.netProfitPrevYear)
        }
        val needles = amounts.map { it.sourceText } + listOfNotNull(extraction.outlook.quote, extraction.oneOffQuote)
        return RecordedFixture(name, needles, body)
    }

    private companion object {
        const val REASON_FIXTURE = "reason-canned.json"
        const val REASON_MARKER = "Piszesz uzasadnienie oceny raportu"
        const val ATTACHMENTS_MARKER = "Załączniki PDF (wybrane strony)"
        const val BODY_WITHOUT_NUMBERS =
            """{"id":"msg_body_without_numbers","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"text","text":"{\"category\":\"RESULTS\",\"periods\":[],\"outlook\":{\"tone\":\"NONE\",\"quote\":null},\"oneOffQuarter\":false,\"oneOffQuote\":null,\"cyclicalIndustry\":false}"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":10}}"""
    }
}

class RecordingPushNotifier(devices: DeviceRepository, pushLog: PushLogRepository, private val clock: Clock) :
    PushNotifier(deliveringFirebase(), devices, pushLog, clock) {

    data class Sent(val message: PushMessage, val at: Instant)

    val sent = CopyOnWriteArrayList<Sent>()

    override fun send(message: PushMessage) {
        sent += Sent(message, clock.instant())
        super.send(message)
    }
}

private fun deliveringFirebase(): FirebaseMessaging = mockk {
    every { sendEach(any<List<Message>>()) } answers {
        val delivered = firstArg<List<Message>>().map { mockk<SendResponse> { every { exception } returns null } }
        mockk<BatchResponse> { every { responses } returns delivered }
    }
}

@TestConfiguration
class ReplayConfig {

    @Bean
    @Primary
    fun replayClock(): MutableClock = MutableClock(ReplayHarness.PREPARED_AT)

    @Bean
    @Primary
    fun replayHttp(clock: MutableClock, tracker: SourceHealthTracker): FixtureHttpClient = FixtureHttpClient(clock, tracker)

    @Bean
    @Primary
    fun replayNotifier(devices: DeviceRepository, pushLog: PushLogRepository, clock: MutableClock): RecordingPushNotifier =
        RecordingPushNotifier(devices, pushLog, clock)
}

class ReplayHarness(
    private val jdbc: JdbcClient,
    private val clock: MutableClock,
    private val http: FixtureHttpClient,
    private val anthropic: AnthropicStub,
    private val notifier: RecordingPushNotifier,
    private val pipeline: ReportPipeline,
    private val pendingJob: PendingReportsJob,
    private val backfill: MonthlySalesBackfillJob,
    private val quotesJob: DailyQuotesJob,
    private val refresher: CompanyDataRefresher,
    private val companies: CompanyRepository,
    private val reports: ReportRepository,
    private val scores: ScoreRepository,
    private val extractions: ExtractionRepository,
    private val assembler: ScoringInputAssembler,
    private val settings: SettingRepository,
) {

    private val parser = EspiListingParser()

    fun reset() {
        jdbc.sql("TRUNCATE company RESTART IDENTITY CASCADE").update()
        listOf("daily_quote", "ai_usage", "source_health", "push_log").forEach { jdbc.sql("DELETE FROM $it").update() }
        jdbc.sql("DELETE FROM setting WHERE key <> 'PUSH_THRESHOLD'").update()
        settings.put("PUSH_THRESHOLD", "70")
        jdbc.sql("INSERT INTO device (token, registered_at) VALUES ('$REPLAY_DEVICE', now()) ON CONFLICT (token) DO NOTHING").update()
        http.unmapped.clear()
        http.notCaptured.clear()
        anthropic.unmatched.clear()
    }

    fun loadQuotes() {
        clock.set(PREPARED_AT)
        Files.list(Fixtures.directory("quotes")).use { files ->
            files.map { it.fileName.toString() }
                .filter { QUOTE_FILE.matches(it) }
                .map { LocalDate.parse(it.removePrefix("gpw-").removeSuffix(".xls")) }
                .sorted()
                .toList()
        }.forEach(quotesJob::run)
    }

    fun prepareAsInstalled(company: String) {
        clock.set(PREPARED_AT)
        backfill.backfill(monthlySalesBeforeInstallation(company))
        jdbc.sql("SELECT id FROM company WHERE short_name = :name").param("name", company).query(Long::class.java).optional()
            .ifPresent { refresher.refresh(companies.findById(it), force = true) }
    }

    private fun monthlySalesBeforeInstallation(company: String): List<ListingEntry> {
        val today = PREPARED_AT.toWarsawDate()
        val window = today.minusMonths(BACKFILL_MONTHS)..today.minusDays(1)
        return parser.parse(checkNotNull(Fixtures.text("gpw/backfill-asbis.html")), Market.GPW).entries.filter {
            it.isMonthlySales() && it.companyName.contains(company, ignoreCase = true) && it.publishedAt.toWarsawDate() in window
        }
    }

    fun listingEntry(listingFixture: String, sourceRef: String): ListingEntry =
        parser.parse(checkNotNull(Fixtures.text("gpw/$listingFixture")), Market.GPW).entries.single { it.sourceRef == sourceRef }

    fun replay(entry: ListingEntry, detectedAt: Instant): ReplayResult {
        releaseBackfilled(entry)
        notifier.sent.clear()
        clock.set(detectedAt)
        pipeline.process(entry)
        settle(entry, detectedAt)
        check(http.unmapped.isEmpty()) { "Requests without a fixture: ${http.unmapped}" }
        check(anthropic.unmatched.isEmpty()) { "AI requests without a recorded response: ${anthropic.unmatched}" }
        return resultOf(entry, detectedAt)
    }

    fun notCapturedRequests(): List<String> = http.notCaptured.distinct()

    fun servedFixtures(): List<String> = anthropic.served.toList()

    private fun reportId(entry: ListingEntry): Long =
        jdbc.sql("SELECT id FROM report WHERE source_ref = :ref").param("ref", entry.sourceRef).query(Long::class.java).single()

    private fun releaseBackfilled(entry: ListingEntry) {
        jdbc.sql("SELECT id FROM report WHERE source_ref = :ref AND status = :status")
            .param("ref", entry.sourceRef).param("status", ReportStatus.BACKFILLED.name)
            .query(Long::class.java).list()
            .forEach { id ->
                jdbc.sql("UPDATE revenue_period SET report_id = NULL WHERE report_id = :id").param("id", id).update()
                jdbc.sql("DELETE FROM report WHERE id = :id").param("id", id).update()
            }
    }

    private fun settle(entry: ListingEntry, detectedAt: Instant) {
        val deadline = detectedAt.plus(SETTLE_LIMIT)
        while (statusOf(entry) in PENDING && clock.instant() < deadline) {
            clock.advance(PENDING_JOB_INTERVAL)
            pendingJob.run()
        }
    }

    private fun statusOf(entry: ListingEntry): ReportStatus = reports.findById(reportId(entry)).status

    private fun resultOf(entry: ListingEntry, detectedAt: Instant): ReplayResult {
        val report: Report = reports.findById(reportId(entry))
        val id = checkNotNull(report.id)
        val stored = scores.find(id)
        val verified = extractions.find(id)
        val input = stored?.let { assembler.assemble(companies.findById(report.companyId), report, verified) }
        val pushes = notifier.sent.filter { it.message.kind == PushKind.RESULTS && it.message.reportId == id }
        val finishedAt = pushes.lastOrNull()?.at ?: clock.instant()
        return ReplayResult(
            status = report.status,
            score = stored?.score,
            pushes = pushes.map { it.message },
            elapsed = Duration.between(detectedAt, finishedAt),
            input = input,
            reason = stored?.reason,
            extraction = verified?.extraction,
        )
    }

    companion object {
        val PREPARED_AT: Instant = Instant.parse("2026-09-21T06:00:00Z")
        private const val BACKFILL_MONTHS = 13L
        private const val REPLAY_DEVICE = "replay-device"
        private val SETTLE_LIMIT: Duration = Duration.ofHours(6)
        private val PENDING_JOB_INTERVAL: Duration = Duration.ofMinutes(5)
        private val QUOTE_FILE = Regex("""gpw-\d{4}-\d{2}-\d{2}\.xls""")
        private val PENDING = setOf(
            ReportStatus.NEW,
            ReportStatus.PENDING_ATTACHMENTS,
            ReportStatus.PENDING_AI,
            ReportStatus.PENDING_DATA,
            ReportStatus.PUSH_PENDING,
        )
    }
}
