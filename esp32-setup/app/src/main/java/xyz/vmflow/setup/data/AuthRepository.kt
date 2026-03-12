package xyz.vmflow.setup.data

import android.content.SharedPreferences
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class AuthRepository(
    private val httpClient: HttpClient,
    private val prefs: SharedPreferences
) {
    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
    }

    suspend fun login(email: String, password: String): Result<AuthResponse> {
        return try {
            val response = httpClient.post("${ApiConstants.SUPABASE_URL}/auth/v1/token?grant_type=password") {
                header("apikey", ApiConstants.SUPABASE_ANON_KEY)
                setBody(LoginRequest(email = email, password = password))
            }
            if (response.status.isSuccess()) {
                val auth: AuthResponse = response.body()
                saveTokens(auth)
                Result.success(auth)
            } else {
                val err: ErrorResponse = response.body()
                Result.failure(Exception(err.msg ?: err.message ?: "Login failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshToken(): Result<AuthResponse> {
        val rt = prefs.getString(KEY_REFRESH_TOKEN, null)
            ?: return Result.failure(Exception("No refresh token"))
        return try {
            val response = httpClient.post("${ApiConstants.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token") {
                header("apikey", ApiConstants.SUPABASE_ANON_KEY)
                setBody(RefreshRequest(refreshToken = rt))
            }
            if (response.status.isSuccess()) {
                val auth: AuthResponse = response.body()
                saveTokens(auth)
                Result.success(auth)
            } else {
                Result.failure(Exception("Token refresh failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)
    fun isLoggedIn(): Boolean = getAccessToken() != null

    fun logout() {
        prefs.edit().remove(KEY_ACCESS_TOKEN).remove(KEY_REFRESH_TOKEN).apply()
    }

    private fun saveTokens(auth: AuthResponse) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, auth.accessToken)
            .putString(KEY_REFRESH_TOKEN, auth.refreshToken)
            .apply()
    }
}

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class AuthResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_in") val expiresIn: Long = 0
)

@Serializable
data class ErrorResponse(
    val msg: String? = null,
    val message: String? = null,
    val error: String? = null
)
