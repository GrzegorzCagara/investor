package pl.investor.history

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import pl.investor.support.toLocalDate
import java.sql.ResultSet
import java.time.LocalDate

@Repository
class QuoteRepository(private val jdbc: JdbcClient) {

    fun save(quote: DailyQuote) {
        jdbc.sql(
            """
            INSERT INTO daily_quote (instrument, date, close, volume, turnover)
            VALUES (:instrument, :date, :close, :volume, :turnover)
            ON CONFLICT (instrument, date) DO UPDATE SET
                close = EXCLUDED.close,
                volume = EXCLUDED.volume,
                turnover = EXCLUDED.turnover
            """
        )
            .param("instrument", quote.instrument)
            .param("date", quote.date)
            .param("close", quote.close)
            .param("volume", quote.volume)
            .param("turnover", quote.turnover)
            .update()
    }

    fun closesUpTo(instrument: String, date: LocalDate, sessions: Int): List<DailyQuote> =
        jdbc.sql(
            """
            SELECT * FROM daily_quote
            WHERE instrument = :instrument AND date <= :date
            ORDER BY date DESC
            LIMIT :sessions
            """
        )
            .param("instrument", instrument)
            .param("date", date)
            .param("sessions", sessions)
            .query { rs, _ -> rs.toDailyQuote() }
            .list()

    fun between(instrument: String, from: LocalDate, to: LocalDate): List<DailyQuote> =
        jdbc.sql(
            """
            SELECT * FROM daily_quote
            WHERE instrument = :instrument AND date BETWEEN :from AND :to
            ORDER BY date
            """
        )
            .param("instrument", instrument)
            .param("from", from)
            .param("to", to)
            .query { rs, _ -> rs.toDailyQuote() }
            .list()

    fun latestDate(instrument: String): LocalDate? =
        jdbc.sql("SELECT date FROM daily_quote WHERE instrument = :instrument ORDER BY date DESC LIMIT 1")
            .param("instrument", instrument)
            .query(LocalDate::class.java)
            .optional()
            .orElse(null)

    private fun ResultSet.toDailyQuote() = DailyQuote(
        instrument = getString("instrument"),
        date = checkNotNull(toLocalDate("date")),
        close = getBigDecimal("close"),
        volume = getLong("volume"),
        turnover = getBigDecimal("turnover"),
    )
}
