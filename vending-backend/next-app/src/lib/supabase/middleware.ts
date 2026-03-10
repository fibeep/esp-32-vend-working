/**
 * JWT authentication middleware helper for Next.js API routes.
 *
 * Extracts the `Authorization: Bearer <token>` header from incoming
 * requests and validates the token using Supabase's `auth.getUser()`.
 *
 * If the token is missing or invalid, the helper returns a 401 response
 * that the route handler can return immediately. Otherwise it returns
 * the authenticated user object and the raw auth header string for
 * passing to `createAuthClient`.
 */

import { NextRequest, NextResponse } from "next/server";
import { createAuthClient } from "./client";
import type { User } from "@supabase/supabase-js";

/** Successful authentication result. */
export interface AuthSuccess {
  /** The Supabase `User` object extracted from the JWT. */
  user: User;
  /** The raw `Authorization` header value (e.g. "Bearer eyJ..."). */
  authHeader: string;
}

/** Failed authentication result. */
export interface AuthFailure {
  /** A NextResponse with status 401 ready to return from the route handler. */
  error: NextResponse;
}

/** Discriminated union: either a success with `user` or a failure with `error`. */
export type AuthResult =
  | { ok: true; data: AuthSuccess }
  | { ok: false; data: AuthFailure };

/**
 * Validates the JWT from the request's Authorization header.
 *
 * Usage in a route handler:
 * ```ts
 * const auth = await verifyAuth(request);
 * if (!auth.ok) return auth.data.error;
 * const { user, authHeader } = auth.data;
 * ```
 *
 * @param request - The incoming Next.js request
 * @returns An AuthResult indicating success or failure
 */
export async function verifyAuth(request: NextRequest): Promise<AuthResult> {
  const authHeader = request.headers.get("Authorization");

  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    return {
      ok: false,
      data: {
        error: NextResponse.json(
          { error: "Missing or malformed Authorization header" },
          { status: 401 }
        ),
      },
    };
  }

  try {
    const supabase = createAuthClient(authHeader);
    const {
      data: { user },
      error,
    } = await supabase.auth.getUser();

    if (error || !user) {
      return {
        ok: false,
        data: {
          error: NextResponse.json(
            { error: "Invalid or expired token" },
            { status: 401 }
          ),
        },
      };
    }

    return {
      ok: true,
      data: { user, authHeader },
    };
  } catch {
    return {
      ok: false,
      data: {
        error: NextResponse.json(
          { error: "Authentication service unavailable" },
          { status: 503 }
        ),
      },
    };
  }
}
