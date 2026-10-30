package pl.investor.extraction

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate

@Repository
class AiUsageRepository(private val jdbc: JdbcClient) {

    fun add(month: LocalDate, pool: AiPool, inputTokens: Long, outputTokens: Long, costUsd: BigDecimal) {
        jdbc.sql(
            """
            INSERT INTO ai_usage (month, pool, input_tokens, output_tokens, cost_usd)
            VALUES (:month, :pool, :input, :output, :cost)
            ON CONFLICT (month, pool) DO UPDATE SET
                input_tokens = ai_usage.input_tokens + :input,
                output_tokens = ai_usage.output_tokens + :output,
                cost_usd = ai_usage.cost_usd + :cost
            """.trimIndent(),
        )
            .param("month", month)
            .param("pool", pool.name)
            .param("input", inputTokens)
            .param("output", outputTokens)
            .param("cost", costUsd)
            .update()
    }

    fun costUsd(month: LocalDate, pool: AiPool): BigDecimal =
        jdbc.sql("SELECT cost_usd FROM ai_usage WHERE month = :month AND pool = :pool")
            .param("month", month)
            .param("pool", pool.name)
            .query(BigDecimal::class.java)
            .optional()
            .orElse(BigDecimal.ZERO)
}
