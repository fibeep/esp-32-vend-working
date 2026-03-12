ALTER TABLE "public"."products"
  ADD COLUMN IF NOT EXISTS "cost" double precision DEFAULT 0 NOT NULL;
