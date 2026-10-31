package pl.investor.api

import com.google.firebase.messaging.FirebaseMessaging
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.investor.extraction.AiBudget
import pl.investor.extraction.AiPool
import pl.investor.history.QuoteRepository
import pl.investor.history.SESSION_CALENDAR
import pl.investor.monitoring.SourceHealthTracker
import pl.investor.notification.PushLogRepository
import java.time.Clock
import java.time.Duration
import java.time.YearMonth

@RestController
@RequestMapping("/api/status")
class StatusController(
    private val health: SourceHealthTracker,
    private val budget: AiBudget,
    private val pushLog: PushLogRepository,
    private val firebase: ObjectProvider<FirebaseMessaging>,
    private val quotes: QuoteRepository,
    private val clock: Clock,
) {

    @GetMapping
    fun status(): StatusResponse {
        val month = YearMonth.now(clock)
        return StatusResponse(
            sources = health.snapshot().map { it.toView() },
            aiSpentUsd = AiPool.entries.associateWith { budget.spentUsd(it, month) },
            aiLimitUsd = AiPool.entries.associateWith { it.limitUsd },
            medianLatencySeconds7d = pushLog.medianResultsLatencySeconds(clock.instant().minus(LATENCY_WINDOW)),
            fcmConfigured = firebase.ifAvailable != null,
            latestQuoteDate = quotes.latestDate(SESSION_CALENDAR),
        )
    }

    private companion object {
        val LATENCY_WINDOW: Duration = Duration.ofDays(7)
    }
}
