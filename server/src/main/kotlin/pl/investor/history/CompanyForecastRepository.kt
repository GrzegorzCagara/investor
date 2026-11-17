package pl.investor.history

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import pl.investor.support.getLongOrNull
import pl.investor.support.toInstant
import pl.investor.support.toLocalDate
import pl.investor.support.toUtc
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

@Repository
class CompanyForecastRepository(private val jdbc: JdbcClient) {

    fun save(forecast: CompanyForecast) {
        jdbc.sql(
            """
            INSERT INTO company_forecast (company_id, report_id, period_end, currency, revenue, operating_profit, net_profit, published_at)
            VALUES (:companyId, :reportId, :periodEnd, :currency, :revenue, :operatingProfit, :netProfit, :publishedAt)
            ON CONFLICT (report_id, period_end) DO UPDATE SET
                currency = EXCLUDED.currency,
                revenue = EXCLUDED.revenue,
                operating_profit = EXCLUDED.operating_profit,
                net_profit = EXCLUDED.net_profit
            """
        )
            .param("companyId", forecast.companyId)
            .param("reportId", forecast.reportId)
            .param("periodEnd", forecast.periodEnd)
            .param("currency", forecast.currency)
            .param("revenue", forecast.revenue)
            .param("operatingProfit", forecast.operatingProfit)
            .param("netProfit", forecast.netProfit)
            .param("publishedAt", forecast.publishedAt.toUtc())
            .update()
    }

    fun latestForYearOf(companyId: Long, periodEnd: LocalDate, publishedBefore: Instant): CompanyForecast? =
        jdbc.sql(
            """
            SELECT * FROM company_forecast
            WHERE company_id = :companyId AND period_end BETWEEN :from AND :to AND published_at < :publishedBefore
            ORDER BY period_end, published_at DESC
            LIMIT 1
            """
        )
            .param("companyId", companyId)
            .param("from", periodEnd)
            .param("to", YearMonth.from(periodEnd).plusMonths(MONTHS_TO_LAST_QUARTER).atEndOfMonth())
            .param("publishedBefore", publishedBefore.toUtc())
            .query { rs, _ -> rs.toCompanyForecast() }
            .optional()
            .orElse(null)

    private fun ResultSet.toCompanyForecast() = CompanyForecast(
        companyId = getLong("company_id"),
        periodEnd = checkNotNull(toLocalDate("period_end")),
        currency = getString("currency"),
        revenue = getBigDecimal("revenue"),
        operatingProfit = getBigDecimal("operating_profit"),
        netProfit = getBigDecimal("net_profit"),
        publishedAt = toInstant("published_at"),
        reportId = getLongOrNull("report_id"),
    )

    private companion object {
        const val MONTHS_TO_LAST_QUARTER = 9L
    }
}
