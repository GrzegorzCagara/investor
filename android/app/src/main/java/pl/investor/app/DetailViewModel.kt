package pl.investor.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Loaded(val detail: AlertDetail) : DetailUiState
    data object Failed : DetailUiState
}

class DetailViewModel(private val api: InvestorApi, private val alertId: Long) : ViewModel() {

    private val mutableState = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        mutableState.value = DetailUiState.Loading
        viewModelScope.launch {
            mutableState.value = try {
                DetailUiState.Loaded(api.alert(alertId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DetailUiState.Failed
            }
        }
    }
}
