package pl.investor.api

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/alerts")
class AlertController(private val alerts: AlertRepository, private val assembler: AlertAssembler) {

    @GetMapping
    fun list(@RequestParam(defaultValue = "100") limit: Int): AlertsResponse =
        AlertsResponse(assembler.items(alerts.newest(limit.coerceIn(1, MAX_LIMIT))))

    @GetMapping("/{id}")
    fun detail(@PathVariable id: Long): AlertDetail =
        alerts.find(id)?.let(assembler::detail) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    private companion object {
        const val MAX_LIMIT = 500
    }
}
