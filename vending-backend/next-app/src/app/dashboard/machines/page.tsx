import { createClient } from "@/lib/supabase/server";
import Link from "next/link";
import { format } from "date-fns";
import { Box, Wifi, WifiOff } from "lucide-react";
import { AddMachineButton } from "@/components/MachineListClient";

export const dynamic = "force-dynamic";

export default async function MachinesPage() {
  const supabase = await createClient();

  const [machinesRes, devicesRes, salesRes, coilsRes] = await Promise.all([
    supabase.from("machines").select("*").order("created_at", { ascending: false }),
    supabase.from("embedded").select("id, machine_id, subdomain, mac_address, status"),
    supabase.from("sales").select("machine_id, item_price"),
    supabase
      .from("machine_coils")
      .select("machine_id, current_stock, capacity"),
  ]);

  const machines = machinesRes.data ?? [];
  const devices = devicesRes.data ?? [];
  const sales = salesRes.data ?? [];
  const coils = coilsRes.data ?? [];

  // Unlinked devices (no machine_id)
  const unlinkedDevices = devices
    .filter((d) => !d.machine_id)
    .map((d) => ({ id: d.id, subdomain: d.subdomain, mac_address: d.mac_address }));

  // Build lookups
  const deviceByMachine: Record<
    string,
    { subdomain: number; status: string }
  > = {};
  for (const d of devices) {
    if (d.machine_id) {
      deviceByMachine[d.machine_id] = {
        subdomain: d.subdomain,
        status: d.status,
      };
    }
  }

  const revenueByMachine: Record<string, number> = {};
  const countByMachine: Record<string, number> = {};
  for (const s of sales) {
    if (s.machine_id) {
      revenueByMachine[s.machine_id] =
        (revenueByMachine[s.machine_id] ?? 0) + (s.item_price ?? 0);
      countByMachine[s.machine_id] =
        (countByMachine[s.machine_id] ?? 0) + 1;
    }
  }

  const lowStockByMachine: Record<string, number> = {};
  for (const c of coils) {
    if (c.machine_id && c.capacity > 0 && c.current_stock <= 2) {
      lowStockByMachine[c.machine_id] =
        (lowStockByMachine[c.machine_id] ?? 0) + 1;
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Machines</h1>
        <AddMachineButton unlinkedDevices={unlinkedDevices} />
      </div>

      {machines.length === 0 ? (
        <div className="rounded-xl border border-gray-200 bg-white p-10 text-center text-gray-400">
          No machines yet. Create one to get started.
        </div>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {machines.map((machine) => {
            const device = deviceByMachine[machine.id];
            const revenue = revenueByMachine[machine.id] ?? 0;
            const saleCount = countByMachine[machine.id] ?? 0;
            const lowStock = lowStockByMachine[machine.id] ?? 0;

            return (
              <Link
                key={machine.id}
                href={`/dashboard/machines/${machine.id}`}
                className="rounded-xl border border-gray-200 bg-white p-5 transition-shadow hover:shadow-md"
              >
                <div className="flex items-start justify-between">
                  <div className="flex items-center gap-3">
                    <div className="rounded-lg bg-gray-100 p-2">
                      <Box size={20} className="text-gray-500" />
                    </div>
                    <div>
                      <h3 className="font-semibold">
                        {machine.name ?? "Unnamed"}
                      </h3>
                      {machine.location && (
                        <p className="text-xs text-gray-400">
                          {machine.location}
                        </p>
                      )}
                    </div>
                  </div>
                  {device ? (
                    device.status === "online" ? (
                      <span className="flex items-center gap-1 rounded-full bg-green-50 px-2 py-0.5 text-xs font-medium text-green-700">
                        <Wifi size={12} /> Online
                      </span>
                    ) : (
                      <span className="flex items-center gap-1 rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-500">
                        <WifiOff size={12} /> Offline
                      </span>
                    )
                  ) : (
                    <span className="rounded-full bg-yellow-50 px-2 py-0.5 text-xs font-medium text-yellow-600">
                      No device
                    </span>
                  )}
                </div>

                <div className="mt-4 grid grid-cols-3 gap-3 text-center">
                  <div>
                    <p className="text-lg font-bold">${revenue.toFixed(2)}</p>
                    <p className="text-xs text-gray-400">Revenue</p>
                  </div>
                  <div>
                    <p className="text-lg font-bold">{saleCount}</p>
                    <p className="text-xs text-gray-400">Sales</p>
                  </div>
                  <div>
                    <p
                      className={`text-lg font-bold ${
                        lowStock > 0 ? "text-red-600" : ""
                      }`}
                    >
                      {lowStock}
                    </p>
                    <p className="text-xs text-gray-400">Low stock</p>
                  </div>
                </div>

                {machine.refilled_at && (
                  <p className="mt-3 text-xs text-gray-400">
                    Refilled:{" "}
                    {format(new Date(machine.refilled_at), "MMM dd, yyyy")}
                  </p>
                )}
              </Link>
            );
          })}
        </div>
      )}
    </div>
  );
}
