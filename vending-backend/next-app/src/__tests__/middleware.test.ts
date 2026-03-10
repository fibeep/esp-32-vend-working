/**
 * Tests for the JWT authentication middleware helper.
 *
 * These tests verify that:
 *   1. Missing Authorization header returns 401
 *   2. Malformed Authorization header returns 401
 *   3. Invalid/expired tokens return 401
 *   4. Valid tokens return the user object
 */

import { describe, it, expect, vi, beforeEach } from "vitest";
import { NextRequest } from "next/server";

/* Mock the Supabase client before importing the middleware */
const mockGetUser = vi.fn();
vi.mock("@supabase/supabase-js", () => ({
  createClient: () => ({
    auth: {
      getUser: mockGetUser,
    },
  }),
}));

/* Set environment variables before importing modules that read them */
process.env.SUPABASE_URL = "https://test.supabase.co";
process.env.SUPABASE_ANON_KEY = "test-anon-key";

/* Import after mocking */
import { verifyAuth } from "@/lib/supabase/middleware";

/**
 * Helper to create a NextRequest with the given Authorization header.
 */
function makeRequest(authHeader?: string): NextRequest {
  const headers = new Headers();
  if (authHeader) {
    headers.set("Authorization", authHeader);
  }
  return new NextRequest("http://localhost:3000/api/test", { headers });
}

describe("verifyAuth middleware", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("returns 401 when Authorization header is missing", async () => {
    const result = await verifyAuth(makeRequest());
    expect(result.ok).toBe(false);
    if (!result.ok) {
      const response = result.data.error;
      expect(response.status).toBe(401);
      const body = await response.json();
      expect(body.error).toContain("Missing");
    }
  });

  it("returns 401 when Authorization header does not start with Bearer", async () => {
    const result = await verifyAuth(makeRequest("Basic abc123"));
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.data.error.status).toBe(401);
    }
  });

  it("returns 401 when the token is invalid", async () => {
    mockGetUser.mockResolvedValue({
      data: { user: null },
      error: { message: "Invalid token" },
    });

    const result = await verifyAuth(makeRequest("Bearer invalid-token"));
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.data.error.status).toBe(401);
    }
  });

  it("returns the user when the token is valid", async () => {
    const mockUser = {
      id: "user-uuid-123",
      email: "test@example.com",
      aud: "authenticated",
    };

    mockGetUser.mockResolvedValue({
      data: { user: mockUser },
      error: null,
    });

    const result = await verifyAuth(makeRequest("Bearer valid-token"));
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.data.user.id).toBe("user-uuid-123");
      expect(result.data.user.email).toBe("test@example.com");
      expect(result.data.authHeader).toBe("Bearer valid-token");
    }
  });

  it("returns 401 when getUser returns null user without error", async () => {
    mockGetUser.mockResolvedValue({
      data: { user: null },
      error: null,
    });

    const result = await verifyAuth(makeRequest("Bearer some-token"));
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.data.error.status).toBe(401);
    }
  });
});
