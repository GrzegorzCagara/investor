package pl.investor.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val SUCCESS_COLOR = Color(0xFF1F9D55)

@Composable
fun SettingsTab(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form = state.form
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            form != null -> SettingsFormSection(form, state, viewModel)
            state.loading -> Text("Ładowanie…")
            else -> {
                ErrorMessage(state.loadError ?: SettingsViewModel.LOAD_ERROR)
                TextButton(onClick = viewModel::load) { Text("Spróbuj ponownie") }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        StatusSection(state, viewModel::refreshStatus)
    }
}

@Composable
private fun SettingsFormSection(form: SettingsForm, state: SettingsUiState, viewModel: SettingsViewModel) {
    val editable = !state.saving
    SectionTitle("Powiadomienia")
    OutlinedTextField(
        value = form.threshold,
        onValueChange = viewModel::onThresholdChange,
        label = { Text("Próg oceny (0–100)") },
        isError = form.thresholdError != null,
        supportingText = { Text(form.thresholdError ?: "Powiadomienie przychodzi, gdy ocena jest co najmniej taka.") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        enabled = editable,
        modifier = Modifier.fillMaxWidth(),
    )

    SectionTitle("Rynki")
    Market.entries.forEach { market ->
        SwitchRow(Formatting.marketLabel(market), market in form.enabledMarkets, editable) { viewModel.onMarketChange(market, it) }
    }

    SectionTitle("Rodzaje zdarzeń")
    SwitchRow(Formatting.eventTypeLabel(EventType.RESULTS), form.resultsEnabled, editable, viewModel::onResultsChange)
    Hint("Wyłączone rynki i zdarzenia nadal widać w aplikacji, tylko bez powiadomień.")

    SectionTitle("Godziny ciszy")
    SwitchRow("Włączone", form.quietEnabled, editable, viewModel::onQuietEnabledChange)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TimeField("Od", form.quietFrom, form.quietFromError, editable, viewModel::onQuietFromChange, Modifier.weight(1f))
        TimeField("Do", form.quietTo, form.quietToError, editable, viewModel::onQuietToChange, Modifier.weight(1f))
    }
    Hint("W tych godzinach powiadomienia przychodzą po cichu, bez dźwięku.")

    Button(onClick = viewModel::save, enabled = editable, modifier = Modifier.fillMaxWidth()) {
        Text(if (state.saving) "Zapisywanie…" else "Zapisz")
    }
    state.saveMessage?.let { Text(it, color = SUCCESS_COLOR) }
    state.saveError?.let { Text(it, color = ERROR_COLOR) }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun TimeField(label: String, value: String, error: String?, enabled: Boolean, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = if (error != null) {
            { Text(error) }
        } else {
            null
        },
        singleLine = true,
        enabled = enabled,
        modifier = modifier,
    )
}

@Composable
private fun StatusSection(state: SettingsUiState, onRefresh: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Stan źródeł", Modifier.weight(1f))
        TextButton(onClick = onRefresh) { Text("Odśwież") }
    }
    state.statusError?.let { ErrorMessage(it) }
    Hint("Ostatni udany odczyt")
    state.sources.forEach { source ->
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(source.label, modifier = Modifier.weight(1f), fontSize = 13.sp)
            Text(
                if (source.failing) "⚠ ${source.lastRead}" else source.lastRead,
                fontSize = 13.sp,
                color = if (source.failing) ERROR_COLOR else Color.Unspecified,
            )
        }
    }
    state.latestQuoteDate?.let { date ->
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Notowania (ostatnia sesja)", modifier = Modifier.weight(1f), fontSize = 13.sp)
            Text(date, fontSize = 13.sp)
        }
    }

    SectionTitle("AI w tym miesiącu", Modifier.padding(top = 8.dp))
    state.aiUsage.forEach { usage ->
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(usage.label, modifier = Modifier.weight(1f), fontSize = 13.sp)
            Text(usage.usage, fontSize = 13.sp)
        }
    }
}
