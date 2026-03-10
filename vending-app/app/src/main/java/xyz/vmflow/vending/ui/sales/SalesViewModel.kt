package xyz.vmflow.vending.ui.sales

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.vmflow.vending.data.repository.SalesRepository
import xyz.vmflow.vending.domain.model.Sale

/**
 * UI state for the sales history screen.
 *
 * @property sales The list of sale records, ordered by most recent first.
 * @property isLoading Whether sales are being fetched.
 * @property isRefreshing Whether a pull-to-refresh is in progress.
 * @property error An error message, or null if none.
 */
data class SalesUiState(
    val sales: List<Sale> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null
)

/**
 * ViewModel for the sales history screen.
 *
 * Fetches and displays transaction records from the backend.
 * Supports initial loading and pull-to-refresh.
 *
 * @property salesRepository Repository for querying sales data.
 */
class SalesViewModel(
    private val salesRepository: SalesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SalesUiState())

    /** Observable UI state for the sales screen. */
    val uiState: StateFlow<SalesUiState> = _uiState.asStateFlow()

    init {
        loadSales()
    }

    /**
     * Loads the sales history from the backend.
     *
     * Called on initialization and on manual refresh.
     */
    fun loadSales() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val result = salesRepository.getSales()

            result.fold(
                onSuccess = { sales ->
                    _uiState.value = _uiState.value.copy(
                        sales = sales,
                        isLoading = false,
                        isRefreshing = false
                    )
                },
                onFailure = { exception ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = exception.message ?: "Failed to load sales"
                    )
                }
            )
        }
    }

    /**
     * Refreshes the sales list (pull-to-refresh).
     */
    fun refresh() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        loadSales()
    }
}
