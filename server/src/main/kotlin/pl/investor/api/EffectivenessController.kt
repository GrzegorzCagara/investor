package pl.investor.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/effectiveness")
class EffectivenessController(private val alerts: AlertRepository) {

    @GetMapping
    fun effectiveness(): EffectivenessResponse = EffectivenessCalculator.calculate(alerts.scoredOutcomes())
}
