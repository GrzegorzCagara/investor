package pl.investor.settings

import org.springframework.stereotype.Component

@Component
class PushThreshold(private val settings: SettingRepository) {

    fun current(): Double = settings.find(KEY)?.toDoubleOrNull() ?: DEFAULT

    private companion object {
        const val KEY = "PUSH_THRESHOLD"
        const val DEFAULT = 70.0
    }
}
