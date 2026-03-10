package xyz.vmflow.vending.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import xyz.vmflow.vending.data.remote.ApiConstants
import xyz.vmflow.vending.domain.model.Sale

/**
 * Repository for querying sales/transaction history.
 *
 * Fetches sale records from the Supabase `sales` table, including
 * joined device information (subdomain). Sales are ordered by creation
 * date in descending order (most recent first).
 *
 * All operations require a valid JWT access token for Supabase RLS filtering,
 * ensuring users only see sales from their own devices.
 *
 * @property httpClient The Ktor HTTP client for making API requests.
 * @property authRepository The auth repository for obtaining access tokens.
 */
class SalesRepository(
    private val httpClient: HttpClient,
    private val authRepository: AuthRepository
) {

    /**
     * Fetches all sales records for the authenticated user.
     *
     * Queries the Supabase `sales` table with a join on `embedded` to include
     * the device subdomain. Results are ordered by `created_at` descending.
     *
     * The query uses the PostgREST syntax:
     * `GET /rest/v1/sales?select=*,embedded(subdomain)&order=created_at.desc`
     *
     * @return [Result.success] with a list of [Sale] objects,
     *         or [Result.failure] with an exception on error.
     *         Automatically retries once with a refreshed token on 401.
     */
    suspend fun getSales(): Result<List<Sale>> {
        return executeWithRetry {
            val token = authRepository.getAccessToken()
                ?: return@executeWithRetry Result.failure(Exception("Not authenticated"))

            val response = httpClient.get(
                "${ApiConstants.SUPABASE_URL}/rest/v1/sales?select=*,embedded(subdomain)&order=created_at.desc"
            ) {
                header("apikey", ApiConstants.SUPABASE_ANON_KEY)
                header("Authorization", "Bearer $token")
            }

            if (response.status.isSuccess()) {
                val sales: List<Sale> = response.body()
                Result.success(sales)
            } else {
                Result.failure(Exception("Failed to fetch sales: ${response.status}"))
            }
        }
    }

    /**
     * Executes an API call with automatic token refresh on 401 responses.
     *
     * @param block The suspending lambda containing the API call.
     * @return The result from the API call.
     */
    private suspend fun <T> executeWithRetry(block: suspend () -> Result<T>): Result<T> {
        val result = try {
            block()
        } catch (e: Exception) {
            Result.failure(e)
        }
        if (result.isFailure) {
            val exception = result.exceptionOrNull()
            if (exception?.message?.contains("401") == true) {
                val refreshResult = authRepository.refreshToken()
                if (refreshResult.isSuccess) {
                    return try {
                        block()
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }
        }
        return result
    }
}
