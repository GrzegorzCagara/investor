package pl.investor.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant

private const val EXPLANATION = "Zwrot ponad indeks (WIG/NCIndex) po 5, 20 i 60 sesjach od zamknięcia sesji po raporcie."
private const val LABEL_WEIGHT = 1.5f
private const val ALERTS_WEIGHT = 0.8f
private const val CELL_WEIGHT = 1.2f
private val CELL_TEXT: TextUnit = 11.sp
private val COUNT_TEXT: TextUnit = 10.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectivenessTab(viewModel: EffectivenessViewModel, onOpen: (Long) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PullToRefreshBox(isRefreshing = state.loading, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Hint(EXPLANATION, Modifier.padding(top = 8.dp)) }
            state.error?.let { error -> item { ErrorMessage(error) } }
            state.scoreGroups.forEach { group ->
                item(key = "group-${group.title}") { StatsTable("${group.title}: według oceny", "Ocena", group.rows) }
            }
            if (state.reactionRows.isNotEmpty()) {
                item(key = "reactions") { StatsTable("Według reakcji rynku w dniu raportu", "Reakcja", state.reactionRows) }
            }
            if (state.items.isNotEmpty()) {
                item(key = "items-title") { SectionTitle("Wcześniejsze alerty", Modifier.padding(top = 8.dp)) }
            }
            items(state.items, key = { "item-${it.id}" }) { outcome -> OutcomeCard(outcome, state.now, onOpen) }
        }
    }
}

@Composable
private fun StatsTable(title: String, firstColumn: String, rows: List<StatsRow>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE6E6E6)),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            StatsLine(firstColumn, "Alerty", EffectivenessViewModel.HORIZONS.map { HorizonCell("$it sesji", "") }, header = true)
            rows.forEach { row -> StatsLine(row.label, row.alerts.toString(), row.cells, header = false) }
        }
    }
}

@Composable
private fun StatsLine(label: String, alerts: String, cells: List<HorizonCell>, header: Boolean) {
    val weight = if (header) FontWeight.Bold else FontWeight.Normal
    val color = if (header) HINT_COLOR else Color.Unspecified
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(LABEL_WEIGHT), fontSize = CELL_TEXT, fontWeight = weight, color = color)
        Text(alerts, modifier = Modifier.weight(ALERTS_WEIGHT), fontSize = CELL_TEXT, fontWeight = weight, color = color, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
        cells.forEach { cell ->
            Column(modifier = Modifier.weight(CELL_WEIGHT), horizontalAlignment = Alignment.End) {
                Text(cell.excess, fontSize = CELL_TEXT, fontWeight = weight, color = color, maxLines = 1, softWrap = false)
                if (cell.count.isNotEmpty()) {
                    Text(cell.count, fontSize = COUNT_TEXT, color = HINT_COLOR, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun OutcomeCard(item: OutcomeItem, now: Instant, onOpen: (Long) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(item.id) },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE6E6E6)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${item.companyName} · ${Formatting.marketLabel(item.market)}",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                ScoreBadge(item.score)
            }
            Text(
                "${Formatting.date(item.publishedAt, now)} · ${Formatting.outcomeLine(item)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF555555),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}
