import { createClient } from "@/lib/supabase/server";
import { notFound } from "next/navigation";
import { RefillButton } from "@/components/RefillButton";
import { MachineDetailTabs } from "@/components/MachineDetailTabs";
import { MachineHeader } from "@/components/MachineHeader";

export const dynamic = "force-dynamic";

const channelColors: Record<string, string> = {
  cash: "bg-green-100 text-green-700",
  yappy: "bg-purple-100 text-purple-700",
  ble: "bg-blue-100 text-blue-700",
  mqtt: "bg-orange-100 text-orange-700",
};

export default async function MachineDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const supabase = await createClient();

  const [machineRes, coilsRes, salesRes, deviceRes, productsRes, unlinkedRes] =
    await Promise.all([
      supabase.from("machines").select("*").eq("id", id).single(),
      supabase
        .from("machine_coils")
        .select("*")
        .eq("machine_id", id)
        .order("item_number", { ascending: true }),
      supabase
        .from("sales")
        .select("id, item_price, item_number, channel, created_at")
        .eq("machine_id", id)
        .order("created_at", { ascending: false })
        .limit(100),
      supabase
        .from("embedded")
        .select("id, subdomain, mac_address, status, status_at")
        .eq("machine_id", id)
        .limit(1),
      supabase.from("products").select("id, name, cost"),
      supabase
        .from("embedded")
        .select("id, subdomain, mac_address, status, status_at")
        .is("machine_id", null),
    ]);

  if (!machineRes.data) notFound();

  const machine = machineRes.data;
  const coils = coilsRes.data ?? [];
  const sales = salesRes.data ?? [];
  const device = deviceRes.data?.[0] ?? null;
  const products = productsRes.data ?? [];
  const unlinkedDevices = unlinkedRes.data ?? [];

  // Build coil → product cost lookup: item_number → product cost
  const costByItemNumber: Record<number, number> = {};
  const productCostMap: Record<string, number> = {};
  for (const p of products) {
    productCostMap[p.id] = p.cost ?? 0;
  }
  for (const coil of coils) {
    if (coil.item_number != null && coil.product_id && productCostMap[coil.product_id] != null) {
      costByItemNumber[coil.item_number] = productCostMap[coil.product_id];
    }
  }

  // Enrich sales with product cost
  const enrichedSales = sales.map((s) => ({
    ...s,
    product_cost: costByItemNumber[s.item_number] ?? 0,
  }));

  const totalRevenue = sales.reduce((sum, s) => sum + (s.item_price ?? 0), 0);
  const totalCost = enrichedSales.reduce((sum, s) => sum + (s.product_cost ?? 0), 0);
  const totalProfit = totalRevenue - totalCost;

  return (
    <div className="space-y-6">
      <MachineHeader machine={machine} totalRevenue={totalRevenue} totalProfit={totalProfit} />

      <MachineDetailTabs
        machineId={id}
        coils={coils}
        sales={enrichedSales}
        device={device}
        unlinkedDevices={unlinkedDevices}
        products={products.map((p) => ({ id: p.id, name: p.name }))}
        channelColors={channelColors}
        refillButton={<RefillButton machineId={id} />}
      />
    </div>
  );
}
