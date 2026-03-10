/**
 * Server-side Supabase client factory.
 *
 * Two client variants are provided:
 *
 * 1. **Service-role client** (`createServiceClient`) -- uses the
 *    `SUPABASE_SERVICE_ROLE_KEY` and bypasses Row Level Security (RLS).
 *    This is used by the MQTT bridge and any backend logic that must
 *    operate across all rows regardless of ownership.
 *
 * 2. **Authenticated client** (`createAuthClient`) -- uses the
 *    `SUPABASE_ANON_KEY` but forwards the caller's JWT in the
 *    `Authorization` header so that Supabase RLS policies are enforced
 *    on behalf of the logged-in user. This mirrors the approach used in
 *    the original Deno edge functions.
 */

import { createClient, SupabaseClient } from "@supabase/supabase-js";

/**
 * Returns the Supabase project URL from the environment.
 * Throws at startup if the variable is missing.
 */
function getSupabaseUrl(): string {
  const url = process.env.SUPABASE_URL;
  if (!url) throw new Error("Missing SUPABASE_URL environment variable");
  return url;
}

/**
 * Creates a Supabase client that uses the **service role key**.
 *
 * This client bypasses RLS and should only be used for trusted
 * server-side operations such as the MQTT bridge inserting sales
 * on behalf of any device owner.
 *
 * @returns A SupabaseClient instance with service-role privileges
 */
export function createServiceClient(): SupabaseClient {
  const key = process.env.SUPABASE_SERVICE_ROLE_KEY;
  if (!key) throw new Error("Missing SUPABASE_SERVICE_ROLE_KEY environment variable");
  return createClient(getSupabaseUrl(), key);
}

/**
 * Creates a Supabase client scoped to a specific user session.
 *
 * The anon key is used for the initial connection, but the user's
 * JWT is passed in the `Authorization` header so that Supabase
 * applies RLS policies (e.g. "only see your own devices/sales").
 *
 * This is the same pattern used by the original Deno edge functions.
 *
 * @param authHeader - The full `Authorization: Bearer <token>` header value
 * @returns A SupabaseClient instance scoped to the authenticated user
 */
export function createAuthClient(authHeader: string): SupabaseClient {
  const anonKey = process.env.SUPABASE_ANON_KEY;
  if (!anonKey) throw new Error("Missing SUPABASE_ANON_KEY environment variable");
  return createClient(getSupabaseUrl(), anonKey, {
    global: {
      headers: { Authorization: authHeader },
    },
  });
}
