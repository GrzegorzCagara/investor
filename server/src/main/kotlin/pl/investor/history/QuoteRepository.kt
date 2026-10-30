package pl.investor.history

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import pl.investor.support.toLocalDate
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
            .query { rs, _ ->
                DailyQuote(
                    instrument = rs.getString("instrument"),
                    date = rs.toLocalDate("date")!!,
                    close = rs.getBigDecimal("close"),
                    volume = rs.getLong("volume"),
                    turnover = rs.getBigDecimal("turnover"),
                )
            }
            .list()
}
