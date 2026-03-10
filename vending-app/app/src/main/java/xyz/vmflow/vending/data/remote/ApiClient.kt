package xyz.vmflow.vending.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Constants for backend API configuration.
 *
 * Defines the base URL and API prefix for all backend communication.
 * These values should be updated when deploying against different environments
 * (development, staging, production).
 */
object ApiConstants {
    /** Base URL of the Next.js backend API server */
    const val BASE_URL = "https://api.panamavendingmachines.com"

    /** API route prefix for all endpoints */
    const val API_PREFIX = "/api"

    /** Supabase project URL for direct GoTrue/PostgREST access */
    const val SUPABASE_URL = "https://luntgcliwnyvrmrqpdts.supabase.co"

    /** Supabase anonymous API key for unauthenticated access */
    const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imx1bnRnY2xpd255dnJtcnFwZHRzIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzMxMDA2MzEsImV4cCI6MjA4ODY3NjYzMX0.s5mBlh0YTXi2xhfd1vOX_R9Aof6S6D5ZXc8zK_G7074"
}

/**
 * HTTP client factory for backend API communication.
 *
 * Creates a configured Ktor [HttpClient] with:
 * - OkHttp engine for Android compatibility
 * - JSON content negotiation with lenient parsing
 * - Request logging in debug builds
 * - Default JSON content type headers
 *
 * The client is used by all repository classes to make HTTP requests
 * to the backend API.
 *
 * ## Usage
 * ```kotlin
 * val client = ApiClient.create()
 * val response = client.get("${ApiConstants.BASE_URL}/api/devices")
 * ```
 */
object ApiClient {

    /**
     * Creates a new configured [HttpClient] instance.
     *
     * Each call creates a fresh client. In production, consider reusing
     * a single client instance via dependency injection.
     *
     * @return A configured [HttpClient] ready for API calls.
     */
    fun create(): HttpClient {
        return HttpClient(OkHttp) {
            // JSON serialization/deserialization
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true     // Tolerate extra fields from the API
                    isLenient = true              // Accept non-strict JSON
                    prettyPrint = false           // Compact output
                    encodeDefaults = true         // Include default values in requests
                    coerceInputValues = true      // Coerce nulls to defaults
                })
            }

            // HTTP request/response logging (useful for debugging)
            install(Logging) {
                level = LogLevel.BODY
            }

            // Default request configuration
            defaultRequest {
                contentType(ContentType.Application.Json)
            }
        }
    }
}
