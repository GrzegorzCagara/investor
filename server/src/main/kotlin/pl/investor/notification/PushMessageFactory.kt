package pl.investor.notification

import org.springframework.stereotype.Component
import pl.investor.extraction.AiPool
import pl.investor.report.Company
import pl.investor.report.Market
import pl.investor.scoring.Score
import pl.investor.scoring.displayScore
import pl.investor.source.Source
import pl.investor.support.WARSAW
import java.time.Instant
import java.time.format.DateTimeFormatter

@Component
class PushMessageFactory {

    fun forResults(company: Company, market: Market, score: Score, reason: String, alertId: Long) = PushMessage(
        title = "${company.shortName ?: company.name} (${market.name}) · WYNIKI · ocena ${score.displayScore()}",
        body = reason.firstSentence().take(BODY_LIMIT),
        data = mapOf("alertId" to alertId.toString()),
        kind = PushKind.RESULTS,
        reportId = alertId,
    )

    fun forNews(company: Company, market: Market, score: Score, reason: String, alertId: Long) = PushMessage(
        title = "${company.shortName ?: company.name} (${market.name}) · WYDARZENIE · ocena ${score.displayScore()}",
        body = reason.firstSentence().take(BODY_LIMIT),
        data = mapOf("alertId" to alertId.toString()),
        kind = PushKind.NEWS,
        reportId = alertId,
        companyId = company.id,
    )

    fun forSourceOutage(source: Source, since: Instant) = PushMessage(
        title = "Źródło ${source.name} nie działa",
        body = "Brak danych od ${since.atZone(WARSAW).format(HOUR_MINUTE)}",
        data = mapOf("source" to source.name),
        kind = PushKind.SOURCE_OUTAGE,
        reportId = null,
    )

    fun forAiBudgetExhausted(pool: AiPool) = PushMessage(
        title = "Limit AI wyczerpany",
        body = pool.budgetExhaustedBody(),
        data = mapOf("pool" to pool.name),
        kind = PushKind.AI_BUDGET,
        reportId = null,
    )

    fun forAiKeyRejected() = PushMessage(
        title = "Claude odrzuca klucz API",
        body = "Sprawdź ANTHROPIC_API_KEY w .env i limit w Console. Raporty czekają do 2 godzin.",
        data = emptyMap(),
        kind = PushKind.AI_AUTH,
        reportId = null,
    )

    private fun AiPool.budgetExhaustedBody() = when (this) {
        AiPool.GPW -> "Do końca miesiąca raporty GPW/NC są oceniane bez AI"
        AiPool.US -> "Do końca miesiąca raporty USA są oceniane bez AI"
        AiPool.NEWS -> "Do końca miesiąca wydarzenia nie są oceniane"
        AiPool.SE -> "Do końca miesiąca raporty ze Szwecji są oceniane bez AI"
        AiPool.SE_NEWS -> "Do końca miesiąca wydarzenia ze Szwecji nie są oceniane"
        AiPool.DE -> "Do końca miesiąca raporty z Niemiec są oceniane bez AI"
        AiPool.DE_NEWS -> "Do końca miesiąca wydarzenia z Niemiec nie są oceniane"
    }

    private companion object {
        const val BODY_LIMIT = 180
        val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

private val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+(?=\\p{Lu})")

fun String.firstSentence(): String = trim().split(SENTENCE_BOUNDARY, limit = 2).first()
