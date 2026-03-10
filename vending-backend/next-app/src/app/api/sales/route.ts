/**
 * GET /api/sales
 *
 * Returns a paginated list of sales for the authenticated user.
 * Sales are scoped by Supabase RLS to only show the user's own transactions.
 *
 * Query parameters:
 *   - limit   (number, default 50, max 100) - Page size
 *   - offset  (number, default 0)           - Pagination offset
 *   - order   (string, default "created_at.desc") - Sort column.direction
 *   - channel (string, optional)            - Filter by sale channel (ble|mqtt|cash)
 *   - subdomain (string, optional)          - Filter by device subdomain
 *
 * Response (200):
 *   [{ id, channel, item_number, item_price, created_at, embedded_id, lat, lng }]
 */

import { NextRequest, NextResponse } from "next/server";
import { verifyAuth } from "@/lib/supabase/middleware";
import { createAuthClient } from "@/lib/supabase/client";

export async function GET(request: NextRequest) {
  const auth = await verifyAuth(request);
  if (!auth.ok) return auth.data.error;

  const supabase = createAuthClient(auth.data.authHeader);
  const { searchParams } = new URL(request.url);

  /* Pagination */
  const limit = Math.min(
    parseInt(searchParams.get("limit") ?? "50", 10),
    100
  );
  const offset = parseInt(searchParams.get("offset") ?? "0", 10);

  /* Sorting -- defaults to newest first */
  const orderParam = searchParams.get("order") ?? "created_at.desc";
  const [orderColumn, orderDirection] = orderParam.split(".");
  const ascending = orderDirection === "asc";

  /* Build query with embedded device info joined via foreign key */
  let query = supabase
    .from("sales")
    .select("id, channel, item_number, item_price, created_at, embedded_id, lat, lng, embedded:embedded_id(subdomain, status)", { count: "exact" })
    .order(orderColumn || "created_at", { ascending })
    .range(offset, offset + limit - 1);

  /* Optional filters */
  const channel = searchParams.get("channel");
  if (channel) {
    query = query.eq("channel", channel);
  }

  const subdomain = searchParams.get("subdomain");
  if (subdomain) {
    /* Join filter: only sales whose related embedded device has this subdomain */
    query = query.eq("embedded.subdomain", subdomain);
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
