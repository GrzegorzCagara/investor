package pl.investor.api

import org.springframework.http.HttpStatus
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import pl.investor.settings.AlertFilters
import pl.investor.settings.PushThreshold
import pl.investor.settings.QuietHours
import pl.investor.settings.QuietHoursConfig
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import kotlin.math.roundToInt

@RestController
@RequestMapping("/api/settings")
class SettingsController(
    private val pushThreshold: PushThreshold,
    private val alertFilters: AlertFilters,
    private val quietHours: QuietHours,
    private val transaction: TransactionTemplate,
) {

    @GetMapping
    fun settings(): SettingsView = SettingsView(
        pushThreshold = pushThreshold.current().roundToInt(),
        disabledMarkets = alertFilters.disabledMarkets(),
        disabledEventTypes = alertFilters.disabledEventTypes(),
        quietHours = quietHours.current().toView(),
    )

    @PutMapping
    fun update(@RequestBody request: SettingsRequest): SettingsView {
        val threshold = request.pushThreshold.toThreshold()
        val markets = request.disabledMarkets.withoutNulls("disabledMarkets")
        val eventTypes = request.disabledEventTypes.withoutNulls("disabledEventTypes")
        val quiet = request.quietHours.toConfig()
        transaction.executeWithoutResult {
            pushThreshold.update(threshold)
            alertFilters.update(markets, eventTypes)
            quietHours.update(quiet)
        }
        return settings()
    }

    private fun Double?.toThreshold(): Int {
        val value = this ?: throw badRequest("pushThreshold is required")
        if (value % 1.0 != 0.0 || value.toInt() !in PushThreshold.RANGE) throw badRequest("pushThreshold must be a whole number within ${PushThreshold.RANGE}")
        return value.toInt()
    }

    private fun <T : Any> List<T?>?.withoutNulls(field: String): List<T> {
        val values = this ?: throw badRequest("$field is required")
        return values.map { it ?: throw badRequest("$field must not contain null") }
    }

    private fun QuietHoursConfig.toView() = QuietHoursView(enabled, from.format(TIME_FORMAT), to.format(TIME_FORMAT))

    private fun QuietHoursRequest?.toConfig(): QuietHoursConfig {
        val request = this ?: throw badRequest("quietHours is required")
        return QuietHoursConfig(
            enabled = request.enabled ?: throw badRequest("quietHours.enabled is required"),
            from = request.from.toTime("quietHours.from"),
            to = request.to.toTime("quietHours.to"),
        )
    }

    private fun String?.toTime(field: String): LocalTime {
        val text = this ?: throw badRequest("$field is required")
        return try {
            LocalTime.parse(text, TIME_FORMAT)
        } catch (e: DateTimeParseException) {
            throw badRequest("$field must be HH:mm, got $text")
        }
    }

    private fun badRequest(reason: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, reason)

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)
    }
}
