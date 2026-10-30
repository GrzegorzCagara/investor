package pl.investor.extraction

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.support.PostgresTestBase
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiBudgetTest : PostgresTestBase() {

    @Autowired lateinit var repo: AiUsageRepository
    @Autowired lateinit var jdbc: JdbcClient

    @BeforeEach
    fun clean() {
        jdbc.sql("DELETE FROM ai_usage").update()
    }

    private fun budgetAt(instant: String) = AiBudget(repo, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC))

    @Test
    fun `budget blocks the GPW pool after 1,90 USD and leaves US open`() {
        val budget = budgetAt("2026-10-15T10:00:00Z")

        assertTrue(budget.canSpend(AiPool.GPW))
        budget.record(AiPool.GPW, inputTokens = 19_000_000, outputTokens = 0)

        assertFalse(budget.canSpend(AiPool.GPW))
        assertTrue(budget.canSpend(AiPool.US))
        assertEquals(0, BigDecimal("1.90").compareTo(budget.spentUsd(AiPool.GPW, YearMonth.of(2026, 10))))
    }

    @Test
    fun `output tokens cost five times more than input tokens`() {
        val budget = budgetAt("2026-10-15T10:00:00Z")

        budget.record(AiPool.US, inputTokens = 1_000_000, outputTokens = 1_000_000)

        assertEquals(0, BigDecimal("0.60").compareTo(budget.spentUsd(AiPool.US, YearMonth.of(2026, 10))))
    }

    @Test
    fun `budget resets in a new calendar month`() {
        budgetAt("2026-10-31T23:59:59Z").record(AiPool.GPW, inputTokens = 19_000_000, outputTokens = 0)

        val november = budgetAt("2026-11-01T00:00:01Z")

        assertTrue(november.canSpend(AiPool.GPW))
        assertEquals(0, BigDecimal.ZERO.compareTo(november.spentUsd(AiPool.GPW, YearMonth.of(2026, 11))))
    }
}
