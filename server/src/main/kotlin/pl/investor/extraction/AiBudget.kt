package pl.investor.extraction

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.YearMonth

@Component
class AiBudget(private val repo: AiUsageRepository, private val clock: Clock) {

    fun canSpend(pool: AiPool): Boolean = spentUsd(pool, currentMonth()) < pool.limitUsd

    fun record(pool: AiPool, inputTokens: Long, outputTokens: Long) {
        val cost = INPUT_USD_PER_TOKEN * BigDecimal(inputTokens) + OUTPUT_USD_PER_TOKEN * BigDecimal(outputTokens)
        repo.add(currentMonth().atDay(1), pool, inputTokens, outputTokens, cost)
    }

    fun spentUsd(pool: AiPool, month: YearMonth): BigDecimal = repo.costUsd(month.atDay(1), pool)

    private fun currentMonth(): YearMonth = YearMonth.now(clock)

    private companion object {
        val INPUT_USD_PER_TOKEN = BigDecimal("0.10").divide(BigDecimal(1_000_000))
        val OUTPUT_USD_PER_TOKEN = BigDecimal("0.50").divide(BigDecimal(1_000_000))
    }
}
