package pl.investor.pipeline

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.investor.extraction.AiAuthenticationException
import pl.investor.extraction.AiBudget
import pl.investor.extraction.AiBudgetExhaustedException
import pl.investor.extraction.AiPool
import pl.investor.extraction.newsPool
import pl.investor.extraction.resultsPool
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
import pl.investor.news.NewsContextAssembler
import pl.investor.notification.PushLogRepository
import pl.investor.notification.PushMessage
import pl.investor.notification.PushMessageFactory
import pl.investor.notification.PushNotifier
import pl.investor.notification.ReasonWriter
import pl.investor.report.Company
import pl.investor.report.CompanyNames
import pl.investor.report.CompanyRepository
import pl.investor.report.EventType
import pl.investor.report.Market
import pl.investor.report.Report
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import pl.investor.scoring.Score
import pl.investor.scoring.ScoreCalculator
import pl.investor.scoring.ScoreComponents
import pl.investor.scoring.ScoreRepository
import pl.investor.scoring.StoredScore
import pl.investor.scoring.ScoringInput
import pl.investor.scoring.ScoringInputAssembler
import pl.investor.scoring.primaryPeriod
import pl.investor.settings.AlertFilters
import pl.investor.settings.PushThreshold
import pl.investor.settings.SettingRepository
import pl.investor.source.SourceUnavailableException
import pl.investor.source.consensus.ConsensusNoteFinder
import pl.investor.source.content.ReportContent
import pl.investor.source.content.ReportContentClient
import pl.investor.source.eqs.isEqsCorporateNews
import pl.investor.source.gpw.ListingEntry
import pl.investor.source.nasdaq.NASDAQ_MARKETS
import pl.investor.source.nasdaq.isIgnoredNasdaqCategory
import pl.investor.support.toWarsawDate
import java.time.Clock
import java.time.Duration
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
    private val newsContext: NewsContextAssembler,
    private val xtbFilter: XtbFilter,
    private val consensusNotes: ConsensusNoteFinder,
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
            val draft = newReport(entry, company)
            if (isCopyOfKnownRelease(entry, company)) {
                reports.saveIfNew(draft.copy(status = ReportStatus.IRRELEVANT))
                return
            }
            reports.saveIfNew(draft) ?: return
        } catch (e: Exception) {
            log.error("Saving report {} failed, it will be retried on the next poll", entry.sourceRef, e)
            return
        }
        exclusively(checkNotNull(report.id)) {
            guarded(report) { route(report, company, entry, allowWithoutAttachments = false) }
        }
    }

    private fun route(report: Report, company: Company, entry: ListingEntry, allowWithoutAttachments: Boolean) {
        val next = nextStep(report, company, entry)
        when {
            next is Step.Skip -> setStatus(report, next.status)
            xtbFilter.withholdsFromAi(company) -> setStatus(report, ReportStatus.SKIPPED_NOT_ON_XTB)
            next == Step.Results -> analyze(report, company, entry, allowWithoutAttachments)
            else -> assessNews(report, company, entry, content = null)
        }
    }

    private fun nextStep(report: Report, company: Company, entry: ListingEntry): Step {
        val title = entry.title?.takeIf { it.isNotBlank() }
        return when {
            entry.isIgnoredNasdaqCategory() -> Step.Skip(ReportStatus.IRRELEVANT)
            isOutsideNordicCoverage(company) -> Step.Skip(ReportStatus.IRRELEVANT)
            title?.let(ResultsKeywordFilter::isCorrection) == true -> Step.Skip(ReportStatus.CORRECTION_SKIPPED)
            report.eventType == EventType.NEWS -> Step.News
            entry.kind.isPeriodic -> Step.Results
            title == null -> Step.Skip(ReportStatus.IRRELEVANT)
            entry.isEqsCorporateNews() -> if (ResultsKeywordFilter.isResultsRelease(title)) Step.Results else Step.Skip(ReportStatus.IRRELEVANT)
            ResultsKeywordFilter.matches(title, entry.market) -> Step.Results
            RoutineNewsFilter.isRoutine(title, entry.market) -> Step.Skip(ReportStatus.IRRELEVANT)
            else -> Step.News
        }
    }

    private fun isOutsideNordicCoverage(company: Company): Boolean {
        val listedElsewhere = LISTED_ELSEWHERE_GUARD[company.market] ?: return false
        return when (val identification = refresher.avanzaIdentification(company)) {
            AvanzaIdentification.NotListed -> true
            is AvanzaIdentification.Identified -> isFollowedElsewhere(company, identification.isin, listedElsewhere) || hasPolishNamesake(company)
            AvanzaIdentification.Unavailable -> hasPolishNamesake(company)
        }
    }

    private fun isFollowedElsewhere(company: Company, isin: String, markets: List<Market>): Boolean =
        companies.existsInMarkets(isin, markets).also { followed ->
            if (followed) log.warn("{} ({}) release dropped: ISIN {} is already followed in one of {}", company.name, company.market, isin, markets)
        }

    private fun hasPolishNamesake(company: Company): Boolean =
        companies.namesInMarkets(POLISH_MARKETS).any { CompanyNames.sameCompany(it, company.name) }

    private fun isCopyOfKnownRelease(entry: ListingEntry, company: Company): Boolean =
        entry.market in NASDAQ_MARKETS && reports.existsSameRelease(checkNotNull(company.id), entry.number, entry.publishedAt, SAME_RELEASE_WINDOW)

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

    fun rescoreWithConsensus(reportId: Long): Boolean = exclusively(reportId) {
        val report = reports.findById(reportId)
        rescore(report, companies.findById(report.companyId))
    }

    fun awaitConsensus(reportId: Long, mayFetchNote: Boolean): ConsensusNoteLookup {
        var lookup = ConsensusNoteLookup.NOT_ATTEMPTED
        exclusively(reportId) {
            val report = reports.findById(reportId)
            if (report.status != ReportStatus.AWAITING_CONSENSUS) return@exclusively
            val company = companies.findById(report.companyId)
            when {
                consensusNotes.hasNote(reportId) -> rescore(report, company)
                isPastConsensusDeadline(report) -> pushOnStoredScore(report, company)
                mayFetchNote -> {
                    lookup = findOrFetchConsensusNote(report)
                    if (lookup == ConsensusNoteLookup.FOUND) rescore(report, company)
                }
            }
        }
        return lookup
    }

    private fun rescore(report: Report, company: Company) =
        guarded(report) { scoreAndNotify(report, company, extractions.find(checkNotNull(report.id))) }

    private fun pushOnStoredScore(report: Report, company: Company) {
        val id = checkNotNull(report.id)
        val stored = checkNotNull(scores.find(id)) { "Report $id has no score to push" }
        decideResultsPush(report, company, stored.score, stored.reason, pushPeriod(report, company, extractions.find(id)))
    }

    private fun findOrFetchConsensusNote(report: Report): ConsensusNoteLookup =
        try {
            if (consensusNotes.findAndSave(report)) ConsensusNoteLookup.FOUND else ConsensusNoteLookup.NOT_FOUND
        } catch (e: SourceUnavailableException) {
            log.warn("Bankier unavailable while looking for the consensus note of report {}, it keeps waiting", report.sourceRef, e)
            ConsensusNoteLookup.SOURCE_UNAVAILABLE
        } catch (e: Exception) {
            log.warn("Looking for the consensus note of report {} failed, it keeps waiting", report.sourceRef, e)
            ConsensusNoteLookup.NOT_FOUND
        }

    private fun canFindConsensusNotes(company: Company): Boolean =
        runCatching { consensusNotes.canFindNotesOf(company) }.getOrElse {
            log.warn("Checking the Bankier news page of {} failed, waiting for the consensus note anyway", company.name, it)
            true
        }

    private fun isPastConsensusDeadline(report: Report): Boolean =
        consensusDeadline(report.publishedAt)?.let { clock.instant() >= it } ?: true

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
            val company = refreshedQuietly(companies.findById(report.companyId), force = true)
            when {
                assembler.currentQuarter(company, report) == null -> Unit
                xtbFilter.withholdsFromAi(company) -> setStatus(report, ReportStatus.SKIPPED_NOT_ON_XTB)
                else -> scoreAndNotify(report, company, extractions.find(reportId))
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
            !report.kind.isPeriodic && category != ReportCategory.RESULTS -> newsUnlessCorporate(report, company, entry, extracted.content)
            category == ReportCategory.OTHER -> setStatus(report, ReportStatus.IRRELEVANT)
            else -> {
                extractions.save(checkNotNull(report.id), extracted.verified)
                afterExtraction(report, company, entry, extracted.verified, extracted.content)
            }
        }
    }

    private fun extractWithBodyFirst(report: Report, company: Company, entry: ListingEntry, allowWithoutAttachments: Boolean): Extracted? {
        val periodic = entry.kind.isPeriodic
        if (!periodic && report.status == ReportStatus.PENDING_ATTACHMENTS) return extractAwaitingAttachments(report, company, entry, allowWithoutAttachments)
        val firstContent = contentClient.fetch(entry, includeAttachments = periodic)
        if ((periodic || firstContent.attachmentsPending) && holdForAttachments(report, firstContent, allowWithoutAttachments)) return null
        val first = extractFrom(report, company, firstContent) ?: return null
        val extraction = first.verified.extraction
        if (periodic || extraction.category != ReportCategory.RESULTS || extraction.primaryPeriod() != null || !firstContent.hasReadableAttachments) return first
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
        if (!budget.canSpend(report.market.resultsPool())) {
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

    private fun newsUnlessCorporate(report: Report, company: Company, entry: ListingEntry, content: ReportContent?) {
        if (entry.isEqsCorporateNews()) setStatus(report, ReportStatus.IRRELEVANT) else assessNewsUnlessRoutine(report, company, entry, content)
    }

    private fun assessNewsUnlessRoutine(report: Report, company: Company, entry: ListingEntry, content: ReportContent?) {
        if (RoutineNewsFilter.isRoutine(entry.title.orEmpty(), entry.market)) {
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
        if (!budget.canSpend(news.market.newsPool())) {
            newsWithoutAi(news)
            return
        }
        val body = content ?: contentClient.fetch(entry, includeAttachments = false)
        val refreshed = refreshedQuietly(company)
        val input = newsContext.input(refreshed, report, entry.title.orEmpty(), body)
        val assessment = callingAi(news, onBudgetExhausted = { newsWithoutAi(news) }) { newsAssessor.assess(input) } ?: return
        newsAssessments.save(id, assessment, clock.instant())
        scoreAndNotifyNews(news, refreshed, assessment)
    }

    private fun scoreAndNotifyNews(report: Report, company: Company, assessment: NewsAssessment) {
        val id = checkNotNull(report.id)
        val score = Score(assessment.score, NO_COMPONENTS, 0.0, newsContext.warnings(company, report))
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
        notifyBudgetExhaustedOnce(report.market.newsPool())
        setStatus(report, ReportStatus.UNSCORED_NO_AI)
    }

    private fun afterExtraction(report: Report, company: Company, entry: ListingEntry, verified: VerifiedExtraction, content: ReportContent?) {
        saveRevenuePeriods(report, company, verified)
        when {
            verified.extraction.primaryPeriod() != null -> scoreAndNotify(report, company, verified)
            entry.isEqsCorporateNews() -> setStatus(report, ReportStatus.IRRELEVANT)
            !report.kind.isPeriodic -> assessNews(report, company, entry, content)
            else -> setStatus(report, ReportStatus.PENDING_DATA)
        }
    }

    private fun extractAndVerify(content: ReportContent, company: Company): VerifiedExtraction =
        verifier.verify(extractor.extract(content, company.name, company.market.resultsPool()), ExtractionPrompt.reportInput(content), company.market)

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
        notifyBudgetExhaustedOnce(report.market.resultsPool())
        refreshedQuietly(company)
        val hasStructuredDataFromToday = report.kind.isPeriodic &&
            quarterResults.publishedOn(checkNotNull(company.id), report.publishedAt.toWarsawDate()) != null
        if (hasStructuredDataFromToday) {
            scoreAndNotify(report, company, verified = null)
        } else {
            setStatus(report, ReportStatus.UNSCORED_NO_AI)
        }
    }

    private fun scoreAndNotify(report: Report, stale: Company, verified: VerifiedExtraction?) {
        val company = refreshedQuietly(stale)
        val input = assembler.assemble(company, report, verified)
        val score = ScoreCalculator.score(input)
        val reason = reasonWriter.write(company, input, score)
        scores.save(checkNotNull(report.id), score, reason, clock.instant())
        val period = pushPeriod(report, company, verified)
        if (awaitsConsensus(report, company, input, period)) {
            setStatus(report, ReportStatus.AWAITING_CONSENSUS)
            return
        }
        decideResultsPush(report, company, score, reason, period)
    }

    private fun awaitsConsensus(report: Report, company: Company, input: ScoringInput, period: PushPeriod?): Boolean =
        report.market == Market.GPW &&
            period?.kind == PeriodKind.QUARTER &&
            input.consensus.isEmpty() &&
            report.isResultsPushEnabled() &&
            !isAlreadyPushed(company, period) &&
            consensusDeadline(report.publishedAt)?.let { clock.instant() < it } == true &&
            !consensusNotes.hasNote(checkNotNull(report.id)) &&
            canFindConsensusNotes(company)

    private fun decideResultsPush(report: Report, company: Company, score: Score, reason: String, period: PushPeriod?) {
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

    private fun refreshedQuietly(company: Company, force: Boolean = false): Company {
        try {
            refresher.refresh(company, force)
        } catch (e: Exception) {
            log.warn("Refreshing financial data of {} failed, scoring with what is stored", company.name, e)
        }
        return companies.findById(checkNotNull(company.id))
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

    private sealed interface Step {
        data class Skip(val status: ReportStatus) : Step
        data object Results : Step
        data object News : Step
    }

    private data class Extracted(val verified: VerifiedExtraction, val content: ReportContent)

    private companion object {
        val SAME_RELEASE_WINDOW: Duration = Duration.ofMinutes(15)
        val POLISH_MARKETS = listOf(Market.GPW, Market.NC)
        val LISTED_ELSEWHERE_GUARD = mapOf(Market.SE to POLISH_MARKETS + Market.FI, Market.FI to POLISH_MARKETS + Market.SE)
        val NO_COMPONENTS = ScoreComponents(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }
}
