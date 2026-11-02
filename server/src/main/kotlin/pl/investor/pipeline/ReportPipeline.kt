package pl.investor.pipeline

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.investor.extraction.AiAuthenticationException
import pl.investor.extraction.AiBudget
import pl.investor.extraction.AiBudgetExhaustedException
import pl.investor.extraction.AiPool
import pl.investor.extraction.AiUnavailableException
import pl.investor.extraction.ClaudeExtractor
import pl.investor.extraction.ExtractionPrompt
import pl.investor.extraction.ExtractionRepository
import pl.investor.extraction.QuoteVerifier
import pl.investor.extraction.ReportCategory
import pl.investor.extraction.VerifiedExtraction
import pl.investor.history.PeriodKind
import pl.investor.history.QuarterResultRepository
import pl.investor.history.RevenuePeriod
import pl.investor.history.RevenuePeriodRepository
import pl.investor.news.NewsAssessment
import pl.investor.news.NewsAssessmentRepository
import pl.investor.news.NewsAssessor
import pl.investor.news.NewsDirection
import pl.investor.news.NEWS_LISTED_MIN_SCORE
import pl.investor.news.TRAILING_YEAR_QUARTERS
import pl.investor.news.NewsInput
import pl.investor.news.trailingYearRevenue
import pl.investor.notification.PushLogRepository
import pl.investor.notification.PushMessage
import pl.investor.notification.PushMessageFactory
import pl.investor.notification.PushNotifier
import pl.investor.notification.ReasonWriter
import pl.investor.report.Company
import pl.investor.report.CompanyRepository
import pl.investor.report.EventType
import pl.investor.report.Report
import pl.investor.report.ReportKind
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import pl.investor.scoring.Score
import pl.investor.scoring.ScoreCalculator
import pl.investor.scoring.ScoreComponents
import pl.investor.scoring.ScoreRepository
import pl.investor.scoring.StoredScore
import pl.investor.scoring.ScoringInputAssembler
import pl.investor.scoring.primaryPeriod
import pl.investor.settings.AlertFilters
import pl.investor.settings.PushThreshold
import pl.investor.settings.SettingRepository
import pl.investor.source.SourceUnavailableException
import pl.investor.source.content.ReportContent
import pl.investor.source.content.ReportContentClient
import pl.investor.source.gpw.ListingEntry
import pl.investor.support.toWarsawDate
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap

