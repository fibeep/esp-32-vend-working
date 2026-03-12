import { createClient } from "@/lib/supabase/server";
import { format } from "date-fns";
import { SalesFilters } from "@/components/SalesFilters";

export const dynamic = "force-dynamic";

interface SearchParams {
  channel?: string;
  from?: string;
  to?: string;
  machine?: string;
  page?: string;
}

const PAGE_SIZE = 50;

const channelColors: Record<string, string> = {
  cash: "bg-green-100 text-green-700",
  yappy: "bg-purple-100 text-purple-700",
  ble: "bg-blue-100 text-blue-700",
  mqtt: "bg-orange-100 text-orange-700",
};

export default async function SalesPage({
  searchParams,
}: {
  searchParams: Promise<SearchParams>;
}) {
  const params = await searchParams;
  const supabase = await createClient();
  const page = parseInt(params.page ?? "1", 10);
  const offset = (page - 1) * PAGE_SIZE;

  // Build query
  let query = supabase
    .from("sales")
    .select("id, item_price, item_number, channel, created_at, machine_id", {
      count: "exact",
    })
    .order("created_at", { ascending: false })
    .range(offset, offset + PAGE_SIZE - 1);

  if (params.channel) query = query.eq("channel", params.channel);
  if (params.from) query = query.gte("created_at", params.from);
  if (params.to) query = query.lte("created_at", params.to + "T23:59:59");
  if (params.machine) query = query.eq("machine_id", params.machine);

  const [salesRes, machinesRes] = await Promise.all([
    query,
    supabase.from("machines").select("id, name"),
  ]);

  const sales = salesRes.data ?? [];
  const totalCount = salesRes.count ?? 0;
  const totalPages = Math.ceil(totalCount / PAGE_SIZE);
  const machines = machinesRes.data ?? [];

  // Calculate totals for filtered results
  const totalRevenue = sales.reduce((sum, s) => sum + (s.item_price ?? 0), 0);

  // Build machine lookup
  const machineMap: Record<string, string> = {};
  for (const m of machines) {
    machineMap[m.id] = m.name ?? "Unnamed";
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Sales</h1>
        <div className="text-sm text-gray-500">
          {totalCount} sales &middot; ${totalRevenue.toFixed(2)} revenue
        </div>
      </div>

      <SalesFilters
        machines={machines}
        currentChannel={params.channel}
        currentFrom={params.from}
        currentTo={params.to}
        currentMachine={params.machine}
      />

      <div className="overflow-hidden rounded-xl border border-gray-200 bg-white">
        <table className="w-full text-sm">
          <thead className="border-b border-gray-200 bg-gray-50">
            <tr>
              <th className="px-4 py-3 text-left font-medium text-gray-500">
                Date
              </th>
              <th className="px-4 py-3 text-left font-medium text-gray-500">
                Machine
              </th>
              <th className="px-4 py-3 text-left font-medium text-gray-500">
                Item #
              </th>
              <th className="px-4 py-3 text-left font-medium text-gray-500">
                Price
              </th>
              <th className="px-4 py-3 text-left font-medium text-gray-500">
                Channel
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {sales.length === 0 ? (
              <tr>
                <td
                  colSpan={5}
                  className="px-4 py-8 text-center text-gray-400"
                >
                  No sales found
                </td>
              </tr>
            ) : (
              sales.map((sale) => (
                <tr key={sale.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 text-gray-600">
                    {format(new Date(sale.created_at), "MMM dd, yyyy HH:mm")}
                  </td>
                  <td className="px-4 py-3">
                    {sale.machine_id
                      ? machineMap[sale.machine_id] ?? "—"
                      : "—"}
                  </td>
                  <td className="px-4 py-3">#{sale.item_number}</td>
                  <td className="px-4 py-3 font-medium">
                    ${(sale.item_price ?? 0).toFixed(2)}
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                        channelColors[sale.channel] ??
                        "bg-gray-100 text-gray-600"
                      }`}
                    >
                      {sale.channel}
                    </span>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-between">
          <span className="text-sm text-gray-500">
            Page {page} of {totalPages}
          </span>
          <div className="flex gap-2">
            {page > 1 && (
              <PaginationLink
                params={params}
                page={page - 1}
                label="Previous"
              />
            )}
            {page < totalPages && (
              <PaginationLink
                params={params}
                page={page + 1}
                label="Next"
              />
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function PaginationLink({
  params,
  page,
  label,
}: {
  params: SearchParams;
  page: number;
  label: string;
}) {
  const sp = new URLSearchParams();
  if (params.channel) sp.set("channel", params.channel);
  if (params.from) sp.set("from", params.from);
  if (params.to) sp.set("to", params.to);
  if (params.machine) sp.set("machine", params.machine);
  sp.set("page", String(page));

  return (
    <a
      href={`/dashboard/sales?${sp.toString()}`}
      className="rounded-lg border border-gray-200 bg-white px-3 py-1.5 text-sm font-medium text-gray-600 hover:bg-gray-50"
    >
      {label}
    </a>
  );
}
