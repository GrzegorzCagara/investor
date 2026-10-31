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

data class HorizonCell(val excess: String, val count: String)

data class StatsRow(val label: String, val alerts: Int, val cells: List<HorizonCell>)

data class StatsGroup(val title: String, val rows: List<StatsRow>)

data class EffectivenessUiState(
    val loading: Boolean = true,
    val scoreGroups: List<StatsGroup> = emptyList(),
    val reactionRows: List<StatsRow> = emptyList(),
    val items: List<OutcomeItem> = emptyList(),
    val error: String? = null,
    val now: Instant = Instant.EPOCH,
)

class EffectivenessViewModel(private val api: InvestorApi, private val clock: Clock) : ViewModel() {

    private val mutableState = MutableStateFlow(EffectivenessUiState(now = clock.instant()))
    val state: StateFlow<EffectivenessUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        mutableState.update { it.copy(loading = true, error = null) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val response = api.effectiveness()
                mutableState.value = EffectivenessUiState(
                    loading = false,
                    scoreGroups = response.buckets.groupBy { it.eventType }.map { (type, buckets) ->
                        StatsGroup(Formatting.eventTypeLabel(type), buckets.map { it.toRow() })
                    },
                    reactionRows = response.byReaction.map { it.toRow() },
                    items = response.items,
                    now = clock.instant(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(loading = false, error = LOAD_ERROR) }
            }
        }
    }

    private fun ScoreBucketView.toRow() = StatsRow(label, alerts, horizons.cells())

    private fun ReactionBucketView.toRow() = StatsRow(Formatting.reactionLabel(reaction), alerts, horizons.cells())

    private fun List<HorizonStats>.cells(): List<HorizonCell> =
        HORIZONS.map { sessions ->
            val stats = firstOrNull { it.sessions == sessions }
            HorizonCell(Formatting.horizonExcess(stats), Formatting.horizonCount(stats))
        }

    companion object {
        val HORIZONS = listOf(5, 20, 60)
        const val LOAD_ERROR = "Nie udało się pobrać skuteczności"
    }
}
