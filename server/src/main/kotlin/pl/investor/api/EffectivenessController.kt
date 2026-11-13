package pl.investor.api

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.investor.support.WARSAW
import java.time.Instant
import java.time.LocalDate

@RestController
@RequestMapping("/api/effectiveness")
class EffectivenessController(
    private val alerts: AlertRepository,
    @Value("\${investor.effectiveness-since}") countedSince: String,
) {

    private val since: Instant = LocalDate.parse(countedSince).atStartOfDay(WARSAW).toInstant()

    @GetMapping
    fun effectiveness(): EffectivenessResponse =
        EffectivenessCalculator.calculate(alerts.scoredOutcomes(since), alerts.signalOutcomes(since))
}
