package pl.investor.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.investor.report.Market
import java.time.Clock
import java.time.Duration

@RestController
@RequestMapping("/api/ranking")
class RankingController(private val alerts: AlertRepository, private val assembler: AlertAssembler, private val clock: Clock) {

    @GetMapping
    fun ranking(@RequestParam(required = false) market: Market?): RankingResponse =
        RankingResponse(assembler.items(alerts.latestScoredPerCompany(clock.instant().minus(WINDOW), market, MAX_ITEMS)))

    private companion object {
        val WINDOW: Duration = Duration.ofDays(30)
        const val MAX_ITEMS = 100
    }
}
