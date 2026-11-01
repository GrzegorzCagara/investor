package pl.investor.pipeline

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import pl.investor.extraction.AiAuthenticationException
import pl.investor.extraction.AiBudgetExhaustedException
import pl.investor.extraction.AiPool
import pl.investor.extraction.ClaudeExtractor
import pl.investor.extraction.ExtractionPrompt
import pl.investor.extraction.QuoteVerifier
import pl.investor.history.PeriodKind
import pl.investor.history.RevenuePeriod
import pl.investor.history.RevenuePeriodRepository
import pl.investor.report.CompanyRepository
import pl.investor.report.Market
import pl.investor.report.Report
import pl.investor.report.ReportKind
import pl.investor.report.ReportRepository
import pl.investor.report.ReportStatus
import pl.investor.source.content.ReportContentClient
import pl.investor.source.gpw.EspiListingClient
import pl.investor.source.gpw.ListingEntry
import pl.investor.support.toWarsawDate
import java.text.Normalizer
import java.time.Clock
import java.time.LocalDate

@Component
class MonthlySalesBackfillJob(
    private val listing: EspiListingClient,
    private val resolver: CompanyResolver,
    private val companies: CompanyRepository,
    private val reports: ReportRepository,
    private val contentClient: ReportContentClient,
    private val extractor: ClaudeExtractor,
    private val verifier: QuoteVerifier,
    private val revenuePeriods: RevenuePeriodRepository,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(MonthlySalesBackfillJob::class.java)

    fun runAll(months: Int = DEFAULT_MONTHS): Int {
        val today = clock.instant().toWarsawDate()
        val yesterday = today.minusDays(1)
        val firstDay = today.minusMonths(months.toLong())
        val entries = mutableListOf<ListingEntry>()
        generateSequence(firstDay) { it.plusDays(1) }.takeWhile { !it.isAfter(yesterday) }.forEachIndexed { index, date ->
            if (index % PROGRESS_EVERY_DAYS == 0) log.info("Monthly sales backfill scan at {}, {} reports found so far", date, entries.size)
            listOf(Market.GPW, Market.NC).forEach { market ->
                runCatching { monthlySalesEntriesOn(market, date) }
                    .onSuccess { entries += it }
                    .onFailure { log.warn("Reading {} listing for {} failed, the day is skipped", market, date, it) }
            }
        }
        val groups = entries.groupBy { it.isin ?: it.companyName }.values
        log.info("Monthly sales backfill scan finished, {} reports of {} companies found", entries.size, groups.size)
        var saved = 0
        for (group in groups) {
            try {
                saved += backfill(group)
            } catch (e: AiBudgetExhaustedException) {
                log.warn("AI budget exhausted, monthly sales backfill stopped")
                break
            } catch (e: AiAuthenticationException) {
                log.error("Anthropic API rejected the API key, monthly sales backfill stopped", e)
                break
            } catch (e: Exception) {
                log.error("Backfill of {} failed", group.first().companyName, e)
            }
        }
        log.info("Monthly sales backfill finished, {} monthly revenues saved", saved)
        return groups.size
    }

    private fun monthlySalesEntriesOn(market: Market, date: LocalDate): List<ListingEntry> {
        val found = mutableListOf<ListingEntry>()
        var offset = 0
        do {
            val page = listing.page(market, date, offset)
            page.entries.filterTo(found) { it.isMonthlySales() }
            offset += EspiListingClient.PAGE_SIZE
        } while (page.rawItemCount >= EspiListingClient.PAGE_SIZE)
        return found
    }

    fun backfill(entries: List<ListingEntry>): Int {
        val sorted = entries.sortedBy { it.publishedAt }
        val company = sorted.firstOrNull()?.let(resolver::resolve) ?: return 0
        val companyId = checkNotNull(company.id)
        companies.markPublishesMonthlySales(companyId)
        val unread = sorted.filterNot { reports.existsBySourceRef(it.market, it.sourceRef) }
        log.info("Monthly sales backfill of {}: {} of {} reports to read", company.name, unread.size, sorted.size)
        var stored = 0
        unread.forEach { entry ->
            try {
                stored += store(entry, companyId, company.name)
            } catch (e: AiBudgetExhaustedException) {
                throw e
            } catch (e: AiAuthenticationException) {
                throw e
            } catch (e: Exception) {
                log.error("Backfill of report {} failed", entry.sourceRef, e)
            }
        }
        return stored
    }

    private fun store(entry: ListingEntry, companyId: Long, companyName: String): Int {
        val content = contentClient.fetch(entry)
        val verified = verifier.verify(extractor.extract(content, companyName, AiPool.GPW), ExtractionPrompt.reportInput(content))
        val monthly = verified.extraction.periods.filter { it.kind == PeriodKind.MONTH && it.revenue != null }
        return checkNotNull(
            transactions.execute {
                val reportId = reports.saveIfNew(backfilledReport(entry, companyId))?.id
                if (reportId == null) {
                    0
                } else {
                    monthly.forEach { period ->
                        revenuePeriods.save(
                            RevenuePeriod(
                                companyId = companyId,
                                kind = PeriodKind.MONTH,
                                periodEnd = period.periodEnd,
                                currency = period.currency,
                                revenue = checkNotNull(period.revenue).valueInThousands,
                                revenuePrevYear = period.revenuePrevYear?.valueInThousands,
                                publishedAt = entry.publishedAt,
                                reportId = reportId,
                            )
                        )
                    }
                    monthly.size
                }
            }
        )
    }

    private fun backfilledReport(entry: ListingEntry, companyId: Long) = Report(
        id = null,
        companyId = companyId,
        market = entry.market,
        kind = entry.kind,
        number = entry.number,
        sourceRef = entry.sourceRef,
        title = entry.title,
        publishedAt = entry.publishedAt,
        detectedAt = clock.instant(),
        detailUrl = entry.detailUrl,
        status = ReportStatus.BACKFILLED,
    )

    private companion object {
        const val DEFAULT_MONTHS = 13
        const val PROGRESS_EVERY_DAYS = 30
    }
}

private val COMBINING_MARKS = Regex("\\p{M}+")
private val MONTH_PERIOD = Regex(
    "\\b(za|w|we)\\s+(miesiac\\w*\\s+)?(styczen|stycznia|styczniu|luty|lutego|lutym|marzec|marca|marcu|kwiecien|kwietnia|kwietniu|" +
        "maj|maja|maju|czerwiec|czerwca|czerwcu|lipiec|lipca|lipcu|sierpien|sierpnia|sierpniu|wrzesien|wrzesnia|wrzesniu|" +
        "pazdziernik|pazdziernika|pazdzierniku|listopad|listopada|listopadzie|grudzien|grudnia|grudniu)\\b"
)
private val ASSET_SALE = Regex("akcj|udzial|obligacj|nieruchomosc|wierzytelnosc|zorganizowanej")

fun ListingEntry.isMonthlySales(): Boolean {
    val normalized = title?.withoutPolishMarks() ?: return false
    return kind == ReportKind.CURRENT && normalized.isAboutRevenue() && normalized.coversOneMonth()
}

private fun String.isAboutRevenue(): Boolean =
    ("przychod" in this || "sprzedaz" in this) && !ASSET_SALE.containsMatchIn(this)

private fun String.coversOneMonth(): Boolean = "miesieczn" in this || MONTH_PERIOD.containsMatchIn(this)

private fun String.withoutPolishMarks(): String =
    Normalizer.normalize(lowercase().replace('ł', 'l'), Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
