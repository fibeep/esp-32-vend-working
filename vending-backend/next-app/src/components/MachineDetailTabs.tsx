"use client";

import { useState } from "react";
import { format } from "date-fns";
import { CoilEditor } from "./CoilEditor";
import { DevicePicker } from "./DevicePicker";

interface Coil {
  id: string;
  item_number: number | null;
  product_id: string | null;
  price: number;
  current_stock: number;
  capacity: number;
}

interface Sale {
  id: string;
  item_price: number;
  item_number: number;
  channel: string;
  created_at: string;
  product_cost?: number;
}

interface Device {
  id: string;
  subdomain: number;
  mac_address: string | null;
  status: string;
  status_at: string;
}

interface Product {
  id: string;
  name: string;
}

interface Props {
  machineId: string;
  coils: Coil[];
  sales: Sale[];
  device: Device | null;
  unlinkedDevices: Device[];
  products: Product[];
  channelColors: Record<string, string>;
  refillButton: React.ReactNode;
}

type Tab = "inventory" | "sales" | "device";

export function MachineDetailTabs({
  machineId,
  coils,
  sales,
  device,
  unlinkedDevices,
  products,
  channelColors,
  refillButton,
}: Props) {
  const [activeTab, setActiveTab] = useState<Tab>("inventory");

  const tabs: { key: Tab; label: string }[] = [
    { key: "inventory", label: `Inventory (${coils.length})` },
    { key: "sales", label: `Sales (${sales.length})` },
    { key: "device", label: "Device" },
  ];

  return (
    <>
      <div className="flex gap-1 rounded-lg border border-gray-200 bg-white p-1">
        {tabs.map((tab) => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={`flex-1 rounded-md px-4 py-2 text-sm font-medium transition-colors ${
              activeTab === tab.key
                ? "bg-blue-50 text-blue-700"
                : "text-gray-500 hover:text-gray-700"
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {activeTab === "inventory" && (
        <div className="rounded-xl border border-gray-200 bg-white">
          <div className="flex items-center justify-end border-b border-gray-200 px-5 py-3">
            {refillButton}
          </div>
          <CoilEditor machineId={machineId} coils={coils} products={products} />
        </div>
      )}

      {activeTab === "sales" && (
        <div className="overflow-hidden rounded-xl border border-gray-200 bg-white">
          <table className="w-full text-sm">
            <thead className="border-b border-gray-200 bg-gray-50">
              <tr>
                <th className="px-4 py-3 text-left font-medium text-gray-500">Date</th>
                <th className="px-4 py-3 text-left font-medium text-gray-500">Item #</th>
                <th className="px-4 py-3 text-left font-medium text-gray-500">Price</th>
                <th className="px-4 py-3 text-left font-medium text-gray-500">Cost</th>
                <th className="px-4 py-3 text-left font-medium text-gray-500">Profit</th>
                <th className="px-4 py-3 text-left font-medium text-gray-500">Channel</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {sales.length === 0 ? (
                <tr>
                  <td colSpan={6} className="px-4 py-8 text-center text-gray-400">
                    No sales for this machine
                  </td>
                </tr>
              ) : (
                sales.map((sale) => {
                  const cost = sale.product_cost ?? 0;
                  const profit = (sale.item_price ?? 0) - cost;
                  return (
                    <tr key={sale.id} className="hover:bg-gray-50">
                      <td className="px-4 py-3 text-gray-600">
                        {format(new Date(sale.created_at), "MMM dd, yyyy HH:mm")}
                      </td>
                      <td className="px-4 py-3">#{sale.item_number}</td>
                      <td className="px-4 py-3 font-medium">
                        ${(sale.item_price ?? 0).toFixed(2)}
                      </td>
                      <td className="px-4 py-3 text-gray-500">
                        ${cost.toFixed(2)}
                      </td>
                      <td className={`px-4 py-3 font-medium ${profit > 0 ? "text-green-600" : ""}`}>
                        ${profit.toFixed(2)}
                      </td>
                      <td className="px-4 py-3">
                        <span
                          className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                            channelColors[sale.channel] ?? "bg-gray-100 text-gray-600"
                          }`}
                        >
                          {sale.channel}
                        </span>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      )}

      {activeTab === "device" && (
        <div className="rounded-xl border border-gray-200 bg-white p-5">
          <DevicePicker
            machineId={machineId}
            currentDevice={device}
            unlinkedDevices={unlinkedDevices}
          />
        </div>
      )}
    </>
  );
}
