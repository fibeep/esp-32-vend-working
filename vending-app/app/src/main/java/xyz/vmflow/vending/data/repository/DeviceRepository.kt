package xyz.vmflow.vending.data.repository

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import xyz.vmflow.vending.data.remote.ApiConstants
import xyz.vmflow.vending.domain.model.Device

/**
 * Repository for ESP32 embedded device management.
 *
 * Provides CRUD operations for registered devices, including:
 * - Listing all devices owned by the authenticated user
 * - Registering new devices by MAC address
 * - Fetching a specific device's details (including passkey)
 *
 * All operations require a valid JWT access token obtained from [AuthRepository].
 *
 * @property httpClient The Ktor HTTP client for making API requests.
 * @property authRepository The auth repository for obtaining access tokens.
 */
class DeviceRepository(
    private val httpClient: HttpClient,
    private val authRepository: AuthRepository
) {
    companion object {
        private const val TAG = "DeviceRepository"
    }

    /**
     * Fetches all devices owned by the authenticated user.
     *
     * Queries the Supabase `embedded` table with the user's JWT for RLS filtering.
     * The response includes device status (online/offline) from MQTT LWT.
     *
     * @return [Result.success] with a list of [Device] objects,
     *         or [Result.failure] with an exception on error.
     *         Automatically retries once with a refreshed token on 401.
     */
    suspend fun getDevices(): Result<List<Device>> {
        return executeWithRetry {
            val token = authRepository.getAccessToken()
                ?: return@executeWithRetry Result.failure(Exception("Not authenticated"))

            val response = httpClient.get("${ApiConstants.SUPABASE_URL}/rest/v1/embedded") {
                header("apikey", ApiConstants.SUPABASE_ANON_KEY)
                header("Authorization", "Bearer $token")
            }

            if (response.status.isSuccess()) {
                val devices: List<Device> = response.body()
                Result.success(devices)
            } else {
                Result.failure(Exception("Failed to fetch devices: ${response.status}"))
            }
        }
    }

    /**
     * Registers a new device by its BLE MAC address.
     *
     * Sends a POST request to create a new entry in the `embedded` table.
     * The backend generates a unique subdomain and passkey for the device.
     * The response includes all device details needed for BLE provisioning.
     *
     * @param macAddress The BLE MAC address of the ESP32 device (e.g., "AA:BB:CC:DD:EE:FF").
     * @return [Result.success] with the newly created [Device] (including subdomain and passkey),
     *         or [Result.failure] with an exception on error.
     */
    suspend fun registerDevice(macAddress: String): Result<Device> {
        return executeWithRetry {
            val token = authRepository.getAccessToken()
            Log.d(TAG, "registerDevice: token present=${token != null}, length=${token?.length ?: 0}, first20=${token?.take(20) ?: "null"}")
            if (token == null) {
                return@executeWithRetry Result.failure(Exception("Not authenticated"))
            }

            val response = httpClient.post("${ApiConstants.SUPABASE_URL}/rest/v1/embedded") {
                header("apikey", ApiConstants.SUPABASE_ANON_KEY)
                header("Authorization", "Bearer $token")
                header("Prefer", "return=representation")
                setBody(RegisterDeviceRequest(macAddress = macAddress))
            }

            Log.d(TAG, "registerDevice: response status=${response.status}")
            if (response.status.isSuccess()) {
                val devices: List<Device> = response.body()
                Log.d(TAG, "registerDevice: got ${devices.size} devices in response")
                if (devices.isNotEmpty()) {
                    Result.success(devices.first())
                } else {
                    Result.failure(Exception("Empty response from device registration"))
                }
            } else {
                val errorBody = try { response.body<String>() } catch (_: Exception) { "unable to read" }
                Log.e(TAG, "registerDevice: failed with ${response.status}, body=$errorBody")
                Result.failure(Exception("Failed to register device: ${response.status}"))
            }
        }
    }

    /**
     * Executes an API call with automatic token refresh on 401 responses.
     *
     * If the first attempt fails due to an expired token, this method
     * refreshes the access token and retries the operation once.
     *
     * @param block The suspending lambda containing the API call.
     * @return The result from the API call.
     */
    private suspend fun <T> executeWithRetry(block: suspend () -> Result<T>): Result<T> {
        val result = try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "executeWithRetry: block threw exception: ${e.message}")
            Result.failure(e)
        }
        if (result.isFailure) {
            val exception = result.exceptionOrNull()
            Log.d(TAG, "executeWithRetry: initial call failed: ${exception?.message}")
            if (exception?.message?.contains("401") == true ||
                exception?.message?.contains("Not authenticated") == true
            ) {
                Log.d(TAG, "executeWithRetry: detected 401, attempting token refresh...")
                val refreshResult = authRepository.refreshToken()
                Log.d(TAG, "executeWithRetry: refresh result: success=${refreshResult.isSuccess}, error=${refreshResult.exceptionOrNull()?.message}")
                if (refreshResult.isSuccess) {
                    Log.d(TAG, "executeWithRetry: retrying with new token...")
                    return try {
                        block()
                    } catch (e: Exception) {
                        Log.e(TAG, "executeWithRetry: retry also failed: ${e.message}")
                        Result.failure(e)
                    }
                } else {
                    Log.e(TAG, "executeWithRetry: token refresh failed, returning original error")
                }
            }
        }
        return result
    }
}

/** Request body for registering a new device */
@Serializable
data class RegisterDeviceRequest(
    @kotlinx.serialization.SerialName("mac_address")
    val macAddress: String
)
