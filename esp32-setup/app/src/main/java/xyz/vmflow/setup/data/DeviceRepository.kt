package xyz.vmflow.setup.data

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Device(
    val id: String = "",
    val subdomain: String = "",
    val passkey: String = "",
    @SerialName("mac_address") val macAddress: String = "",
    val status: String = "offline",
    @SerialName("owner_id") val ownerId: String = "",
    @SerialName("machine_id") val machineId: String? = null,
    @SerialName("created_at") val createdAt: String = ""
) {
    val isOnline: Boolean get() = status == "online"
    val bleName: String get() = "$subdomain.panamavendingmachines.com"
}

@Serializable
data class Machine(
    val id: String = "",
    val name: String? = null,
    val location: String? = null,
    @SerialName("serial_number") val serialNumber: String? = null
)

class DeviceRepository(
    private val httpClient: HttpClient,
    private val authRepository: AuthRepository
) {
    companion object {
        private const val TAG = "DeviceRepository"
    }

    suspend fun getDevices(): Result<List<Device>> = withRetry {
        val token = authRepository.getAccessToken()
            ?: return@withRetry Result.failure(Exception("Not authenticated"))
        val response = httpClient.get("${ApiConstants.SUPABASE_URL}/rest/v1/embedded?select=*") {
            header("apikey", ApiConstants.SUPABASE_ANON_KEY)
            header("Authorization", "Bearer $token")
        }
        if (response.status.isSuccess()) {
            Result.success(response.body<List<Device>>())
        } else {
            Result.failure(Exception("Failed: ${response.status}"))
        }
    }

    suspend fun registerDevice(macAddress: String): Result<Device> = withRetry {
        val token = authRepository.getAccessToken()
            ?: return@withRetry Result.failure(Exception("Not authenticated"))
        val response = httpClient.post("${ApiConstants.SUPABASE_URL}/rest/v1/embedded") {
            header("apikey", ApiConstants.SUPABASE_ANON_KEY)
            header("Authorization", "Bearer $token")
            header("Prefer", "return=representation")
            setBody(RegisterDeviceRequest(macAddress = macAddress))
        }
        if (response.status.isSuccess()) {
            val devices: List<Device> = response.body()
            if (devices.isNotEmpty()) Result.success(devices.first())
            else Result.failure(Exception("Empty response"))
        } else {
            Result.failure(Exception("Failed: ${response.status}"))
        }
    }

    suspend fun createMachine(name: String, location: String?): Result<Machine> = withRetry {
        val token = authRepository.getAccessToken()
            ?: return@withRetry Result.failure(Exception("Not authenticated"))
        val response = httpClient.post("${ApiConstants.SUPABASE_URL}/rest/v1/machines") {
            header("apikey", ApiConstants.SUPABASE_ANON_KEY)
            header("Authorization", "Bearer $token")
            header("Prefer", "return=representation")
            setBody(CreateMachineRequest(
                name = name,
                location = location?.ifBlank { null }
            ))
        }
        if (response.status.isSuccess()) {
            val machines: List<Machine> = response.body()
            if (machines.isNotEmpty()) Result.success(machines.first())
            else Result.failure(Exception("Empty response"))
        } else {
            val errorBody = try { response.body<String>() } catch (_: Exception) { "" }
            Log.e(TAG, "createMachine failed: ${response.status} $errorBody")
            Result.failure(Exception("Failed: ${response.status}"))
        }
    }

    suspend fun linkDeviceToMachine(deviceId: String, machineId: String): Result<Unit> = withRetry {
        val token = authRepository.getAccessToken()
            ?: return@withRetry Result.failure(Exception("Not authenticated"))
        val response = httpClient.patch("${ApiConstants.SUPABASE_URL}/rest/v1/embedded?id=eq.$deviceId") {
            header("apikey", ApiConstants.SUPABASE_ANON_KEY)
            header("Authorization", "Bearer $token")
            setBody(LinkDeviceRequest(machineId = machineId))
        }
        if (response.status.isSuccess()) Result.success(Unit)
        else Result.failure(Exception("Failed: ${response.status}"))
    }

    suspend fun getDevice(deviceId: String): Result<Device> = withRetry {
        val token = authRepository.getAccessToken()
            ?: return@withRetry Result.failure(Exception("Not authenticated"))
        val response = httpClient.get("${ApiConstants.SUPABASE_URL}/rest/v1/embedded?id=eq.$deviceId&select=*") {
            header("apikey", ApiConstants.SUPABASE_ANON_KEY)
            header("Authorization", "Bearer $token")
        }
        if (response.status.isSuccess()) {
            val devices: List<Device> = response.body()
            if (devices.isNotEmpty()) Result.success(devices.first())
            else Result.failure(Exception("Device not found"))
        } else {
            Result.failure(Exception("Failed: ${response.status}"))
        }
    }

    private suspend fun <T> withRetry(block: suspend () -> Result<T>): Result<T> {
        val result = try { block() } catch (e: Exception) { Result.failure(e) }
        if (result.isFailure) {
            val msg = result.exceptionOrNull()?.message ?: ""
            if (msg.contains("401") || msg.contains("Not authenticated")) {
                if (authRepository.refreshToken().isSuccess) {
                    return try { block() } catch (e: Exception) { Result.failure(e) }
                }
            }
        }
        return result
    }
}

@Serializable
data class RegisterDeviceRequest(@SerialName("mac_address") val macAddress: String)

@Serializable
data class CreateMachineRequest(
    val name: String,
    val location: String? = null
)

@Serializable
data class LinkDeviceRequest(@SerialName("machine_id") val machineId: String)
