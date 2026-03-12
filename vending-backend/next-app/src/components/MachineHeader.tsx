"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { createClient } from "@/lib/supabase/browser";
import { Pencil, Check, X } from "lucide-react";

interface Machine {
  id: string;
  name: string | null;
  serial_number: string | null;
  location: string | null;
}

export function MachineHeader({
  machine,
  totalRevenue,
  totalProfit,
}: {
  machine: Machine;
  totalRevenue: number;
  totalProfit: number;
}) {
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(machine.name ?? "");
  const [serialNumber, setSerialNumber] = useState(machine.serial_number ?? "");
  const [location, setLocation] = useState(machine.location ?? "");
  const [loading, setLoading] = useState(false);
  const router = useRouter();
  const supabase = createClient();

  async function handleSave() {
    setLoading(true);
    await supabase
      .from("machines")
      .update({
        name: name.trim() || null,
        serial_number: serialNumber.trim() || null,
        location: location.trim() || null,
      })
      .eq("id", machine.id);
    setLoading(false);
    setEditing(false);
    router.refresh();
  }

  if (editing) {
    return (
      <div className="rounded-xl border border-blue-200 bg-blue-50 p-5 space-y-3">
        <div className="grid gap-3 sm:grid-cols-3">
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">Name</label>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm"
              autoFocus
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">Serial Number</label>
            <input
              value={serialNumber}
              onChange={(e) => setSerialNumber(e.target.value)}
              className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">Location</label>
            <input
              value={location}
              onChange={(e) => setLocation(e.target.value)}
              className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm"
            />
          </div>
        </div>
        <div className="flex gap-2">
          <button
            onClick={handleSave}
            disabled={loading}
            className="flex items-center gap-1.5 rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            <Check size={14} /> Save
          </button>
          <button
            onClick={() => setEditing(false)}
            className="flex items-center gap-1.5 rounded-lg border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-600 hover:bg-gray-50"
          >
            <X size={14} /> Cancel
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex items-center justify-between">
      <div className="flex items-center gap-3">
        <div>
          <h1 className="text-2xl font-bold">
            {machine.name ?? "Unnamed Machine"}
          </h1>
          <p className="text-sm text-gray-500">
            {[machine.location, machine.serial_number ? `SN: ${machine.serial_number}` : null]
              .filter(Boolean)
              .join(" · ") || "No details"}
          </p>
        </div>
        <button
          onClick={() => setEditing(true)}
          className="rounded-lg p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
        >
          <Pencil size={16} />
        </button>
      </div>
      <div className="text-right">
        <p className="text-sm text-gray-500">Revenue / Profit</p>
        <p className="text-xl font-bold">
          ${totalRevenue.toFixed(2)}{" "}
          <span className={`text-base ${totalProfit >= 0 ? "text-green-600" : "text-red-600"}`}>
            ({totalProfit >= 0 ? "+" : ""}${totalProfit.toFixed(2)})
          </span>
        </p>
      </div>
    </div>
  );
}
