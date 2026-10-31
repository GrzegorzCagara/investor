package pl.investor.history

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.support.PostgresTestBase
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals

class QuoteRepositoryTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var quotes: QuoteRepository

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE daily_quote").update()
    }

    @Test
    fun `between returns quotes of one instrument in the inclusive range ascending`() {
        listOf("2026-08-07", "2026-08-03", "2026-08-05", "2026-08-10").forEach { quotes.save(quote("WIG", it)) }
        quotes.save(quote("NCINDEX", "2026-08-05"))

        val rows = quotes.between("WIG", LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-07"))

        assertEquals(listOf("2026-08-03", "2026-08-05", "2026-08-07").map(LocalDate::parse), rows.map { it.date })
    }

    private fun quote(instrument: String, date: String) =
        DailyQuote(instrument, LocalDate.parse(date), BigDecimal("100.0000"), 10, null)
}
