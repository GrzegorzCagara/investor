package pl.investor.scoring

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import pl.investor.support.toUtc
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant

@Repository
class ScoreRepository(private val jdbc: JdbcClient) {

    private val mapper = jacksonObjectMapper()
    private val log = LoggerFactory.getLogger(ScoreRepository::class.java)

    fun save(reportId: Long, score: Score, reason: String, createdAt: Instant) {
        jdbc.sql(
            """
            INSERT INTO score (report_id, total, components, penalty, warnings, signals, context, reason, created_at)
            VALUES (:reportId, :total, CAST(:components AS jsonb), :penalty, string_to_array(:warnings, ','),
                string_to_array(:signals, ','), CAST(:context AS jsonb), :reason, :createdAt)
            ON CONFLICT (report_id) DO UPDATE SET
                total = EXCLUDED.total,
                components = EXCLUDED.components,
                penalty = EXCLUDED.penalty,
                warnings = EXCLUDED.warnings,
                signals = EXCLUDED.signals,
                context = EXCLUDED.context,
                reason = EXCLUDED.reason,
                created_at = EXCLUDED.created_at
            """
        )
            .param("reportId", reportId)
            .param("total", score.total)
            .param("components", mapper.writeValueAsString(score.components))
            .param("penalty", score.penalty)
            .param("warnings", score.warnings.joinToString(",") { it.name })
            .param("signals", score.signals.joinToString(",") { it.name })
            .param("context", mapper.writeValueAsString(score.context))
            .param("reason", reason)
            .param("createdAt", createdAt.toUtc())
            .update()
    }

    fun find(reportId: Long): StoredScore? =
        jdbc.sql(
            """
            SELECT total, components::text AS components, penalty, array_to_string(warnings, ',') AS warnings,
                array_to_string(signals, ',') AS signals, context::text AS context, reason
            FROM score WHERE report_id = :reportId
            """
        )
            .param("reportId", reportId)
            .query { rs, _ ->
                StoredScore(
                    Score(
                        total = rs.getDouble("total"),
                        components = mapper.readValue<ScoreComponents>(rs.getString("components")),
                        penalty = rs.getDouble("penalty"),
                        warnings = rs.getString("warnings").split(",").filter { it.isNotEmpty() }.map(Warning::valueOf).toSet(),
                        signals = signalsOf(reportId, rs.getString("signals")),
                        context = contextOf(reportId, rs.getString("context")),
                    ),
                    rs.getString("reason"),
                )
            }
            .optional()
            .orElse(null)

    private fun signalsOf(reportId: Long, stored: String): Set<Signal> =
        stored.split(",").filter { it.isNotEmpty() }.mapNotNull { name ->
            Signal.entries.firstOrNull { it.name == name }.also { if (it == null) log.warn("Score of report {} has an unknown signal {}", reportId, name) }
        }.toSet()

    private fun contextOf(reportId: Long, stored: String?): ScoreContext =
        stored?.let { json ->
            runCatching { mapper.readValue<ScoreContext>(json) }
                .onFailure { log.warn("Score context of report {} is unreadable: {}", reportId, it.message) }
                .getOrNull()
        } ?: ScoreContext.EMPTY

    fun totalOf(reportId: Long): Double =
        jdbc.sql("SELECT total FROM score WHERE report_id = :reportId")
            .param("reportId", reportId)
            .query(Double::class.java)
            .single()
}

data class StoredScore(val score: Score, val reason: String)
