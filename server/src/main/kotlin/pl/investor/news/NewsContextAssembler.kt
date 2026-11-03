package pl.investor.news

import org.springframework.stereotype.Component
import pl.investor.history.QuarterResult
import pl.investor.history.QuarterResultRepository
import pl.investor.history.QuoteRepository
import pl.investor.history.TRAILING_YEAR_QUARTERS
import pl.investor.history.ValuationRepository
import pl.investor.history.trailingYearNetProfit
import pl.investor.history.trailingYearRevenue
import pl.investor.report.Company
import pl.investor.report.Report
import pl.investor.report.ReportRepository
import pl.investor.scoring.Warning
import pl.investor.source.content.ReportContent
import pl.investor.support.toWarsawDate
import java.math.BigDecimal
import java.math.MathContext
import java.time.Duration

@Component
class NewsContextAssembler(
    private val quarterResults: QuarterResultRepository,
    private val valuations: ValuationRepository,
    private val quotes: QuoteRepository,
    private val reports: ReportRepository,
) {

    fun input(company: Company, report: Report, title: String, content: ReportContent): NewsInput {
        val quarters = quarterResults.publishedBefore(checkNotNull(company.id), report.publishedAt.toWarsawDate().plusDays(1), TRAILING_YEAR_QUARTERS)
        val revenue = quarters.trailingYearRevenue()
        return NewsInput(
            companyName = company.name,
            market = report.market,
            title = title,
            content = content,
            trailingRevenue = revenue,
            trailingNetProfit = quarters.trailingYearNetProfit(),
            marketCap = lastClose(company, report)?.let { close -> marketCap(company, quarters, revenue, close) },
            earlierReports = earlierReports(company, report),
        )
    }

    fun warnings(company: Company, report: Report): Set<Warning> =
        if (lastClose(company, report)?.let { it < PENNY_STOCK_PRICE } == true) setOf(Warning.PENNY_STOCK) else emptySet()

    private fun lastClose(company: Company, report: Report): BigDecimal? {
        val isin = company.isin?.takeIf { company.isQuotedInMarketCurrency() } ?: return null
        val dayBefore = report.publishedAt.toWarsawDate().minusDays(1)
        return quotes.closesUpTo(isin, dayBefore, 1).firstOrNull()
            ?.takeIf { it.date.isAfter(dayBefore.minusDays(MAX_QUOTE_AGE_DAYS)) }
            ?.close
    }

    private fun marketCap(company: Company, quarters: List<QuarterResult>, trailingRevenue: BigDecimal?, close: BigDecimal): BigDecimal? {
        val latest = quarters.maxByOrNull { it.quarterEnd } ?: return null
        val point = valuations.forQuarters(checkNotNull(company.id), listOf(latest.quarterEnd)).firstOrNull() ?: return null
        point.shares?.let { return BigDecimal(it).multiply(close).divide(THOUSAND, MathContext.DECIMAL64) }
        val ps = point.ps ?: return null
        val price = point.price?.takeIf { it.signum() > 0 } ?: return null
        val revenue = trailingRevenue?.takeIf { it.signum() > 0 } ?: return null
        return ps.multiply(revenue).multiply(close).divide(price, MathContext.DECIMAL64)
    }

    private fun earlierReports(company: Company, report: Report): List<EarlierReport> =
        reports.relevantBetween(
            checkNotNull(company.id),
            report.publishedAt.minus(Duration.ofDays(NewsPrompt.EARLIER_REPORTS_DAYS)),
            report.publishedAt,
            NewsPrompt.EARLIER_REPORTS_LIMIT,
        ).map { EarlierReport(it.publishedAt.toWarsawDate(), checkNotNull(it.title)) }

    private companion object {
        const val MAX_QUOTE_AGE_DAYS = 14L
        val PENNY_STOCK_PRICE = BigDecimal.ONE
        val THOUSAND = BigDecimal(1000)
    }
}
