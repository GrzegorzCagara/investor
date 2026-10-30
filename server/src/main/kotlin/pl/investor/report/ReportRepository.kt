package pl.investor.report

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import pl.investor.support.toInstant
import pl.investor.support.toUtc
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime

@Repository
class ReportRepository(private val jdbc: JdbcClient) {

    fun saveIfNew(report: Report): Report? =
        jdbc.sql(
            """
            INSERT INTO report (company_id, market, kind, number, source_ref, title, published_at, detected_at, detail_url, status, status_changed_at)
            VALUES (:companyId, :market, :kind, :number, :sourceRef, :title, :publishedAt, :detectedAt, :detailUrl, :status, :detectedAt)
            ON CONFLICT (market, source_ref) DO NOTHING
            RETURNING id
            """
        )
            .param("companyId", report.companyId)
            .param("market", report.market.name)
            .param("kind", report.kind.name)
            .param("number", report.number)
            .param("sourceRef", report.sourceRef)
            .param("title", report.title)
            .param("publishedAt", report.publishedAt.toUtc())
            .param("detectedAt", report.detectedAt.toUtc())
            .param("detailUrl", report.detailUrl)
            .param("status", report.status.name)
            .query(Long::class.java)
            .optional()
            .map { report.copy(id = it) }
            .orElse(null)

    fun existsBySourceRef(market: Market, sourceRef: String): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM report WHERE market = :market AND source_ref = :sourceRef)")
            .param("market", market.name)
            .param("sourceRef", sourceRef)
            .query(Boolean::class.java)
            .single()

    fun updateStatus(id: Long, status: ReportStatus, at: Instant) {
        jdbc.sql(
            """
            UPDATE report SET
                status_changed_at = CASE WHEN status = :status THEN status_changed_at ELSE :at END,
                status = :status
            WHERE id = :id
            """
        )
            .param("status", status.name)
            .param("at", at.toUtc())
            .param("id", id)
            .update()
    }

    fun markAttempt(id: Long, at: Instant) {
        jdbc.sql("UPDATE report SET last_attempt_at = :at WHERE id = :id")
            .param("at", at.toUtc())
            .param("id", id)
            .update()
    }

    fun findPendingData(attemptedBefore: Instant, limit: Int): List<Report> =
        jdbc.sql(
            """
            SELECT * FROM report
            WHERE status = 'PENDING_DATA' AND (last_attempt_at IS NULL OR last_attempt_at <= :attemptedBefore)
            ORDER BY last_attempt_at NULLS FIRST, detected_at
            LIMIT :limit
            """
        )
            .param("attemptedBefore", attemptedBefore.toUtc())
            .param("limit", limit)
            .query { rs, _ -> rs.toReport() }
            .list()

    fun findByStatus(status: ReportStatus, limit: Int): List<Report> =
        jdbc.sql("SELECT * FROM report WHERE status = :status ORDER BY detected_at LIMIT :limit")
            .param("status", status.name)
            .param("limit", limit)
            .query { rs, _ -> rs.toReport() }
            .list()

    fun findById(id: Long): Report =
        jdbc.sql("SELECT * FROM report WHERE id = :id")
            .param("id", id)
            .query { rs, _ -> rs.toReport() }
            .single()

    private fun ResultSet.toReport() = Report(
        id = getLong("id"),
        companyId = getLong("company_id"),
        market = Market.valueOf(getString("market")),
        kind = ReportKind.valueOf(getString("kind")),
        number = getString("number"),
        sourceRef = getString("source_ref"),
        title = getString("title"),
        publishedAt = toInstant("published_at"),
        detectedAt = toInstant("detected_at"),
        detailUrl = getString("detail_url"),
        status = ReportStatus.valueOf(getString("status")),
        statusChangedAt = toInstant("status_changed_at"),
        lastAttemptAt = getObject("last_attempt_at", OffsetDateTime::class.java)?.toInstant(),
    )
}