@Component
class ReportPipeline(
    private val companies: CompanyRepository,
    private val reports: ReportRepository,
    private val resolver: CompanyResolver,
    private val contentClient: ReportContentClient,
    private val budget: AiBudget,
    private val extractor: ClaudeExtractor,
    private val verifier: QuoteVerifier,
    private val extractions: ExtractionRepository,
    private val revenuePeriods: RevenuePeriodRepository,
    private val quarterResults: QuarterResultRepository,
    private val refresher: CompanyDataRefresher,
    private val assembler: ScoringInputAssembler,
    private val reasonWriter: ReasonWriter,
    private val scores: ScoreRepository,
    private val settings: SettingRepository,
    private val pushThreshold: PushThreshold,
    private val alertFilters: AlertFilters,
    private val pushLog: PushLogRepository,
    private val notifier: PushNotifier,
    private val messages: PushMessageFactory,
    private val newsAssessor: NewsAssessor,
    private val newsAssessments: NewsAssessmentRepository,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(ReportPipeline::class.java)
    private val inFlight = ConcurrentHashMap.newKeySet<Long>()

    fun process(entry: ListingEntry) {
        val company = try {
            resolver.resolve(entry)
        } catch (e: Exception) {
            log.error("Resolving company of report {} failed, it will be retried on the next poll", entry.sourceRef, e)
            return
        }
        val report = try {
            reports.saveIfNew(newReport(entry, company)) ?: return
        } catch (e: Exception) {
            log.error("Saving report {} failed, it will be retried on the next poll", entry.sourceRef, e)
            return
        }
        exclusively(checkNotNull(report.id)) {
            guarded(report) { route(report, company, entry, allowWithoutAttachments = false) }
        }
    }

    private fun route(report: Report, company: Company, entry: ListingEntry, allowWithoutAttachments: Boolean) {
        val title = entry.title?.takeIf { it.isNotBlank() }
        when {
            title?.let(ResultsKeywordFilter::isCorrection) == true -> setStatus(report, ReportStatus.CORRECTION_SKIPPED)
            report.eventType == EventType.NEWS -> assessNews(report, company, entry, content = null)
            entry.kind != ReportKind.CURRENT -> analyze(report, company, entry, allowWithoutAttachments)
            title == null -> setStatus(report, ReportStatus.IRRELEVANT)
            ResultsKeywordFilter.matches(title) -> analyze(report, company, entry, allowWithoutAttachments)
            else -> assessNewsUnlessRoutine(report, company, entry, content = null)
        }
    }

    fun reprocess(reportId: Long, allowWithoutAttachments: Boolean): Boolean = exclusively(reportId) {
        val report = reports.findById(reportId)
        guarded(report) {
            val company = companies.findById(report.companyId)
            route(report, company, entryOf(report, company), allowWithoutAttachments)
        }
    }

    fun retryPush(reportId: Long): Boolean = exclusively(reportId) {
        val report = reports.findById(reportId)
        val company = companies.findById(report.companyId)
        val stored = checkNotNull(scores.find(reportId)) { "Report $reportId has no score to push" }
        when {
            report.eventType == EventType.NEWS -> retryNewsPush(report, company, stored)
            !report.isResultsPushEnabled() -> setStatus(report, ReportStatus.SCORED)
            else -> {
                val period = pushPeriod(report, company, extractions.find(reportId))
                if (isAlreadyPushed(company, period)) setStatus(report, ReportStatus.SCORED) else deliverResults(report, company, stored.score, stored.reason, period)
            }
        }
    }

    private fun retryNewsPush(report: Report, company: Company, stored: StoredScore) {
        val id = checkNotNull(report.id)
        val assessment = newsAssessments.find(id)
        if (assessment != null && isNewsPushDue(report, assessment)) {
            deliver(report, messages.forNews(company, report.market, stored.score, stored.reason, id))
        } else {
            setStatus(report, ReportStatus.SCORED)
        }
    }

    fun retryPendingData(reportId: Long): Boolean = exclusively(reportId) {
        val report = reports.findById(reportId)
        guarded(report) {
            val company = companies.findById(report.companyId)
            refreshQuietly(company, force = true)
            if (assembler.currentQuarter(company, report) != null) {
                scoreAndNotify(report, company, extractions.find(reportId))
            }
        }
    }

    private fun exclusively(reportId: Long, block: () -> Unit): Boolean {
        if (!inFlight.add(reportId)) return false
        try {
            block()
        } finally {
            inFlight.remove(reportId)
        }
        return true
    }

    private fun guarded(report: Report, block: () -> Unit) {
        try {
            block()
        } catch (e: SourceUnavailableException) {
            log.warn("Source unavailable while processing report {}, it will be retried", report.sourceRef, e)
            runCatching { setStatus(report, ReportStatus.NEW) }
                .onFailure { log.error("Marking report {} as NEW failed", report.sourceRef, it) }
        } catch (e: Exception) {
            log.error("Processing of report {} failed", report.sourceRef, e)
            runCatching { setStatus(report, ReportStatus.FAILED) }
                .onFailure { log.error("Marking report {} as FAILED failed", report.sourceRef, it) }
        }
    }

    private fun analyze(report: Report, company: Company, entry: ListingEntry, allowWithoutAttachments: Boolean) {
        extractions.find(checkNotNull(report.id))?.let {
            afterExtraction(report, company, entry, it, content = null)
            return
        }
        val extracted = extractWithBodyFirst(report, company, entry, allowWithoutAttachments) ?: return
        val category = extracted.verified.extraction.category
        when {
            report.kind == ReportKind.CURRENT && category != ReportCategory.RESULTS -> assessNewsUnlessRoutine(report, company, entry, extracted.content)
            category == ReportCategory.OTHER -> setStatus(report, ReportStatus.IRRELEVANT)
            else -> {
                extractions.save(checkNotNull(report.id), extracted.verified)
                afterExtraction(report, company, entry, extracted.verified, extracted.content)
            }
        }
    }

    private fun extractWithBodyFirst(report: Report, company: Company, entry: ListingEntry, allowWithoutAttachments: Boolean): Extracted? {
        val periodic = entry.kind != ReportKind.CURRENT
        if (!periodic && report.status == ReportStatus.PENDING_ATTACHMENTS) return extractAwaitingAttachments(report, company, entry, allowWithoutAttachments)
        val firstContent = contentClient.fetch(entry, includeAttachments = periodic)
        if (periodic && holdForAttachments(report, firstContent, allowWithoutAttachments)) return null
        val first = extractFrom(report, company, firstContent) ?: return null
        val extraction = first.verified.extraction
        if (periodic || extraction.category != ReportCategory.RESULTS || extraction.primaryPeriod() != null || firstContent.attachmentUrls.isEmpty()) return first
        val withAttachments = contentClient.fetch(entry, includeAttachments = true)
        if (withAttachments.attachmentsPending) {
            if (!allowWithoutAttachments) setStatus(report, ReportStatus.PENDING_ATTACHMENTS)
            return first.takeIf { allowWithoutAttachments }
        }
        return extractFrom(report, company, withAttachments)
    }

    private fun extractAwaitingAttachments(report: Report, company: Company, entry: ListingEntry, allowWithoutAttachments: Boolean): Extracted? {
        val content = contentClient.fetch(entry, includeAttachments = true)
        return if (content.attachmentsPending && !allowWithoutAttachments) null else extractFrom(report, company, content)
    }

    private fun holdForAttachments(report: Report, content: ReportContent, allowWithoutAttachments: Boolean): Boolean {
        val hold = content.attachmentsPending && !allowWithoutAttachments
        if (hold) setStatus(report, ReportStatus.PENDING_ATTACHMENTS)
        return hold
    }

    private fun extractFrom(report: Report, company: Company, content: ReportContent): Extracted? {
        if (!budget.canSpend(AiPool.GPW)) {
            scoreWithoutAi(report, company)
            return null
        }
        return callingAi(report, onBudgetExhausted = { scoreWithoutAi(report, company) }) {
            Extracted(extractAndVerify(content, company), content)
        }
    }

    private fun <T> callingAi(report: Report, onBudgetExhausted: () -> Unit, call: () -> T): T? =
        try {
            call()
        } catch (e: AiBudgetExhaustedException) {
            onBudgetExhausted()
            null
        } catch (e: AiUnavailableException) {
            log.warn("AI unavailable for report {}, it stays pending", report.sourceRef, e)
            setStatus(report, ReportStatus.PENDING_AI)
            null
        } catch (e: AiAuthenticationException) {
            log.error("Anthropic rejected the API key for report {}, it stays pending", report.sourceRef, e)
            setStatus(report, ReportStatus.PENDING_AI)
            notifyAiKeyRejectedOnce()
            null
        }

    private fun assessNewsUnlessRoutine(report: Report, company: Company, entry: ListingEntry, content: ReportContent?) {
        if (RoutineNewsFilter.isRoutine(entry.title.orEmpty())) {
            setStatus(report, ReportStatus.IRRELEVANT)
        } else {
            assessNews(report, company, entry, content)
        }
    }

    private fun assessNews(report: Report, company: Company, entry: ListingEntry, content: ReportContent?) {
        val id = checkNotNull(report.id)
        reports.updateEventType(id, EventType.NEWS)
        val news = report.copy(eventType = EventType.NEWS)
        newsAssessments.find(id)?.let {
            scoreAndNotifyNews(news, company, it)
            return
        }
        if (!budget.canSpend(AiPool.NEWS)) {
            newsWithoutAi(news)
            return
        }
        val body = content ?: contentClient.fetch(entry, includeAttachments = false)
        refreshQuietly(company)
        val input = NewsInput(company.name, report.market, entry.title.orEmpty(), body, trailingRevenue(company, report))
        val assessment = callingAi(news, onBudgetExhausted = { newsWithoutAi(news) }) { newsAssessor.assess(input) } ?: return
        newsAssessments.save(id, assessment, clock.instant())
        scoreAndNotifyNews(news, company, assessment)
    }

    private fun scoreAndNotifyNews(report: Report, company: Company, assessment: NewsAssessment) {
        val id = checkNotNull(report.id)
        val score = Score(assessment.score, NO_COMPONENTS, 0.0, emptySet())
        scores.save(id, score, assessment.reason, clock.instant())
        if (!isNewsPushDue(report, assessment)) {
            setStatus(report, ReportStatus.SCORED)
            return
        }
        setStatus(report, ReportStatus.PUSH_PENDING)
        deliver(report, messages.forNews(company, report.market, score, assessment.reason, id))
    }

    private fun isNewsPushDue(report: Report, assessment: NewsAssessment): Boolean =
        assessment.direction == NewsDirection.POSITIVE &&
            assessment.score >= maxOf(pushThreshold.current(), NEWS_LISTED_MIN_SCORE.toDouble()) &&
            report.isNewsPushEnabled() &&
            !pushLog.newsPushExists(checkNotNull(report.id))

    private fun newsWithoutAi(report: Report) {
        notifyBudgetExhaustedOnce(AiPool.NEWS)
        setStatus(report, ReportStatus.UNSCORED_NO_AI)
    }

    private fun trailingRevenue(company: Company, report: Report) =
        quarterResults.publishedBefore(checkNotNull(company.id), report.publishedAt.toWarsawDate().plusDays(1), TRAILING_YEAR_QUARTERS)
            .trailingYearRevenue()

    private fun afterExtraction(report: Report, company: Company, entry: ListingEntry, verified: VerifiedExtraction, content: ReportContent?) {
        saveRevenuePeriods(report, company, verified)
        when {
            verified.extraction.primaryPeriod() != null -> scoreAndNotify(report, company, verified)
            report.kind == ReportKind.CURRENT -> assessNews(report, company, entry, content)
            else -> setStatus(report, ReportStatus.PENDING_DATA)
        }
    }

    private fun extractAndVerify(content: ReportContent, company: Company): VerifiedExtraction =
        verifier.verify(extractor.extract(content, company.name, AiPool.GPW), ExtractionPrompt.reportInput(content))

    private fun saveRevenuePeriods(report: Report, company: Company, verified: VerifiedExtraction) {
        verified.extraction.periods.forEach { period ->
            val revenue = period.revenue ?: return@forEach
            revenuePeriods.save(
                RevenuePeriod(
                    companyId = checkNotNull(company.id),
                    kind = period.kind,
                    periodEnd = period.periodEnd,
                    currency = period.currency,
                    revenue = revenue.valueInThousands,
                    revenuePrevYear = period.revenuePrevYear?.valueInThousands,
                    publishedAt = report.publishedAt,
                    reportId = report.id,
                )
            )
        }
    }

    private fun scoreWithoutAi(report: Report, company: Company) {
        notifyBudgetExhaustedOnce(AiPool.GPW)
        refreshQuietly(company)
        val hasStructuredDataFromToday = report.kind != ReportKind.CURRENT &&
            quarterResults.publishedOn(checkNotNull(company.id), report.publishedAt.toWarsawDate()) != null
        if (hasStructuredDataFromToday) {
            scoreAndNotify(report, company, verified = null)
        } else {
            setStatus(report, ReportStatus.UNSCORED_NO_AI)
        }
    }

    private fun scoreAndNotify(report: Report, company: Company, verified: VerifiedExtraction?) {
        refreshQuietly(company)
        val input = assembler.assemble(company, report, verified)
        val score = ScoreCalculator.score(input)
        val reason = reasonWriter.write(company, input, score)
        scores.save(checkNotNull(report.id), score, reason, clock.instant())
        val period = pushPeriod(report, company, verified)
        if (score.total < pushThreshold.current() || !report.isResultsPushEnabled() || isAlreadyPushed(company, period)) {
            setStatus(report, ReportStatus.SCORED)
            return
        }
        setStatus(report, ReportStatus.PUSH_PENDING)
        deliverResults(report, company, score, reason, period)
    }

    private fun deliverResults(report: Report, company: Company, score: Score, reason: String, period: PushPeriod?) =
        deliver(
            report,
            messages.forResults(company, report.market, score, reason, checkNotNull(report.id))
                .copy(companyId = company.id, periodKind = period?.kind, periodEnd = period?.end),
        )

    private fun deliver(report: Report, message: PushMessage) {
        try {
            notifier.send(message)
            setStatus(report, ReportStatus.SCORED)
        } catch (e: Exception) {
            log.error("Sending push for report {} failed, it stays PUSH_PENDING", report.sourceRef, e)
        }
    }

    private fun Report.isResultsPushEnabled(): Boolean = alertFilters.allows(market, EventType.RESULTS)

    private fun Report.isNewsPushEnabled(): Boolean = alertFilters.allows(market, EventType.NEWS)

    private fun isAlreadyPushed(company: Company, period: PushPeriod?): Boolean =
        period != null && pushLog.resultsPushExists(checkNotNull(company.id), period.kind, period.end)

    private fun pushPeriod(report: Report, company: Company, verified: VerifiedExtraction?): PushPeriod? =
        verified?.extraction?.primaryPeriod()?.let { PushPeriod(it.kind, it.periodEnd) }
            ?: assembler.currentQuarter(company, report)?.let { PushPeriod(PeriodKind.QUARTER, it.quarterEnd) }

    private fun notifyBudgetExhaustedOnce(pool: AiPool) =
        notifyOnce("AI_BUDGET_NOTIFIED_${pool.name}_${YearMonth.now(clock)}") { messages.forAiBudgetExhausted(pool) }

    private fun notifyAiKeyRejectedOnce() =
        notifyOnce("AI_AUTH_NOTIFIED_${clock.instant().toWarsawDate()}") { messages.forAiKeyRejected() }

    private fun notifyOnce(key: String, message: () -> PushMessage) {
        if (!settings.putIfAbsent(key, clock.instant().toString())) return
        try {
            notifier.send(message())
        } catch (e: Exception) {
            log.error("Sending push {} failed", key, e)
        }
    }

    private fun refreshQuietly(company: Company, force: Boolean = false) {
        try {
            refresher.refresh(company, force)
        } catch (e: Exception) {
            log.warn("Refreshing BiznesRadar data of {} failed, scoring with what is stored", company.name, e)
        }
    }

    private fun setStatus(report: Report, status: ReportStatus) = reports.updateStatus(checkNotNull(report.id), status, clock.instant())

    private fun newReport(entry: ListingEntry, company: Company) = Report(
        id = null,
        companyId = checkNotNull(company.id),
        market = entry.market,
        kind = entry.kind,
        number = entry.number,
        sourceRef = entry.sourceRef,
        title = entry.title,
        publishedAt = entry.publishedAt,
        detectedAt = clock.instant(),
        detailUrl = entry.detailUrl,
        status = ReportStatus.NEW,
    )

    private fun entryOf(report: Report, company: Company) = ListingEntry(
        market = report.market,
        companyName = company.name,
        isin = company.isin,
        kind = report.kind,
        number = report.number,
        sourceRef = report.sourceRef,
        title = report.title,
        publishedAt = report.publishedAt,
        detailUrl = report.detailUrl,
    )

    private data class PushPeriod(val kind: PeriodKind, val end: LocalDate)

    private data class Extracted(val verified: VerifiedExtraction, val content: ReportContent)

    private companion object {
        val NO_COMPONENTS = ScoreComponents(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }
}
