package pl.investor.api

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import pl.investor.report.Market
import pl.investor.report.ReportKind
import pl.investor.report.ReportStatus
import pl.investor.support.toInstant
import java.time.Instant

data class AlertRow(
    val id: Long,
    val companyId: Long,
    val companyName: String,
    val market: Market,
    val reportNumber: String,
    val reportKind: ReportKind,
    val publishedAt: Instant,
    val detailUrl: String,
    val status: ReportStatus,
    val total: Double?,
    val reason: String?,
)

@Repository
class AlertRepository(private val jdbc: JdbcClient) {

    fun newest(limit: Int): List<AlertRow> =
        jdbc.sql("$SELECT WHERE r.status IN ($LISTED) ORDER BY r.published_at DESC, r.id DESC LIMIT :limit")
            .param("limit", limit)
            .query { rs, _ -> rs.toRow() }
            .list()

    fun find(id: Long): AlertRow? =
        jdbc.sql("$SELECT WHERE r.id = :id AND r.status IN ($LISTED)")
            .param("id", id)
            .query { rs, _ -> rs.toRow() }
            .optional()
            .orElse(null)

    private fun java.sql.ResultSet.toRow() = AlertRow(
        id = getLong("id"),
        companyId = getLong("company_id"),
        companyName = getString("short_name") ?: getString("name"),
        market = Market.valueOf(getString("market")),
        reportNumber = getString("number"),
        reportKind = ReportKind.valueOf(getString("kind")),
        publishedAt = toInstant("published_at"),
        detailUrl = getString("detail_url"),
        status = ReportStatus.valueOf(getString("status")),
        total = getDouble("total").takeUnless { wasNull() },
        reason = getString("reason"),
    )

    private companion object {
        val LISTED_STATUSES = listOf(
            ReportStatus.SCORED,
            ReportStatus.PUSH_PENDING,
            ReportStatus.UNSCORED_NO_AI,
            ReportStatus.UNSCORED_NO_DATA,
            ReportStatus.PENDING_ATTACHMENTS,
            ReportStatus.PENDING_AI,
            ReportStatus.PENDING_DATA,
        )
        val LISTED = LISTED_STATUSES.joinToString(",") { "'${it.name}'" }
        const val SELECT = """
            SELECT r.id, r.company_id, c.name, c.short_name, r.market, r.number, r.kind, r.published_at, r.detail_url, r.status, s.total, s.reason
            FROM report r JOIN company c ON c.id = r.company_id LEFT JOIN score s ON s.report_id = r.id
        """
    }
}
