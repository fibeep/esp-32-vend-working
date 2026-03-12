import { createClient } from "@/lib/supabase/server";
import {
  DollarSign,
  ShoppingCart,
  Wifi,
  AlertTriangle,
  TrendingUp,
} from "lucide-react";
import { format, subDays, startOfDay } from "date-fns";
import { RevenueChart } from "@/components/RevenueChart";
import { ChannelPieChart } from "@/components/ChannelPieChart";

export const dynamic = "force-dynamic";

async function getDashboardData() {
  const supabase = await createClient();
  const now = new Date();
  const today = startOfDay(now).toISOString();
  const thirtyDaysAgo = subDays(now, 30).toISOString();

  const [salesRes, devicesRes, coilsRes, recentRes, allSalesRes, coilProductsRes] =
    await Promise.all([
      // Today's sales
      supabase
        .from("sales")
        .select("item_price, channel, machine_id, item_number")
        .gte("created_at", today),
      // Devices online
      supabase
        .from("embedded")
        .select("id, status"),
      // Low stock coils
      supabase
        .from("machine_coils")
        .select("current_stock, capacity")
        .lte("current_stock", 2)
        .gt("capacity", 0),
      // Recent sales
      supabase
        .from("sales")
        .select("id, item_price, item_number, channel, created_at, machine_id")
        .order("created_at", { ascending: false })
        .limit(10),
      // Sales last 30 days for charts
      supabase
        .from("sales")
        .select("item_price, channel, created_at, machine_id, item_number")
        .gte("created_at", thirtyDaysAgo),
      // Coils with product cost for profit calc
      supabase
        .from("machine_coils")
        .select("machine_id, item_number, product_id, products(cost)"),
    ]);

  // Build cost lookup: "machineId:itemNumber" → product cost
  const costLookup: Record<string, number> = {};
  for (const coil of coilProductsRes.data ?? []) {
    if (coil.machine_id && coil.item_number != null) {
      const products = coil.products as { cost: number } | { cost: number }[] | null;
      const cost = Array.isArray(products) ? (products[0]?.cost ?? 0) : (products?.cost ?? 0);
      costLookup[`${coil.machine_id}:${coil.item_number}`] = cost;
    }
  }

  const todaySales = salesRes.data ?? [];
  const todayRevenue = todaySales.reduce((sum, s) => sum + (s.item_price ?? 0), 0);
  const todayCost = todaySales.reduce((sum, s) => {
    const key = `${s.machine_id}:${s.item_number}`;
    return sum + (costLookup[key] ?? 0);
  }, 0);
  const todayProfit = todayRevenue - todayCost;
  const todayCount = todaySales.length;

  const devices = devicesRes.data ?? [];
  const onlineCount = devices.filter((d) => d.status === "online").length;

  const lowStockCount = (coilsRes.data ?? []).length;

  // Channel breakdown
  const allSales = allSalesRes.data ?? [];
  const channelCounts: Record<string, number> = {};
  for (const s of allSales) {
    channelCounts[s.channel] = (channelCounts[s.channel] ?? 0) + 1;
  }
  const channelData = Object.entries(channelCounts).map(([name, value]) => ({
    name,
    value,
  }));

  // Daily revenue for chart
  const dailyMap: Record<string, number> = {};
  for (let i = 29; i >= 0; i--) {
    const day = format(subDays(now, i), "MMM dd");
    dailyMap[day] = 0;
  }
  for (const s of allSales) {
    const day = format(new Date(s.created_at), "MMM dd");
    if (day in dailyMap) {
      dailyMap[day] += s.item_price ?? 0;
    }
  }
  const revenueData = Object.entries(dailyMap).map(([date, revenue]) => ({
    date,
    revenue: Math.round(revenue * 100) / 100,
  }));

  return {
    todayRevenue,
    todayProfit,
    todayCount,
    onlineCount,
    totalDevices: devices.length,
    lowStockCount,
    recentSales: recentRes.data ?? [],
    channelData,
    revenueData,
  };
}

