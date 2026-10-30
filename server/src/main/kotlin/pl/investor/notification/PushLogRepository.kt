package pl.investor.notification

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import pl.investor.history.PeriodKind
import pl.investor.support.toUtc
import java.time.Instant
import java.time.LocalDate

@Repository
class PushLogRepository(private val jdbc: JdbcClient) {

    fun save(message: PushMessage, sentAt: Instant) {
        jdbc.sql(
            """
            INSERT INTO push_log (report_id, kind, title, body, sent_at, company_id, period_kind, period_end)
            VALUES (:reportId, :kind, :title, :body, :sentAt, :companyId, :periodKind, :periodEnd)
            """
        )
            .param("reportId", message.reportId)
            .param("kind", message.kind.name)
            .param("title", message.title)
            .param("body", message.body)
            .param("sentAt", sentAt.toUtc())
            .param("companyId", message.companyId)
            .param("periodKind", message.periodKind?.name)
            .param("periodEnd", message.periodEnd)
            .update()
    }

    fun resultsPushExists(companyId: Long, periodKind: PeriodKind, periodEnd: LocalDate): Boolean =
        jdbc.sql(
            """
            SELECT EXISTS (
                SELECT 1 FROM push_log WHERE kind = :kind AND company_id = :companyId AND period_kind = :periodKind AND period_end = :periodEnd
            )
            """
        )
            .param("kind", PushKind.RESULTS.name)
            .param("companyId", companyId)
            .param("periodKind", periodKind.name)
            .param("periodEnd", periodEnd)
            .query(Boolean::class.java)
            .single()

    fun medianResultsLatencySeconds(since: Instant): Long? =
        jdbc.sql(
            """
            SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (p.sent_at - r.published_at)))
            FROM push_log p JOIN report r ON r.id = p.report_id
            WHERE p.kind = :kind AND p.sent_at >= :since
            """
        )
            .param("kind", PushKind.RESULTS.name)
            .param("since", since.toUtc())
            .query { rs, _ -> rs.getDouble(1).takeUnless { rs.wasNull() } }
            .list()
            .firstOrNull()
            ?.let { Math.round(it) }
}
