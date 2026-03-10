package xyz.vmflow.vending.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.vmflow.vending.data.repository.AuthRepository

/**
 * UI state for the login screen.
 *
 * @property email The current email input value.
 * @property password The current password input value.
 * @property isLoading Whether a login request is in progress.
 * @property error An error message to display, or null if none.
 * @property isSuccess Whether login was successful (triggers navigation).
 */
data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSuccess: Boolean = false
)

/**
 * ViewModel for the login screen.
 *
 * Manages the login form state and handles authentication via [AuthRepository].
 * Emits state changes through [uiState] as a [StateFlow] for the Compose UI
 * to observe reactively.
 *
 * @property authRepository The repository for performing authentication operations.
 */
class LoginViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())

    /** Observable UI state for the login screen. */
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /**
     * Updates the email field value.
     *
     * @param email The new email string from the text field.
     */
    fun onEmailChange(email: String) {
        _uiState.value = _uiState.value.copy(email = email, error = null)
    }

    /**
     * Updates the password field value.
     *
     * @param password The new password string from the text field.
     */
    fun onPasswordChange(password: String) {
        _uiState.value = _uiState.value.copy(password = password, error = null)
    }

    /**
     * Attempts to log in with the current email and password.
     *
     * Validates that both fields are non-empty, then calls the auth repository.
     * On success, sets [LoginUiState.isSuccess] to true, triggering navigation.
     * On failure, sets [LoginUiState.error] with the error message.
     */
    fun login() {
        val state = _uiState.value

        // Validate inputs
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(error = "Please fill in all fields")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)

            val result = authRepository.login(state.email, state.password)

            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isSuccess = true
                    )
                },
                onFailure = { exception ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = exception.message ?: "Login failed"
                    )
                }
            )
        }
    }
}