const channelColors: Record<string, string> = {
  cash: "bg-green-100 text-green-700",
  yappy: "bg-purple-100 text-purple-700",
  ble: "bg-blue-100 text-blue-700",
  mqtt: "bg-orange-100 text-orange-700",
};

export default async function DashboardPage() {
  const data = await getDashboardData();

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Dashboard</h1>

      {/* KPI Cards */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        <KpiCard
          label="Today's Revenue"
          value={`$${data.todayRevenue.toFixed(2)}`}
          icon={<DollarSign size={20} />}
          color="text-green-600 bg-green-50"
        />
        <KpiCard
          label="Today's Profit"
          value={`$${data.todayProfit.toFixed(2)}`}
          icon={<TrendingUp size={20} />}
          color={
            data.todayProfit >= 0
              ? "text-emerald-600 bg-emerald-50"
              : "text-red-600 bg-red-50"
          }
        />
        <KpiCard
          label="Sales Today"
          value={String(data.todayCount)}
          icon={<ShoppingCart size={20} />}
          color="text-blue-600 bg-blue-50"
        />
        <KpiCard
          label="Devices Online"
          value={`${data.onlineCount}/${data.totalDevices}`}
          icon={<Wifi size={20} />}
          color="text-teal-600 bg-teal-50"
        />
        <KpiCard
          label="Low Stock Alerts"
          value={String(data.lowStockCount)}
          icon={<AlertTriangle size={20} />}
          color={
            data.lowStockCount > 0
              ? "text-red-600 bg-red-50"
              : "text-gray-600 bg-gray-50"
          }
        />
      </div>

      {/* Charts */}
      <div className="grid gap-6 lg:grid-cols-3">
        <div className="rounded-xl border border-gray-200 bg-white p-5 lg:col-span-2">
          <h2 className="mb-4 text-sm font-semibold text-gray-500 uppercase">
            Revenue (Last 30 Days)
          </h2>
          <RevenueChart data={data.revenueData} />
        </div>
        <div className="rounded-xl border border-gray-200 bg-white p-5">
          <h2 className="mb-4 text-sm font-semibold text-gray-500 uppercase">
            Sales by Channel
          </h2>
          <ChannelPieChart data={data.channelData} />
        </div>
      </div>

      {/* Recent Sales */}
      <div className="rounded-xl border border-gray-200 bg-white p-5">
        <h2 className="mb-4 text-sm font-semibold text-gray-500 uppercase">
          Recent Sales
        </h2>
        {data.recentSales.length === 0 ? (
          <p className="text-sm text-gray-400">No sales yet</p>
        ) : (
          <div className="divide-y divide-gray-100">
            {data.recentSales.map((sale) => (
              <div
                key={sale.id}
                className="flex items-center justify-between py-2.5"
              >
                <div className="flex items-center gap-3">
                  <span
                    className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                      channelColors[sale.channel] ?? "bg-gray-100 text-gray-600"
                    }`}
                  >
                    {sale.channel}
                  </span>
                  <span className="text-sm text-gray-500">
                    Item #{sale.item_number}
                  </span>
                </div>
                <div className="flex items-center gap-4">
                  <span className="font-medium">
                    ${(sale.item_price ?? 0).toFixed(2)}
                  </span>
                  <span className="text-xs text-gray-400">
                    {format(new Date(sale.created_at), "MMM dd, HH:mm")}
                  </span>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function KpiCard({
  label,
  value,
  icon,
  color,
}: {
  label: string;
  value: string;
  icon: React.ReactNode;
  color: string;
}) {
  return (
    <div className="rounded-xl border border-gray-200 bg-white p-5">
      <div className="flex items-center justify-between">
        <span className="text-sm font-medium text-gray-500">{label}</span>
        <span className={`rounded-lg p-2 ${color}`}>{icon}</span>
      </div>
      <p className="mt-2 text-2xl font-bold">{value}</p>
    </div>
  );
}
