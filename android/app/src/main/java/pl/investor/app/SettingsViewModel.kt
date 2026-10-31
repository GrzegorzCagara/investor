package pl.investor.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.time.Clock
import java.time.Instant

data class SettingsForm(
    val threshold: String,
    val enabledMarkets: Set<Market>,
    val resultsEnabled: Boolean,
    val quietEnabled: Boolean,
    val quietFrom: String,
    val quietTo: String,
) {
    val thresholdError: String?
        get() = if (threshold.trim().toIntOrNull()?.takeIf { it in THRESHOLD_RANGE } == null) THRESHOLD_ERROR else null

    val quietFromError: String?
        get() = timeError(quietFrom)

    val quietToError: String?
        get() = timeError(quietTo)

    val valid: Boolean
        get() = thresholdError == null && quietFromError == null && quietToError == null

    fun toView(): SettingsView = SettingsView(
        pushThreshold = threshold.trim().toInt(),
        disabledMarkets = Market.entries.filterNot { it in enabledMarkets },
        disabledEventTypes = if (resultsEnabled) emptyList() else listOf(EventType.RESULTS),
        quietHours = QuietHoursView(quietEnabled, quietFrom.trim(), quietTo.trim()),
    )

    private fun timeError(value: String): String? = if (TIME.matches(value.trim())) null else TIME_ERROR

    companion object {
        val THRESHOLD_RANGE = 0..100
        const val THRESHOLD_ERROR = "Wpisz liczbę od 0 do 100"
        const val TIME_ERROR = "Wpisz godzinę jako GG:MM, np. 22:00"
        private val TIME = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")

        fun from(view: SettingsView) = SettingsForm(
            threshold = view.pushThreshold.toString(),
            enabledMarkets = Market.entries.filterNot { it in view.disabledMarkets }.toSet(),
            resultsEnabled = EventType.RESULTS !in view.disabledEventTypes,
            quietEnabled = view.quietHours.enabled,
            quietFrom = view.quietHours.from,
            quietTo = view.quietHours.to,
        )
    }
}

data class SourceRow(val label: String, val lastRead: String, val failing: Boolean)

data class AiUsageRow(val label: String, val usage: String)

data class SettingsUiState(
    val loading: Boolean = true,
    val form: SettingsForm? = null,
    val loadError: String? = null,
    val saving: Boolean = false,
    val saveMessage: String? = null,
    val saveError: String? = null,
    val sources: List<SourceRow> = emptyList(),
    val aiUsage: List<AiUsageRow> = emptyList(),
    val latestQuoteDate: String? = null,
    val statusError: String? = null,
)

class SettingsViewModel(private val api: InvestorApi, private val clock: Clock) : ViewModel() {

    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        mutableState.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            try {
                val settings = api.settings()
                mutableState.update { it.copy(loading = false, form = SettingsForm.from(settings)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(loading = false, loadError = LOAD_ERROR) }
            }
            loadStatus()
        }
    }

    fun refreshStatus() {
        viewModelScope.launch { loadStatus() }
    }

    fun onThresholdChange(value: String) = edit { it.copy(threshold = value) }

    fun onMarketChange(market: Market, enabled: Boolean) =
        edit { it.copy(enabledMarkets = if (enabled) it.enabledMarkets + market else it.enabledMarkets - market) }

    fun onResultsChange(enabled: Boolean) = edit { it.copy(resultsEnabled = enabled) }

    fun onQuietEnabledChange(enabled: Boolean) = edit { it.copy(quietEnabled = enabled) }

    fun onQuietFromChange(value: String) = edit { it.copy(quietFrom = value) }

    fun onQuietToChange(value: String) = edit { it.copy(quietTo = value) }

    fun save() {
        val current = mutableState.value
        val form = current.form ?: return
        if (current.saving) return
        if (!form.valid) {
            mutableState.update { it.copy(saveMessage = null, saveError = INVALID_FORM) }
            return
        }
        mutableState.update { it.copy(saving = true, saveMessage = null, saveError = null) }
        viewModelScope.launch {
            try {
                val saved = api.updateSettings(form.toView())
                mutableState.update { it.copy(saving = false, form = SettingsForm.from(saved), saveMessage = SAVED) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                mutableState.update { it.copy(saving = false, saveError = if (e.code() == BAD_REQUEST) REJECTED else SAVE_ERROR) }
            } catch (e: Exception) {
                mutableState.update { it.copy(saving = false, saveError = SAVE_ERROR) }
            }
        }
    }

    private fun edit(change: (SettingsForm) -> SettingsForm) {
        mutableState.update { state ->
            state.form?.let { state.copy(form = change(it), saveMessage = null, saveError = null) } ?: state
        }
    }

    private suspend fun loadStatus() {
        try {
            val status = api.status()
            val now = clock.instant()
            mutableState.update {
                it.copy(
                    sources = status.sources.map { source -> source.toRow(now) },
                    aiUsage = status.aiUsageRows(),
                    latestQuoteDate = Formatting.sessionDate(status.latestQuoteDate),
                    statusError = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.update { it.copy(statusError = STATUS_ERROR) }
        }
    }

    private fun SourceHealthView.toRow(now: Instant) = SourceRow(
        label = Formatting.sourceLabel(source),
        lastRead = Formatting.relative(lastSuccessAt, now),
        failing = lastFailureAt != null && (lastSuccessAt == null || lastFailureAt.isAfter(lastSuccessAt)),
    )

    private fun StatusResponse.aiUsageRows(): List<AiUsageRow> =
        (AI_POOL_ORDER + aiLimitUsd.keys + aiSpentUsd.keys)
            .distinct()
            .filter { it in aiLimitUsd || it in aiSpentUsd }
            .map { pool -> AiUsageRow(Formatting.aiPoolLabel(pool), Formatting.aiUsage(aiSpentUsd[pool] ?: 0.0, aiLimitUsd[pool])) }

    companion object {
        private val AI_POOL_ORDER = listOf("GPW", "US")
        private const val BAD_REQUEST = 400
        const val LOAD_ERROR = "Nie udało się pobrać ustawień"
        const val STATUS_ERROR = "Nie udało się pobrać stanu serwera"
        const val INVALID_FORM = "Popraw zaznaczone pola"
        const val SAVED = "Zapisano"
        const val REJECTED = "Serwer odrzucił ustawienia, sprawdź wartości"
        const val SAVE_ERROR = "Nie udało się zapisać ustawień"
    }
}
