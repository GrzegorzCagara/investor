package pl.investor.outcome

import pl.investor.history.DailyQuote
import pl.investor.history.QuoteRepository
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
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

class OutcomeTestData(
    private val companies: CompanyRepository,
    private val reports: ReportRepository,
    private val scores: ScoreRepository,
    private val quotes: QuoteRepository,
) {

    fun company(name: String, isin: String?, market: Market): Long =
        checkNotNull(companies.save(Company(null, name, isin, market, null, null, false)).id)

    fun report(companyId: Long, market: Market, publishedAt: Instant, status: ReportStatus = ReportStatus.SCORED, scored: Boolean = true): Long {
        val id = checkNotNull(
            reports.saveIfNew(
                Report(
                    null, companyId, market, ReportKind.QUARTERLY, "1/2026", "ref-$companyId-$publishedAt", "Raport",
                    publishedAt, publishedAt, "https://example.test/$companyId", status,
                )
            )?.id
        )
        if (scored) scores.save(id, Score(75.0, ScoreComponents(10.0, 10.0, 10.0, 10.0, 10.0, 25.0), 0.0, emptySet()), "Powód.", publishedAt)
        return id
    }

    fun quotes(instrument: String, from: String, to: String, volume: (LocalDate) -> Long = { 100 }, close: (LocalDate) -> Double = { 100.0 }) {
        weekdays(from, to).forEach { quotes.save(DailyQuote(instrument, it, BigDecimal.valueOf(close(it)), volume(it), null)) }
    }

    private fun weekdays(from: String, to: String): List<LocalDate> =
        generateSequence(LocalDate.parse(from)) { it.plusDays(1) }
            .takeWhile { !it.isAfter(LocalDate.parse(to)) }
            .filterNot { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY }
            .toList()
}
