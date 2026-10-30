package pl.investor.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock

data class AlertSection(val title: String, val items: List<AlertItem>)

data class AlertsUiState(
    val loading: Boolean = true,
    val sections: List<AlertSection> = emptyList(),
    val error: String? = null,
)

class AlertsViewModel(private val api: InvestorApi, private val clock: Clock) : ViewModel() {

    private val mutableState = MutableStateFlow(AlertsUiState())
    val state: StateFlow<AlertsUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        mutableState.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val items = api.alerts().items
                mutableState.value = AlertsUiState(loading = false, sections = group(items))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(loading = false, error = LOAD_ERROR) }
            }
        }
    }

    private fun group(items: List<AlertItem>): List<AlertSection> {
        val now = clock.instant()
        return items.groupBy { Formatting.sectionTitle(it.publishedAt, now) }
            .map { (title, grouped) -> AlertSection(title, grouped) }
    }

    companion object {
        const val LOAD_ERROR = "Nie udało się pobrać alertów"
    }
}
