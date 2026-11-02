package pl.investor.news

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import pl.investor.support.toUtc
import java.time.Instant

@Repository
class NewsAssessmentRepository(private val jdbc: JdbcClient) {

    fun save(reportId: Long, assessment: NewsAssessment, createdAt: Instant) {
        jdbc.sql(
            """
            INSERT INTO news_assessment (report_id, category, direction, impact, reason, contract_value, contract_currency, revenue_share_pct, created_at)
            VALUES (:reportId, :category, :direction, :impact, :reason, :contractValue, :contractCurrency, :revenueSharePct, :createdAt)
            ON CONFLICT (report_id) DO UPDATE SET
                category = EXCLUDED.category,
                direction = EXCLUDED.direction,
                impact = EXCLUDED.impact,
                reason = EXCLUDED.reason,
                contract_value = EXCLUDED.contract_value,
                contract_currency = EXCLUDED.contract_currency,
                revenue_share_pct = EXCLUDED.revenue_share_pct,
                created_at = EXCLUDED.created_at
            """
        )
            .param("reportId", reportId)
            .param("category", assessment.category.name)
            .param("direction", assessment.direction.name)
            .param("impact", assessment.impact)
            .param("reason", assessment.reason)
            .param("contractValue", assessment.contractValue)
            .param("contractCurrency", assessment.contractCurrency?.name)
            .param("revenueSharePct", assessment.revenueSharePct)
            .param("createdAt", createdAt.toUtc())
            .update()
    }

    fun find(reportId: Long): NewsAssessment? =
        jdbc.sql("SELECT * FROM news_assessment WHERE report_id = :reportId")
            .param("reportId", reportId)
            .query { rs, _ ->
                NewsAssessment(
                    category = NewsCategory.valueOf(rs.getString("category")),
                    direction = NewsDirection.valueOf(rs.getString("direction")),
                    impact = rs.getDouble("impact"),
                    reason = rs.getString("reason"),
                    contractValue = rs.getBigDecimal("contract_value"),
                    contractCurrency = rs.getString("contract_currency")?.let(ContractCurrency::valueOf),
                    revenueSharePct = rs.getDouble("revenue_share_pct").takeUnless { rs.wasNull() },
                )
            }
            .optional()
            .orElse(null)
}
