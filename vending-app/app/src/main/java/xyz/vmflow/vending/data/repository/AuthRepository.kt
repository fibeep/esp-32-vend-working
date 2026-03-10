package xyz.vmflow.vending.data.repository

import android.content.SharedPreferences
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.vmflow.vending.data.remote.ApiConstants

/**
 * Repository for authentication operations using Supabase GoTrue.
 *
 * Manages user login, registration, token storage, and automatic token refresh.
 * Authentication state is persisted in [SharedPreferences] so the user stays
 * logged in across app restarts.
 *
 * ## Token Flow
 * 1. User logs in via [login] or registers via [register]
 * 2. The access token and refresh token are stored in SharedPreferences
 * 3. All subsequent API calls use [getAccessToken] to retrieve the JWT
 * 4. If a 401 is received, [refreshToken] is called to get a new access token
 * 5. [logout] clears all stored credentials
 *
 * @property httpClient The Ktor HTTP client for making auth requests.
 * @property prefs SharedPreferences for persisting auth tokens.
 */
class AuthRepository(
    private val httpClient: HttpClient,
    private val prefs: SharedPreferences
) {

    companion object {
        /** SharedPreferences key for the full auth JSON response */
        private const val KEY_AUTH_JSON = "auth_json"

        /** SharedPreferences key for the cached access token */
        private const val KEY_ACCESS_TOKEN = "access_token"

        /** SharedPreferences key for the cached refresh token */
        private const val KEY_REFRESH_TOKEN = "refresh_token"

        /** JSON parser instance for auth responses */
        private val json = Json { ignoreUnknownKeys = true }
    }

    /**
     * Authenticates a user with email and password.
     *
     * Calls the Supabase GoTrue `/auth/v1/token` endpoint with grant_type=password.
     * On success, stores the auth tokens in SharedPreferences.
     *
     * @param email The user's email address.
     * @param password The user's password.
     * @return [Result.success] with the [AuthResponse] on success,
     *         or [Result.failure] with an exception on error.
     */
    suspend fun login(email: String, password: String): Result<AuthResponse> {
        return try {
            val response = httpClient.post("${ApiConstants.SUPABASE_URL}/auth/v1/token?grant_type=password") {
                header("apikey", ApiConstants.SUPABASE_ANON_KEY)
                setBody(LoginRequest(email = email, password = password))
            }

            if (response.status.isSuccess()) {
                val authResponse: AuthResponse = response.body()
                saveAuthData(authResponse)
                Result.success(authResponse)
            } else {
                val errorBody: ErrorResponse = response.body()
                Result.failure(Exception(errorBody.msg ?: errorBody.message ?: "Login failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Registers a new user account.
     *
     * Calls the Supabase GoTrue `/auth/v1/signup` endpoint.
     * On success, stores the auth tokens (user is automatically logged in).
     *
     * @param email The new user's email address.
     * @param password The desired password.
     * @param fullName The user's full name (stored in user metadata).
     * @return [Result.success] with the [AuthResponse] on success,
     *         or [Result.failure] with an exception on error.
     */
    suspend fun register(email: String, password: String, fullName: String): Result<AuthResponse> {
        return try {
            val response = httpClient.post("${ApiConstants.SUPABASE_URL}/auth/v1/signup") {
                header("apikey", ApiConstants.SUPABASE_ANON_KEY)
                setBody(RegisterRequest(
                    email = email,
                    password = password,
                    data = UserMetadata(fullName = fullName)
                ))
            }

            if (response.status.isSuccess()) {
                val authResponse: AuthResponse = response.body()
                saveAuthData(authResponse)
                Result.success(authResponse)
            } else {
                val errorBody: ErrorResponse = response.body()
                Result.failure(Exception(errorBody.msg ?: errorBody.message ?: "Registration failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Refreshes the access token using the stored refresh token.
     *
     * Called automatically when a 401 response is received from the API.
     * On success, updates the stored tokens with the new values.
     *
     * @return [Result.success] with the new [AuthResponse] on success,
     *         or [Result.failure] if the refresh fails (user must re-login).
     */
    suspend fun refreshToken(): Result<AuthResponse> {
        val currentRefreshToken = getRefreshToken() ?: return Result.failure(
            Exception("No refresh token available")
        )

        return try {
            val response = httpClient.post("${ApiConstants.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token") {
                header("apikey", ApiConstants.SUPABASE_ANON_KEY)
                setBody(RefreshRequest(refreshToken = currentRefreshToken))
            }

            if (response.status.isSuccess()) {
                val authResponse: AuthResponse = response.body()
                saveAuthData(authResponse)
                Result.success(authResponse)
            } else {
                Result.failure(Exception("Token refresh failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Retrieves the currently stored access token.
     *
     * @return The JWT access token string, or null if not logged in.
     */
    fun getAccessToken(): String? {
        return prefs.getString(KEY_ACCESS_TOKEN, null)
    }

    /**
     * Retrieves the currently stored refresh token.
     *
     * @return The refresh token string, or null if not logged in.
     */
    fun getRefreshToken(): String? {
        return prefs.getString(KEY_REFRESH_TOKEN, null)
    }

    /**
     * Checks whether the user is currently logged in.
     *
     * A user is considered logged in if an access token exists in storage.
     * Note: the token may be expired; actual validity is checked by the API.
     *
     * @return `true` if an access token is stored.
     */
    fun isLoggedIn(): Boolean {
        return getAccessToken() != null
    }

    /**
     * Logs out the user by clearing all stored authentication data.
     *
     * After calling this, [isLoggedIn] will return false and all API
     * calls will fail with 401 until the user logs in again.
     */
    fun logout() {
        prefs.edit()
            .remove(KEY_AUTH_JSON)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .apply()
    }

    /**
     * Persists the authentication response data to SharedPreferences.
     *
     * Stores the full JSON response as well as extracted token fields
     * for quick access.
     */
    private fun saveAuthData(authResponse: AuthResponse) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, authResponse.accessToken)
            .putString(KEY_REFRESH_TOKEN, authResponse.refreshToken)
            .apply()
    }
}

// ── Request/Response DTOs ──────────────────────────────────────────────────────

/** Login request body for Supabase GoTrue */
@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

/** Registration request body for Supabase GoTrue */
@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val data: UserMetadata? = null
)

/** User metadata included in registration */
@Serializable
data class UserMetadata(
    @kotlinx.serialization.SerialName("full_name")
    val fullName: String
)

/** Token refresh request body */
@Serializable
data class RefreshRequest(
    @kotlinx.serialization.SerialName("refresh_token")
    val refreshToken: String
)

/** Successful authentication response from Supabase GoTrue */
@Serializable
data class AuthResponse(
    @kotlinx.serialization.SerialName("access_token")
    val accessToken: String = "",

    @kotlinx.serialization.SerialName("refresh_token")
    val refreshToken: String = "",

    @kotlinx.serialization.SerialName("token_type")
    val tokenType: String = "bearer",

    @kotlinx.serialization.SerialName("expires_in")
    val expiresIn: Long = 0
)

/** Error response from Supabase GoTrue */
@Serializable
data class ErrorResponse(
    val msg: String? = null,
    val message: String? = null,
    val error: String? = null
)
