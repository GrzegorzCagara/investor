package pl.investor.settings

import org.springframework.stereotype.Component
import pl.investor.support.WARSAW
import java.time.Instant
import java.time.LocalTime

data class QuietHoursConfig(val enabled: Boolean, val from: LocalTime, val to: LocalTime) {

    fun covers(time: LocalTime): Boolean = when {
        !enabled || from == to -> false
        from < to -> time >= from && time < to
        else -> time >= from || time < to
    }
}

@Component
class QuietHours(private val settings: SettingRepository) {

    fun current(): QuietHoursConfig = QuietHoursConfig(
        enabled = settings.find(ENABLED_KEY)?.toBooleanStrictOrNull() ?: DEFAULT.enabled,
        from = settings.find(FROM_KEY)?.toTimeOrNull() ?: DEFAULT.from,
        to = settings.find(TO_KEY)?.toTimeOrNull() ?: DEFAULT.to,
    )

    fun update(config: QuietHoursConfig) {
        settings.put(ENABLED_KEY, config.enabled.toString())
        settings.put(FROM_KEY, config.from.toString())
        settings.put(TO_KEY, config.to.toString())
    }

    fun isActive(at: Instant): Boolean = current().covers(at.atZone(WARSAW).toLocalTime())

    private fun String.toTimeOrNull(): LocalTime? = runCatching { LocalTime.parse(this) }.getOrNull()

    private companion object {
        const val ENABLED_KEY = "QUIET_HOURS_ENABLED"
        const val FROM_KEY = "QUIET_HOURS_FROM"
        const val TO_KEY = "QUIET_HOURS_TO"
        val DEFAULT = QuietHoursConfig(false, LocalTime.of(22, 0), LocalTime.of(7, 0))
    }
}
