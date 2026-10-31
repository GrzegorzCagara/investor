package pl.investor.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.time.Clock

private val tabTitles = listOf("Alerty", "Ranking", "Skuteczność", "⚙")
private const val ALERTS_TAB = 0
private const val RANKING_TAB = 1
private const val EFFECTIVENESS_TAB = 2

val ERROR_COLOR = Color(0xFF9C4221)
val HINT_COLOR = Color(0xFF777777)

@Composable
fun MainScreen(api: InvestorApi, clock: Clock, onOpen: (Long) -> Unit) {
    val alertsViewModel = viewModel<AlertsViewModel>(
        factory = viewModelFactory { initializer { AlertsViewModel(api, clock) } },
    )
    var selectedTab by rememberSaveable { mutableIntStateOf(ALERTS_TAB) }

    LifecycleResumeEffect(Unit) {
        alertsViewModel.refresh()
        onPauseOrDispose {}
    }

    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 0.dp) {
            tabTitles.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title, maxLines = 1) })
            }
        }
        when (selectedTab) {
            ALERTS_TAB -> AlertsTab(alertsViewModel, clock::instant, onOpen)
            RANKING_TAB -> RankingTab(
                viewModel<RankingViewModel>(factory = viewModelFactory { initializer { RankingViewModel(api, clock) } }),
                onOpen,
            )
            EFFECTIVENESS_TAB -> EffectivenessTab(
                viewModel<EffectivenessViewModel>(factory = viewModelFactory { initializer { EffectivenessViewModel(api, clock) } }),
                onOpen,
            )
            else -> SettingsTab(
                viewModel<SettingsViewModel>(factory = viewModelFactory { initializer { SettingsViewModel(api, clock) } }),
            )
        }
    }
}

@Composable
fun ErrorMessage(text: String) {
    Text(text, color = ERROR_COLOR, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.sp, color = HINT_COLOR, modifier = modifier)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = modifier)
}
