package pl.investor.settings

import org.springframework.stereotype.Component

@Component
class PushThreshold(private val settings: SettingRepository) {

    fun current(): Double = settings.find(KEY)?.toDoubleOrNull() ?: DEFAULT

    fun update(value: Int) {
        require(value in RANGE) { "Push threshold must be within $RANGE, got $value" }
        settings.put(KEY, value.toString())
    }

    companion object {
        val RANGE = 0..100
        private const val KEY = "PUSH_THRESHOLD"
        private const val DEFAULT = 70.0
    }
}
