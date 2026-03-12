-- ============================================================
-- Dashboard Support Migration
-- ============================================================
-- Adds inventory tracking columns to machine_coils, RLS policies,
-- and a trigger to auto-decrement stock on sale insert.
-- ============================================================

-- 1. machine_coils: add columns for inventory tracking
ALTER TABLE "public"."machine_coils"
  ADD COLUMN IF NOT EXISTS "machine_id" uuid REFERENCES "public"."machines"("id"),
  ADD COLUMN IF NOT EXISTS "owner_id" uuid,
  ADD COLUMN IF NOT EXISTS "current_stock" smallint DEFAULT 0 NOT NULL,
  ADD COLUMN IF NOT EXISTS "item_number" smallint,
  ADD COLUMN IF NOT EXISTS "price" double precision DEFAULT 0 NOT NULL;

-- 2. machines: add location
ALTER TABLE "public"."machines"
  ADD COLUMN IF NOT EXISTS "location" text;

-- 3. RLS policies for machine_coils (RLS enabled but no policies exist)
CREATE POLICY "select_own" ON "public"."machine_coils"
  FOR SELECT TO authenticated USING (auth.uid() = owner_id);
CREATE POLICY "insert_own" ON "public"."machine_coils"
  FOR INSERT TO authenticated WITH CHECK (auth.uid() = owner_id);
CREATE POLICY "update_own" ON "public"."machine_coils"
  FOR UPDATE TO authenticated USING (auth.uid() = owner_id);
CREATE POLICY "delete_own" ON "public"."machine_coils"
  FOR DELETE TO authenticated USING (auth.uid() = owner_id);

-- 4. Auto-decrement stock on sale insert
CREATE OR REPLACE FUNCTION public.decrement_stock_on_sale()
RETURNS TRIGGER AS $$
BEGIN
  UPDATE public.machine_coils
  SET current_stock = GREATEST(current_stock - 1, 0)
  WHERE machine_id = NEW.machine_id
    AND item_number = NEW.item_number;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE TRIGGER trg_decrement_stock_on_sale
  AFTER INSERT ON public.sales
  FOR EACH ROW
  EXECUTE FUNCTION public.decrement_stock_on_sale();

-- 5. Enable realtime for embedded (device status changes)
ALTER PUBLICATION supabase_realtime ADD TABLE ONLY "public"."embedded";
