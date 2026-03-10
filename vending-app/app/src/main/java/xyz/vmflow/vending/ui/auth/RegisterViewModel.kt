package xyz.vmflow.vending.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.vmflow.vending.data.repository.AuthRepository

/**
 * UI state for the registration screen.
 *
 * @property fullName The current full name input value.
 * @property email The current email input value.
 * @property password The current password input value.
 * @property confirmPassword The current confirm-password input value.
 * @property isLoading Whether a registration request is in progress.
 * @property error An error message to display, or null if none.
 * @property isSuccess Whether registration was successful (triggers navigation).
 */
data class RegisterUiState(
    val fullName: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSuccess: Boolean = false
)

/**
 * ViewModel for the registration screen.
 *
 * Manages the registration form state including validation of password matching.
 * Handles account creation via [AuthRepository] and emits state changes through
 * [uiState] for the Compose UI to observe.
 *
 * @property authRepository The repository for performing authentication operations.
 */
class RegisterViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RegisterUiState())

    /** Observable UI state for the registration screen. */
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    /** Updates the full name field. */
    fun onFullNameChange(fullName: String) {
        _uiState.value = _uiState.value.copy(fullName = fullName, error = null)
    }

    /** Updates the email field. */
    fun onEmailChange(email: String) {
        _uiState.value = _uiState.value.copy(email = email, error = null)
    }

    /** Updates the password field. */
    fun onPasswordChange(password: String) {
        _uiState.value = _uiState.value.copy(password = password, error = null)
    }

    /** Updates the confirm password field. */
    fun onConfirmPasswordChange(confirmPassword: String) {
        _uiState.value = _uiState.value.copy(confirmPassword = confirmPassword, error = null)
    }

    /**
     * Attempts to register a new account.
     *
     * Validates that all fields are filled and passwords match, then calls
     * the auth repository. On success, sets [RegisterUiState.isSuccess] to true.
     */
    fun register() {
        val state = _uiState.value

        // Validate inputs
        if (state.email.isBlank() || state.password.isBlank() || state.fullName.isBlank()) {
            _uiState.value = state.copy(error = "Please fill in all fields")
            return
        }

        if (state.password != state.confirmPassword) {
            _uiState.value = state.copy(error = "Passwords do not match")
            return
        }

        if (state.password.length < 6) {
            _uiState.value = state.copy(error = "Password must be at least 6 characters")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)

            val result = authRepository.register(
                email = state.email,
                password = state.password,
                fullName = state.fullName
            )

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
                        error = exception.message ?: "Registration failed"
                    )
                }
            )
        }
    }
}
