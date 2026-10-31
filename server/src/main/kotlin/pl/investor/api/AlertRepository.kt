package pl.investor.api

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import pl.investor.outcome.Outcome
import pl.investor.report.EventType
import pl.investor.report.Market
import pl.investor.report.ReportKind
import pl.investor.report.ReportStatus
import pl.investor.support.toInstant
import pl.investor.support.toLocalDate
import pl.investor.support.toUtc
import java.sql.ResultSet
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

    fun latestScoredPerCompany(since: Instant, market: Market?, limit: Int): List<AlertRow> =
        jdbc.sql(
            """
            SELECT * FROM (
                SELECT DISTINCT ON (r.company_id) $COLUMNS
                $FROM
                WHERE $SCORED AND r.published_at >= :since AND (CAST(:market AS TEXT) IS NULL OR r.market = :market)
                ORDER BY r.company_id, r.published_at DESC, r.id DESC
            ) latest
            ORDER BY round(total::numeric) DESC, published_at DESC, id DESC
            LIMIT :limit
            """
        )
            .param("since", since.toUtc())
            .param("market", market?.name)
            .param("limit", limit)
            .query { rs, _ -> rs.toRow() }
            .list()

    fun scoredOutcomes(): List<ScoredOutcome> =
        jdbc.sql(
            """
            SELECT r.id, c.name, c.short_name, r.market, r.published_at, s.total,
                o.base_date, o.reaction_pct, o.reaction_index_pct, o.volume_multiple,
                o.return5_pct, o.index5_pct, o.return20_pct, o.index20_pct, o.return60_pct, o.index60_pct
            $FROM
            LEFT JOIN report_outcome o ON o.report_id = r.id
            WHERE $SCORED AND round(s.total::numeric) >= :minScore
            ORDER BY r.published_at DESC, r.id DESC
            """
        )
            .param("minScore", EffectivenessCalculator.MIN_SCORE)
            .query { rs, _ -> rs.toScoredOutcome() }
            .list()

    fun scoredBefore(companyId: Long, before: Instant, limit: Int): List<AlertRow> =
        jdbc.sql("$SELECT WHERE $SCORED AND r.company_id = :companyId AND r.published_at < :before ORDER BY r.published_at DESC, r.id DESC LIMIT :limit")
            .param("companyId", companyId)
            .param("before", before.toUtc())
            .param("limit", limit)
            .query { rs, _ -> rs.toRow() }
            .list()

    private fun ResultSet.toScoredOutcome() = ScoredOutcome(
        id = getLong("id"),
        companyName = getString("short_name") ?: getString("name"),
        market = Market.valueOf(getString("market")),
        eventType = EventType.RESULTS,
        publishedAt = toInstant("published_at"),
        total = getDouble("total"),
        outcome = toLocalDate("base_date")?.let { baseDate ->
            Outcome(
                baseDate = baseDate,
                reactionPct = doubleOrNull("reaction_pct"),
                reactionIndexPct = doubleOrNull("reaction_index_pct"),
                volumeMultiple = doubleOrNull("volume_multiple"),
                return5Pct = doubleOrNull("return5_pct"),
                index5Pct = doubleOrNull("index5_pct"),
                return20Pct = doubleOrNull("return20_pct"),
                index20Pct = doubleOrNull("index20_pct"),
                return60Pct = doubleOrNull("return60_pct"),
                index60Pct = doubleOrNull("index60_pct"),
            )
        },
    )

    private fun ResultSet.doubleOrNull(column: String): Double? = getDouble(column).takeUnless { wasNull() }

    private fun ResultSet.toRow() = AlertRow(
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
        const val COLUMNS = "r.id, r.company_id, c.name, c.short_name, r.market, r.number, r.kind, r.published_at, r.detail_url, r.status, s.total, s.reason"
        const val FROM = "FROM report r JOIN company c ON c.id = r.company_id LEFT JOIN score s ON s.report_id = r.id"
        const val SELECT = "SELECT $COLUMNS $FROM"
        const val SCORED = "r.status IN ('SCORED', 'PUSH_PENDING') AND s.total IS NOT NULL"
    }
}
