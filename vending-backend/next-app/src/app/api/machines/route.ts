/**
 * /api/machines
 *
 * GET  - List all vending machines owned by the authenticated user.
 * POST - Register a new vending machine.
 *
 * Machines are physical vending units. An embedded ESP32 device is linked
 * to a machine via the `embedded.machine_id` foreign key. A machine can
 * exist without a linked device (e.g. before the ESP32 is installed).
 */

import { NextRequest, NextResponse } from "next/server";
import { verifyAuth } from "@/lib/supabase/middleware";
import { createAuthClient } from "@/lib/supabase/client";

/**
 * GET /api/machines
 *
 * Returns all machines owned by the authenticated user.
 *
 * Response (200):
 *   [{ id, name, serial_number, refilled_at, created_at }]
 */
export async function GET(request: NextRequest) {
  const auth = await verifyAuth(request);
  if (!auth.ok) return auth.data.error;

  const supabase = createAuthClient(auth.data.authHeader);

  const { data, error } = await supabase
    .from("machines")
    .select("id, name, serial_number, refilled_at, created_at")
    .order("created_at", { ascending: false });

  if (error) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }

  return NextResponse.json(data);
}

/**
 * POST /api/machines
 *
 * Registers a new vending machine.
 *
 * Request body:
 *   { name?: string, serial_number?: string }
 *
 * Response (201):
 *   { id, name, serial_number, refilled_at, created_at }
 */
export async function POST(request: NextRequest) {
  const auth = await verifyAuth(request);
  if (!auth.ok) return auth.data.error;

  try {
    const body = await request.json();
    const supabase = createAuthClient(auth.data.authHeader);

    const insertData: Record<string, unknown> = {};
    if (body.name) insertData.name = body.name;
    if (body.serial_number) insertData.serial_number = body.serial_number;

    const { data, error } = await supabase
      .from("machines")
      .insert([insertData])
      .select("id, name, serial_number, refilled_at, created_at")
      .single();

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    return NextResponse.json(data, { status: 201 });
  } catch {
    return NextResponse.json(
      { error: "Invalid request body" },
      { status: 400 }
    );
  }
}
