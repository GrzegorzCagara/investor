package pl.investor.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val marketFilters: List<Pair<Market?, String>> = listOf(
    null to "Wszystkie",
    Market.GPW to "GPW",
    Market.NC to "NC",
    Market.US to "USA",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankingTab(viewModel: RankingViewModel, onOpen: (Long) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            marketFilters.forEach { (market, label) ->
                FilterChip(selected = state.market == market, onClick = { viewModel.selectMarket(market) }, label = { Text(label) })
            }
        }
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { Hint("Najwyżej ocenione raporty z ostatnich 30 dni, po jednym na spółkę.") }
                state.error?.let { error -> item { ErrorMessage(error) } }
                if (!state.loading && state.error == null && state.items.isEmpty()) {
                    item { Hint("Brak ocenionych raportów", Modifier.padding(vertical = 16.dp)) }
                }
                items(state.items, key = { it.id }) { alert -> AlertCard(alert, state.now, onOpen) }
            }
        }
    }
}
