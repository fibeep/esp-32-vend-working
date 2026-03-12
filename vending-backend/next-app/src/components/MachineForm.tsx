"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { createClient } from "@/lib/supabase/browser";
import { Check, X } from "lucide-react";

interface Device {
  id: string;
  subdomain: number;
  mac_address: string | null;
}

export function MachineForm({
  unlinkedDevices,
  onCancel,
}: {
  unlinkedDevices: Device[];
  onCancel: () => void;
}) {
  const [name, setName] = useState("");
  const [serialNumber, setSerialNumber] = useState("");
  const [location, setLocation] = useState("");
  const [deviceId, setDeviceId] = useState("");
  const [loading, setLoading] = useState(false);
  const router = useRouter();
  const supabase = createClient();

  async function handleCreate() {
    if (!name.trim()) return;
    setLoading(true);

    const { data: machine, error } = await supabase
      .from("machines")
      .insert([{
        name: name.trim(),
        serial_number: serialNumber.trim() || null,
        location: location.trim() || null,
      }])
      .select("id")
      .single();

    if (error || !machine) {
      setLoading(false);
      return;
    }

    // Link device if selected
    if (deviceId) {
      await supabase
        .from("embedded")
        .update({ machine_id: machine.id })
        .eq("id", deviceId);
    }

    setLoading(false);
    onCancel();
    router.refresh();
  }

  return (
    <div className="rounded-xl border border-blue-200 bg-blue-50 p-5 space-y-3">
      <h3 className="text-sm font-semibold text-gray-700">New Machine</h3>
      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <label className="block text-xs font-medium text-gray-500 mb-1">Name *</label>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm"
            placeholder="e.g. Office Lobby"
            autoFocus
          />
        </div>
        <div>
          <label className="block text-xs font-medium text-gray-500 mb-1">Serial Number</label>
          <input
            value={serialNumber}
            onChange={(e) => setSerialNumber(e.target.value)}
            className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm"
            placeholder="Optional"
          />
        </div>
        <div>
          <label className="block text-xs font-medium text-gray-500 mb-1">Location</label>
          <input
            value={location}
            onChange={(e) => setLocation(e.target.value)}
            className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm"
            placeholder="Optional"
          />
        </div>
        <div>
          <label className="block text-xs font-medium text-gray-500 mb-1">Assign ESP32 Device</label>
          <select
            value={deviceId}
            onChange={(e) => setDeviceId(e.target.value)}
            className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm bg-white"
          >
            <option value="">None</option>
            {unlinkedDevices.map((d) => (
              <option key={d.id} value={d.id}>
                #{d.subdomain}{d.mac_address ? ` (${d.mac_address})` : ""}
              </option>
            ))}
          </select>
        </div>
      </div>
      <div className="flex gap-2 pt-1">
        <button
          onClick={handleCreate}
          disabled={loading || !name.trim()}
          className="flex items-center gap-1.5 rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
        >
          <Check size={14} /> Create Machine
        </button>
        <button
          onClick={onCancel}
          className="flex items-center gap-1.5 rounded-lg border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-600 hover:bg-gray-50"
        >
          <X size={14} /> Cancel
        </button>
      </div>
    </div>
  );
}
