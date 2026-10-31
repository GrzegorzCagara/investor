package pl.investor.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

data class RankingUiState(
    val loading: Boolean = true,
    val market: Market? = null,
    val items: List<AlertItem> = emptyList(),
    val error: String? = null,
    val now: Instant = Instant.EPOCH,
)

class RankingViewModel(private val api: InvestorApi, private val clock: Clock) : ViewModel() {

    private val mutableState = MutableStateFlow(RankingUiState(now = clock.instant()))
    val state: StateFlow<RankingUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun selectMarket(market: Market?) {
        if (market == mutableState.value.market) return
        mutableState.update { it.copy(market = market, items = emptyList(), loading = true, error = null) }
        load(market)
    }

    fun refresh() {
        mutableState.update { it.copy(loading = true, error = null) }
        load(mutableState.value.market)
    }

    private fun load(market: Market?) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val items = api.ranking(market).items
                mutableState.update { it.copy(loading = false, items = items, now = clock.instant()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(loading = false, error = LOAD_ERROR) }
            }
        }
    }

    companion object {
        const val LOAD_ERROR = "Nie udało się pobrać rankingu"
    }
}
