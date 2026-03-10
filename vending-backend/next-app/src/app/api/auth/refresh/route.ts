/**
 * POST /api/auth/refresh
 *
 * Refreshes an expired JWT using a valid refresh token.
 * This is called by the Android app when the access token expires
 * to obtain a new token pair without requiring the user to re-login.
 *
 * Request body:
 *   { refresh_token: string }
 *
 * Success response (200):
 *   { access_token, refresh_token }
 *
 * Error responses:
 *   400 - Missing refresh_token or invalid token
 *   500 - Server error
 */

import { NextRequest, NextResponse } from "next/server";
import { createClient } from "@supabase/supabase-js";

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const { refresh_token } = body;

    if (!refresh_token) {
      return NextResponse.json(
        { error: "refresh_token is required" },
        { status: 400 }
      );
    }

    const supabaseUrl = process.env.SUPABASE_URL;
    const supabaseAnonKey = process.env.SUPABASE_ANON_KEY;

    if (!supabaseUrl || !supabaseAnonKey) {
      return NextResponse.json(
        { error: "Server configuration error" },
        { status: 500 }
      );
    }

    const supabase = createClient(supabaseUrl, supabaseAnonKey);

    const { data, error } = await supabase.auth.refreshSession({
      refresh_token,
    });

    if (error || !data.session) {
      return NextResponse.json(
        { error: error?.message ?? "Failed to refresh session" },
        { status: 400 }
      );
    }

    return NextResponse.json({
      access_token: data.session.access_token,
      refresh_token: data.session.refresh_token,
    });
  } catch {
    return NextResponse.json(
      { error: "Internal server error" },
      { status: 500 }
    );
  }
}
