package pl.investor.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant

private val BAR_BACKGROUND = Color(0xFFE6E6E6)
private val BAR_GOOD = Color(0xFF1F9D55)
private val BAR_WEAK = Color(0xFFD69E2E)
private val REVENUE_BAR = Color(0xFF90CDF4)
private val REVENUE_LAST_BAR = Color(0xFF2B6CB0)
private val WARNING = Color(0xFF9C4221)
private const val GOOD_RATIO = 0.6f

@Composable
fun DetailScreen(viewModel: DetailViewModel, now: () -> Instant, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        TextButton(onClick = onBack) { Text("← Wróć") }
        when (val current = state) {
            DetailUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Ładowanie…") }
            DetailUiState.Failed -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nie udało się pobrać szczegółów")
                    TextButton(onClick = viewModel::load) { Text("Spróbuj ponownie") }
                }
            }
            is DetailUiState.Loaded -> DetailContent(current.detail, now())
        }
    }
}

@Composable
private fun DetailContent(detail: AlertDetail, now: Instant) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${detail.companyName} (${Formatting.marketLabel(detail.market)})", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(Formatting.detailHeadline(detail, now), fontSize = 12.sp, color = Color(0xFF666666))
            }
            ScoreBadge(detail.score)
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE2E2E2)),
        ) {
            Text(
                buildWhy(detail.reason),
                modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        detail.reaction?.let { Text(Formatting.reactionLine(it), fontSize = 13.sp) }
        if (detail.consensus.isNotEmpty()) {
            Text(Formatting.consensusLine(detail.consensus), fontSize = 13.sp)
        }
        detail.components.forEach { ComponentRow(it) }
        if (detail.penalty > 0) {
            Text("Kara: −${Formatting.points(detail.penalty)}", color = WARNING, fontSize = 12.sp)
        }
        if (detail.revenueHistory.isNotEmpty()) {
            RevenueChart(detail.revenueHistory)
        }
        detail.outlookQuote?.let { quote ->
            Text("Zapowiedź zarządu", fontSize = 11.sp, color = Color(0xFF888888))
            Text("„$quote”", fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium)
        }
        detail.warnings.forEach { Text("⚠ $it", color = WARNING, fontSize = 12.sp) }
        if (detail.companyHistory.isNotEmpty()) {
            Text("Wcześniejsze alerty spółki", fontSize = 11.sp, color = Color(0xFF888888))
            detail.companyHistory.forEach { HistoryRow(it, now) }
        }
        Button(onClick = { uriHandler.openUri(detail.sourceUrl) }, modifier = Modifier.fillMaxWidth()) {
            Text("Otwórz raport")
        }
    }
}

@Composable
private fun HistoryRow(item: OutcomeItem, now: Instant) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(Formatting.historyLine(item, now), fontSize = 13.sp, modifier = Modifier.weight(1f))
        ScoreBadge(item.score)
    }
}

private fun buildWhy(reason: String): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Dlaczego: ") }
    append(reason)
}

@Composable
private fun ComponentRow(component: ComponentView) {
    val ratio = if (component.max > 0) (component.points / component.max).toFloat().coerceIn(0f, 1f) else 0f
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(component.label, modifier = Modifier.weight(1f), fontSize = 13.sp)
            Text("${Formatting.points(component.points)}/${Formatting.points(component.max)}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Box(modifier = Modifier.fillMaxWidth().height(7.dp).background(BAR_BACKGROUND, RoundedCornerShape(4.dp))) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio)
                    .height(7.dp)
                    .background(if (ratio >= GOOD_RATIO) BAR_GOOD else BAR_WEAK, RoundedCornerShape(4.dp)),
            )
        }
    }
}

@Composable
private fun RevenueChart(history: List<RevenuePoint>) {
    val peak = history.maxOf { it.value }.takeIf { it > 0 } ?: 1.0
    val last = history.last()
    Text("Przychody (${history.size})", fontSize = 11.sp, color = Color(0xFF888888))
    Canvas(modifier = Modifier.fillMaxWidth().height(64.dp)) {
        val gap = 6.dp.toPx()
        val barWidth = (size.width - gap * (history.size - 1)) / history.size
        history.forEachIndexed { index, point ->
            val barHeight = (size.height * (point.value / peak).toFloat()).coerceAtLeast(1f)
            drawRoundRect(
                color = if (index == history.lastIndex) REVENUE_LAST_BAR else REVENUE_BAR,
                topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(history.first().label, fontSize = 10.sp, color = Color(0xFF888888), modifier = Modifier.weight(1f))
        Text("${last.label}: ${Formatting.amount(last.value)} ${last.currency}", fontSize = 10.sp, color = Color(0xFF888888))
    }
}
