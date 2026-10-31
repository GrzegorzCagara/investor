package pl.investor.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant

private const val MUTED_ALPHA = 0.6f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsTab(viewModel: AlertsViewModel, now: () -> Instant, onOpen: (Long) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PullToRefreshBox(isRefreshing = state.loading, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
        AlertsList(state, now(), onOpen)
    }
}

@Composable
private fun AlertsList(state: AlertsUiState, now: Instant, onOpen: (Long) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.error?.let { error ->
            item { Text(error, color = ERROR_COLOR, modifier = Modifier.padding(vertical = 8.dp)) }
        }
        if (!state.loading && state.error == null && state.sections.isEmpty()) {
            item { Text("Brak alertów", color = Color.Gray, modifier = Modifier.padding(vertical = 16.dp)) }
        }
        state.sections.forEach { section ->
            item(key = "section-${section.title}") {
                Text(
                    section.title.uppercase(),
                    fontSize = 11.sp,
                    color = Color(0xFF888888),
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }
            items(section.items, key = { it.id }) { alert -> AlertCard(alert, now, onOpen) }
        }
    }
}

@Composable
fun AlertCard(alert: AlertItem, now: Instant, onOpen: (Long) -> Unit) {
    val muted = alert.belowThreshold || alert.score == null
    Card(
        modifier = Modifier.fillMaxWidth().alpha(if (muted) MUTED_ALPHA else 1f).clickable { onOpen(alert.id) },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE6E6E6)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${alert.companyName} · ${Formatting.marketLabel(alert.market)}",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                ScoreBadge(alert.score)
            }
            Text(
                "${Formatting.time(alert.publishedAt, now)} · ${alert.summary}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF555555),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
fun ScoreBadge(score: Int?) {
    Box(
        modifier = Modifier
            .background(Color(ScoreColors.forScore(score)), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(score?.toString() ?: "—", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}
