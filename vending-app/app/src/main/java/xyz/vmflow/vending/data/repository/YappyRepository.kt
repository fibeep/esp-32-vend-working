package xyz.vmflow.vending.data.repository

import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.vmflow.vending.data.remote.ApiConstants

/**
 * Repository for Yappy payment operations.
 *
 * Communicates with the `yappy-payment` Edge Function to:
 * 1. Generate a dynamic QR code for Yappy payment
 * 2. Poll transaction status until paid
 * 3. Cancel a pending transaction
 *
 * Follows the same patterns as [VendingRepository] for auth token
 * handling and retry logic.
 *
 * @property httpClient The Ktor HTTP client for making API requests.
 * @property authRepository The auth repository for obtaining access tokens.
 */
class YappyRepository(
    private val httpClient: HttpClient,
    private val authRepository: AuthRepository
) {

    /**
     * Generates a Yappy QR code for the given vend request payload.
     *
     * Sends the BLE payload to the Edge Function which:
     * 1. Decrypts the payload to get the price
     * 2. Calls Yappy API to generate a dynamic QR
     * 3. Returns the QR hash and transaction ID
     *
     * @param payload The raw 19-byte XOR-encrypted BLE payload.
     * @param subdomain The device's subdomain identifier.
     * @param latitude GPS latitude (optional).
     * @param longitude GPS longitude (optional).
     * @return [Result.success] with [YappyQrResponse], or [Result.failure] on error.
     */
    suspend fun generateQr(
        payload: ByteArray,
        subdomain: String,
        latitude: Double? = null,
        longitude: Double? = null
    ): Result<YappyQrResponse> {
        return executeWithRetry {
            val token = authRepository.getAccessToken()
                ?: return@executeWithRetry Result.failure(Exception("Not authenticated"))

            val payloadBase64 = Base64.encodeToString(payload, Base64.NO_WRAP)

            val response = httpClient.post("${ApiConstants.SUPABASE_URL}/functions/v1/yappy-payment") {
                header("apikey", ApiConstants.SUPABASE_ANON_KEY)
                header("Authorization", "Bearer $token")
                setBody(YappyRequest(
                    action = "generate-qr",
                    payload = payloadBase64,
                    subdomain = subdomain,
                    lat = latitude,
                    lng = longitude
                ))
            }

            if (response.status.isSuccess()) {
                val qrResponse: YappyQrResponse = response.body()
                Result.success(qrResponse)
            } else {
                Result.failure(Exception("QR generation failed: ${response.status}"))
            }
        }
    }

    /**
     * Checks the status of a Yappy transaction.
     *
     * If the transaction is paid (status "PAGADO"), the Edge Function also:
     * - Records the sale in the database
     * - Returns the XOR-encrypted approval payload
     *
     * @param transactionId The Yappy transaction ID from [generateQr].
     * @param payload The original BLE payload (needed for approval on payment).
     * @param subdomain The device's subdomain.
     * @param latitude GPS latitude (optional).
     * @param longitude GPS longitude (optional).
     * @return [Result.success] with [YappyStatusResponse], or [Result.failure] on error.
     */
    suspend fun checkStatus(
        transactionId: String,
        payload: ByteArray,
        subdomain: String,
        latitude: Double? = null,
        longitude: Double? = null
    ): Result<YappyStatusResponse> {
        return executeWithRetry {
            val token = authRepository.getAccessToken()
                ?: return@executeWithRetry Result.failure(Exception("Not authenticated"))

            val payloadBase64 = Base64.encodeToString(payload, Base64.NO_WRAP)

            val response = httpClient.post("${ApiConstants.SUPABASE_URL}/functions/v1/yappy-payment") {
                header("apikey", ApiConstants.SUPABASE_ANON_KEY)
                header("Authorization", "Bearer $token")
                setBody(YappyRequest(
                    action = "check-status",
                    transactionId = transactionId,
                    payload = payloadBase64,
                    subdomain = subdomain,
                    lat = latitude,
                    lng = longitude
                ))
            }

            if (response.status.isSuccess()) {
                val statusResponse: YappyStatusResponse = response.body()
                Result.success(statusResponse)
            } else {
                Result.failure(Exception("Status check failed: ${response.status}"))
            }
        }
    }

    /**
     * Cancels a pending Yappy transaction.
     *
     * @param transactionId The Yappy transaction ID to cancel.
     * @return [Result.success] on successful cancellation, or [Result.failure] on error.
     */
    suspend fun cancelTransaction(transactionId: String): Result<Unit> {
        return executeWithRetry {
            val token = authRepository.getAccessToken()
                ?: return@executeWithRetry Result.failure(Exception("Not authenticated"))

            val response = httpClient.post("${ApiConstants.SUPABASE_URL}/functions/v1/yappy-payment") {
                header("apikey", ApiConstants.SUPABASE_ANON_KEY)
                header("Authorization", "Bearer $token")
                setBody(YappyRequest(
                    action = "cancel",
                    transactionId = transactionId
                ))
            }

            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Cancel failed: ${response.status}"))
            }
        }
    }

    /**
     * Decodes the Base64 approval payload from a Yappy status response.
     *
     * @param statusResponse The response from [checkStatus] when status is "PAGADO".
     * @return The raw byte array to write to the BLE characteristic.
     */
    fun decodeApprovalPayload(statusResponse: YappyStatusResponse): ByteArray {
        return Base64.decode(statusResponse.payload, Base64.NO_WRAP)
    }

    /**
     * Executes an API call with automatic token refresh on 401 responses.
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

// ── Request/Response DTOs ───────────────────────────────────────────────

/** Unified request body for the yappy-payment Edge Function */
@Serializable
data class YappyRequest(
    val action: String,
    val payload: String? = null,
    val subdomain: String? = null,
    @SerialName("transaction_id")
    val transactionId: String? = null,
    val lat: Double? = null,
    val lng: Double? = null
)

/** Response from action: "generate-qr" */
@Serializable
data class YappyQrResponse(
    @SerialName("qr_hash")
    val qrHash: String = "",
    @SerialName("transaction_id")
    val transactionId: String = "",
    val amount: Double = 0.0
)

/** Response from action: "check-status" */
@Serializable
data class YappyStatusResponse(
    val status: String = "",
    val payload: String = "",
    @SerialName("sales_id")
    val salesId: String = "",
    @SerialName("yappy_raw_status")
    val yappyRawStatus: String = "",
    @SerialName("yappy_body_keys")
    val yappyBodyKeys: List<String> = emptyList()
)
