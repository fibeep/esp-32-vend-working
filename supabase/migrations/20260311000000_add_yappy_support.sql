-- ============================================================
-- Add Yappy Payment Support
-- ============================================================
-- Extends the schema for Yappy mobile payment integration:
-- 1. Add 'yappy' to sale_channel enum
-- 2. Add external_transaction_id to sales table
-- 3. Create yappy_sessions table for bearer token caching
-- ============================================================

-- Add 'yappy' to the sale_channel enum
ALTER TYPE "public"."sale_channel" ADD VALUE IF NOT EXISTS 'yappy';

-- Add external_transaction_id column to sales (for Yappy transaction reconciliation)
ALTER TABLE "public"."sales"
  ADD COLUMN IF NOT EXISTS "external_transaction_id" text;

-- Create yappy_sessions table to cache Yappy API bearer tokens
-- Tokens are valid for ~6 hours; this avoids re-authenticating on every request
CREATE TABLE IF NOT EXISTS "public"."yappy_sessions" (
    "id" uuid DEFAULT gen_random_uuid() NOT NULL,
    "created_at" timestamptz DEFAULT now() NOT NULL,
    "token" text NOT NULL,
    "expires_at" timestamptz NOT NULL,
    CONSTRAINT "yappy_sessions_pkey" PRIMARY KEY ("id")
);
