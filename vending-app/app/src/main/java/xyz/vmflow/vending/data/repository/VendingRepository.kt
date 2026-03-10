package xyz.vmflow.vending.data.repository

import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import xyz.vmflow.vending.data.remote.ApiConstants

/**
 * Repository for the vending credit request flow.
 *
 * Handles the critical path of converting a BLE vend request into an
 * approved credit by communicating with the backend API. This is the
 * bridge between the local BLE payment and the server-side validation.
 *
 * ## Credit Request Flow
 * 1. Receive XOR-encrypted vend request from ESP32 via BLE notification
 * 2. Base64-encode the raw 19-byte payload
 * 3. POST to `/api/credit/request` with payload, subdomain, and GPS coords
 * 4. Backend decrypts, validates, records the sale, re-encrypts with approve command
 * 5. Receive the approved payload (Base64-encoded)
 * 6. Base64-decode and write to BLE characteristic -> ESP32 approves the vend
 *
 * @property httpClient The Ktor HTTP client for making API requests.
 * @property authRepository The auth repository for obtaining access tokens.
 */
class VendingRepository(
    private val httpClient: HttpClient,
    private val authRepository: AuthRepository
) {

    /**
     * Sends a credit request to the backend for validation and approval.
     *
     * This is the critical API call in the vending flow. It sends the raw
     * BLE payload to the backend, which performs:
     * 1. JWT verification to identify the user
     * 2. XOR decryption using the device's passkey
     * 3. Checksum and timestamp validation
     * 4. Sale record creation in the database
     * 5. Re-encryption with the approve command (0x03)
     *
     * The returned payload bytes are ready to be written directly to the
     * BLE characteristic to approve the vend.
     *
     * @param payload The raw 19-byte XOR-encrypted payload from the BLE notification.
     * @param subdomain The device's subdomain identifier (e.g., "123456").
     * @param latitude GPS latitude at the time of the transaction (optional).
     * @param longitude GPS longitude at the time of the transaction (optional).
     * @return [Result.success] with a [CreditResponse] containing the approval payload,
     *         or [Result.failure] with an exception on error.
     *         Automatically retries once with a refreshed token on 401.
     */
    suspend fun requestCredit(
        payload: ByteArray,
        subdomain: String,
        latitude: Double? = null,
        longitude: Double? = null
    ): Result<CreditResponse> {
        return executeWithRetry {
            val token = authRepository.getAccessToken()
                ?: return@executeWithRetry Result.failure(Exception("Not authenticated"))

            // Base64-encode the raw BLE payload for transport over JSON
            val payloadBase64 = Base64.encodeToString(payload, Base64.NO_WRAP)

            val response = httpClient.post("${ApiConstants.SUPABASE_URL}/functions/v1/request-credit") {
                header("apikey", ApiConstants.SUPABASE_ANON_KEY)
                header("Authorization", "Bearer $token")
                setBody(CreditRequest(
                    payload = payloadBase64,
                    subdomain = subdomain,
                    lat = latitude,
                    lng = longitude
                ))
            }

            if (response.status.isSuccess()) {
                val creditResponse: CreditResponse = response.body()
                Result.success(creditResponse)
            } else {
                Result.failure(Exception("Credit request failed: ${response.status}"))
            }
        }
    }

    /**
     * Decodes the Base64 approval payload from the credit response.
     *
     * Convenience method that converts the Base64-encoded approval payload
     * from the backend response into raw bytes ready for BLE transmission.
     *
     * @param creditResponse The response from [requestCredit].
     * @return The raw byte array to write to the BLE characteristic.
     */
    fun decodeApprovalPayload(creditResponse: CreditResponse): ByteArray {
        return Base64.decode(creditResponse.payload, Base64.NO_WRAP)
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

/** Request body for POST /api/credit/request (or Supabase function) */
@Serializable
data class CreditRequest(
    val payload: String,
    val subdomain: String,
    val lat: Double? = null,
    val lng: Double? = null
)

/** Response from the credit request endpoint */
@Serializable
data class CreditResponse(
    val payload: String = "",
    @kotlinx.serialization.SerialName("sales_id")
    val salesId: String = ""
)
