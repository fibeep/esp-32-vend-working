/**
 * GET /api/metrics
 *
 * Returns time-series metrics (currently paxcounter data) for the
 * authenticated user's devices.
 *
 * Query parameters:
 *   - subdomain (string, required) - Filter by device subdomain
 *   - limit     (number, default 100, max 1000) - Page size
 *   - offset    (number, default 0) - Pagination offset
 *   - from      (ISO string, optional) - Start date filter
 *   - to        (ISO string, optional) - End date filter
 *
 * Response (200):
 *   { data: [{ id, name, value, created_at, embedded_id }], pagination: { total, limit, offset } }
 */

import { NextRequest, NextResponse } from "next/server";
import { verifyAuth } from "@/lib/supabase/middleware";
import { createAuthClient } from "@/lib/supabase/client";

export async function GET(request: NextRequest) {
  const auth = await verifyAuth(request);
  if (!auth.ok) return auth.data.error;

  const supabase = createAuthClient(auth.data.authHeader);
  const { searchParams } = new URL(request.url);

  const subdomain = searchParams.get("subdomain");
  if (!subdomain) {
    return NextResponse.json(
      { error: "subdomain query parameter is required" },
      { status: 400 }
    );
  }

  /* Pagination */
  const limit = Math.min(
    parseInt(searchParams.get("limit") ?? "100", 10),
    1000
  );
  const offset = parseInt(searchParams.get("offset") ?? "0", 10);

  /* First resolve the embedded device ID from the subdomain */
  const { data: deviceData, error: deviceError } = await supabase
    .from("embedded")
    .select("id")
    .eq("subdomain", subdomain)
    .single();

  if (deviceError || !deviceData) {
    return NextResponse.json(
      { error: "Device not found" },
      { status: 404 }
    );
  }

  /* Build metrics query */
  let query = supabase
    .from("metrics")
    .select("id, name, value, created_at, embedded_id", { count: "exact" })
    .eq("embedded_id", deviceData.id)
    .order("created_at", { ascending: false })
    .range(offset, offset + limit - 1);

  /* Optional date range filters */
  const from = searchParams.get("from");
  if (from) {
    query = query.gte("created_at", from);
  }

  const to = searchParams.get("to");
  if (to) {
    query = query.lte("created_at", to);
  }

  const { data, error, count } = await query;

  if (error) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }

  return NextResponse.json({
    data,
    pagination: {
      total: count,
      limit,
      offset,
    },
  });
}
