package pl.investor.monitoring

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import pl.investor.source.Source
import pl.investor.support.toUtc
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime

@Repository
class SourceHealthRepository(private val jdbc: JdbcClient) {

    fun upsertSuccess(source: Source, at: Instant) {
        jdbc.sql(
            """
            INSERT INTO source_health (source, last_success_at, outage_notified)
            VALUES (:source, :at, FALSE)
            ON CONFLICT (source) DO UPDATE SET last_success_at = :at, outage_notified = FALSE
            """
        )
            .param("source", source.name)
            .param("at", at.toUtc())
            .update()
    }

    fun upsertFailure(source: Source, at: Instant, error: String) {
        jdbc.sql(
            """
            INSERT INTO source_health (source, last_failure_at, last_error)
            VALUES (:source, :at, :error)
            ON CONFLICT (source) DO UPDATE SET last_failure_at = :at, last_error = :error
            """
        )
            .param("source", source.name)
            .param("at", at.toUtc())
            .param("error", error)
            .update()
    }

    fun markOutageNotified(source: Source) {
        jdbc.sql(
            """
            INSERT INTO source_health (source, outage_notified) VALUES (:source, TRUE)
            ON CONFLICT (source) DO UPDATE SET outage_notified = TRUE
            """
        )
            .param("source", source.name)
            .update()
    }

    fun markOutageNotifiedIfUnchanged(source: Source, lastSuccessAt: Instant): Boolean =
        jdbc.sql(
            """
            UPDATE source_health SET outage_notified = TRUE
            WHERE source = :source AND last_success_at = :lastSuccessAt AND outage_notified = FALSE
            """
        )
            .param("source", source.name)
            .param("lastSuccessAt", lastSuccessAt.toUtc())
            .update() == 1

    fun findAll(): List<SourceHealth> =
        jdbc.sql("SELECT * FROM source_health").query { rs, _ -> rs.toSourceHealth() }.list()

    private fun ResultSet.toSourceHealth() = SourceHealth(
        source = Source.valueOf(getString("source")),
        lastSuccessAt = instantOrNull("last_success_at"),
        lastFailureAt = instantOrNull("last_failure_at"),
        lastError = getString("last_error"),
        outageNotified = getBoolean("outage_notified"),
    )

    private fun ResultSet.instantOrNull(column: String): Instant? =
        getObject(column, OffsetDateTime::class.java)?.toInstant()
}
