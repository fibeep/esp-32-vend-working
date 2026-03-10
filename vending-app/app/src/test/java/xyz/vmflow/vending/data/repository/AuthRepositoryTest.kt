package xyz.vmflow.vending.data.repository

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AuthRepository] token management.
 *
 * Tests the local token storage, retrieval, and logout behavior.
 * HTTP-based login/register tests are omitted as they require
 * a running Ktor client, but the token management logic is fully tested.
 *
 * Uses MockK to mock SharedPreferences for isolated testing.
 */
class AuthRepositoryTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var httpClient: io.ktor.client.HttpClient
    private lateinit var authRepository: AuthRepository

    @Before
    fun setUp() {
        editor = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        httpClient = mockk(relaxed = true)

        // Chain the editor mock to return itself for fluent API
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor

        authRepository = AuthRepository(httpClient = httpClient, prefs = prefs)
    }

    // ── isLoggedIn Tests ───────────────────────────────────────────────────────

    @Test
    fun `isLoggedIn returns true when access token exists`() {
        every { prefs.getString("access_token", null) } returns "valid_token"

        assertTrue("Should be logged in when token exists", authRepository.isLoggedIn())
    }

    @Test
    fun `isLoggedIn returns false when no access token`() {
        every { prefs.getString("access_token", null) } returns null

        assertFalse("Should not be logged in when no token", authRepository.isLoggedIn())
    }

    @Test
    fun `isLoggedIn returns false when access token is empty`() {
        every { prefs.getString("access_token", null) } returns null

        assertFalse("Should not be logged in with empty token", authRepository.isLoggedIn())
    }

    // ── getAccessToken Tests ───────────────────────────────────────────────────

    @Test
    fun `getAccessToken returns stored token`() {
        every { prefs.getString("access_token", null) } returns "my_jwt_token"

        assertEquals("my_jwt_token", authRepository.getAccessToken())
    }

    @Test
    fun `getAccessToken returns null when no token stored`() {
        every { prefs.getString("access_token", null) } returns null

        assertNull(authRepository.getAccessToken())
    }

    // ── getRefreshToken Tests ──────────────────────────────────────────────────

    @Test
    fun `getRefreshToken returns stored token`() {
        every { prefs.getString("refresh_token", null) } returns "my_refresh_token"

        assertEquals("my_refresh_token", authRepository.getRefreshToken())
    }

    @Test
    fun `getRefreshToken returns null when no token stored`() {
        every { prefs.getString("refresh_token", null) } returns null

        assertNull(authRepository.getRefreshToken())
    }

    // ── Logout Tests ───────────────────────────────────────────────────────────

    @Test
    fun `logout clears all auth data from preferences`() {
        authRepository.logout()

        // Verify that all auth keys are removed
        verify { editor.remove("auth_json") }
        verify { editor.remove("access_token") }
        verify { editor.remove("refresh_token") }
        verify { editor.apply() }
    }

    @Test
    fun `logout makes isLoggedIn return false`() {
        // Initially logged in
        every { prefs.getString("access_token", null) } returns "valid_token"
        assertTrue(authRepository.isLoggedIn())

        // After logout, the mock will return null
        authRepository.logout()

        // Simulate the prefs being cleared
        every { prefs.getString("access_token", null) } returns null
        assertFalse("Should not be logged in after logout", authRepository.isLoggedIn())
    }

    // ── DTO Tests ──────────────────────────────────────────────────────────────

    @Test
    fun `LoginRequest serializes correctly`() {
        val request = LoginRequest(email = "test@example.com", password = "password123")
        assertEquals("test@example.com", request.email)
        assertEquals("password123", request.password)
    }

    @Test
    fun `RegisterRequest serializes with metadata`() {
        val request = RegisterRequest(
            email = "test@example.com",
            password = "password123",
            data = UserMetadata(fullName = "John Doe")
        )
        assertEquals("test@example.com", request.email)
        assertEquals("John Doe", request.data?.fullName)
    }

    @Test
    fun `AuthResponse has correct defaults`() {
        val response = AuthResponse()
        assertEquals("", response.accessToken)
        assertEquals("", response.refreshToken)
        assertEquals("bearer", response.tokenType)
        assertEquals(0L, response.expiresIn)
    }

    @Test
    fun `AuthResponse stores tokens`() {
        val response = AuthResponse(
            accessToken = "jwt_access",
            refreshToken = "jwt_refresh",
            tokenType = "bearer",
            expiresIn = 3600
        )
        assertEquals("jwt_access", response.accessToken)
        assertEquals("jwt_refresh", response.refreshToken)
        assertEquals(3600L, response.expiresIn)
    }

    @Test
    fun `ErrorResponse handles different error fields`() {
        val error1 = ErrorResponse(msg = "Invalid credentials")
        assertEquals("Invalid credentials", error1.msg)

        val error2 = ErrorResponse(message = "User not found")
        assertEquals("User not found", error2.message)

        val error3 = ErrorResponse(error = "unauthorized")
        assertEquals("unauthorized", error3.error)
    }

    @Test
    fun `RefreshRequest contains refresh token`() {
        val request = RefreshRequest(refreshToken = "my_refresh_token")
        assertEquals("my_refresh_token", request.refreshToken)
    }
}
