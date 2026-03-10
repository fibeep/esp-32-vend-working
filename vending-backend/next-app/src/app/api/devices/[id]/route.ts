/**
 * /api/devices/[id]
 *
 * GET    - Retrieve a single device by UUID.
 * PUT    - Update device fields (e.g. mac_address).
 * DELETE - Remove a device.
 *
 * All operations are scoped to the authenticated user through Supabase RLS.
 * The `id` path parameter is the UUID primary key of the `embedded` table.
 */

import { NextRequest, NextResponse } from "next/server";
import { verifyAuth } from "@/lib/supabase/middleware";
import { createAuthClient } from "@/lib/supabase/client";

/** Route context providing the dynamic `id` segment. */
interface RouteContext {
  params: Promise<{ id: string }>;
}

/**
 * GET /api/devices/[id]
 *
 * Fetches a single embedded device by its UUID.
 *
 * Response (200): { id, subdomain, passkey, mac_address, status, ... }
 * Response (404): { error: "Device not found" }
 */
export async function GET(request: NextRequest, context: RouteContext) {
  const auth = await verifyAuth(request);
  if (!auth.ok) return auth.data.error;

  const { id } = await context.params;
  const supabase = createAuthClient(auth.data.authHeader);

  const { data, error } = await supabase
    .from("embedded")
    .select("*")
    .eq("id", id)
    .single();

  if (error || !data) {
    return NextResponse.json(
      { error: "Device not found" },
      { status: 404 }
    );
  }

  return NextResponse.json(data);
}

/**
 * PUT /api/devices/[id]
 *
 * Updates allowed fields on an embedded device.
 * Currently supports updating: mac_address, machine_id.
 *
 * Request body: { mac_address?: string, machine_id?: string }
 * Response (200): updated device object
 */
export async function PUT(request: NextRequest, context: RouteContext) {
  const auth = await verifyAuth(request);
  if (!auth.ok) return auth.data.error;

  const { id } = await context.params;

  try {
    const body = await request.json();
    const supabase = createAuthClient(auth.data.authHeader);

    /* Only allow updating specific fields */
    const updateData: Record<string, unknown> = {};
    if (body.mac_address !== undefined) updateData.mac_address = body.mac_address;
    if (body.machine_id !== undefined) updateData.machine_id = body.machine_id;

    if (Object.keys(updateData).length === 0) {
      return NextResponse.json(
        { error: "No valid fields to update" },
        { status: 400 }
      );
    }

    const { data, error } = await supabase
      .from("embedded")
      .update(updateData)
      .eq("id", id)
      .select("*")
      .single();

    if (error || !data) {
      return NextResponse.json(
        { error: error?.message ?? "Device not found" },
        { status: 404 }
      );
    }

    return NextResponse.json(data);
  } catch {
    return NextResponse.json(
      { error: "Invalid request body" },
      { status: 400 }
    );
  }
}

/**
 * DELETE /api/devices/[id]
 *
 * Deletes an embedded device by UUID.
 * RLS ensures only the device owner can delete it.
 *
 * Response (200): { success: true }
 * Response (404): { error: "Device not found or already deleted" }
 */
export async function DELETE(request: NextRequest, context: RouteContext) {
  const auth = await verifyAuth(request);
  if (!auth.ok) return auth.data.error;

  const { id } = await context.params;
  const supabase = createAuthClient(auth.data.authHeader);

  const { error, count } = await supabase
    .from("embedded")
    .delete({ count: "exact" })
    .eq("id", id);

  if (error) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }

  if (count === 0) {
    return NextResponse.json(
      { error: "Device not found or already deleted" },
      { status: 404 }
    );
  }

  return NextResponse.json({ success: true });
}
