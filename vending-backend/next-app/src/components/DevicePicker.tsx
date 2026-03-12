"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { createClient } from "@/lib/supabase/browser";
import { Wifi, WifiOff, Unlink } from "lucide-react";

interface Device {
  id: string;
  subdomain: number;
  mac_address: string | null;
  status: string;
}

export function DevicePicker({
  machineId,
  currentDevice,
  unlinkedDevices,
}: {
  machineId: string;
  currentDevice: Device | null;
  unlinkedDevices: Device[];
}) {
  const [loading, setLoading] = useState(false);
  const router = useRouter();
  const supabase = createClient();

  async function handleLink(deviceId: string) {
    setLoading(true);
    // Unlink current device first
    if (currentDevice) {
      await supabase
        .from("embedded")
        .update({ machine_id: null })
        .eq("id", currentDevice.id);
    }
    // Link new device
    await supabase
      .from("embedded")
      .update({ machine_id: machineId })
      .eq("id", deviceId);
    setLoading(false);
    router.refresh();
  }

  async function handleUnlink() {
    if (!currentDevice) return;
    setLoading(true);
    await supabase
      .from("embedded")
      .update({ machine_id: null })
      .eq("id", currentDevice.id);
    setLoading(false);
    router.refresh();
  }

  return (
    <div className="space-y-4">
      {currentDevice ? (
        <div className="space-y-3">
          <div className="flex items-center gap-3">
            {currentDevice.status === "online" ? (
              <span className="flex items-center gap-1.5 rounded-full bg-green-50 px-3 py-1 text-sm font-medium text-green-700">
                <Wifi size={14} /> Online
              </span>
            ) : (
              <span className="flex items-center gap-1.5 rounded-full bg-gray-100 px-3 py-1 text-sm font-medium text-gray-500">
                <WifiOff size={14} /> Offline
              </span>
            )}
          </div>
          <div className="grid grid-cols-2 gap-4">
            <InfoRow label="Subdomain" value={String(currentDevice.subdomain)} />
            <InfoRow label="MAC Address" value={currentDevice.mac_address ?? "—"} />
            <InfoRow label="Device ID" value={currentDevice.id.slice(0, 8) + "..."} />
          </div>
          <button
            onClick={handleUnlink}
            disabled={loading}
            className="flex items-center gap-1.5 rounded-lg border border-red-200 px-3 py-1.5 text-sm font-medium text-red-600 hover:bg-red-50 disabled:opacity-50"
          >
            <Unlink size={14} /> Unlink Device
          </button>
        </div>
      ) : (
        <div className="space-y-3">
          <p className="text-sm text-gray-400">No device linked to this machine</p>
          {unlinkedDevices.length > 0 ? (
            <div>
              <label className="block text-xs font-medium text-gray-500 mb-1">
                Link an ESP32 Device
              </label>
              <select
                onChange={(e) => {
                  if (e.target.value) handleLink(e.target.value);
                }}
                disabled={loading}
                className="w-full max-w-xs rounded-lg border border-gray-300 px-3 py-1.5 text-sm bg-white disabled:opacity-50"
                defaultValue=""
              >
                <option value="" disabled>Select a device...</option>
                {unlinkedDevices.map((d) => (
                  <option key={d.id} value={d.id}>
                    #{d.subdomain}{d.mac_address ? ` (${d.mac_address})` : ""}
                  </option>
                ))}
              </select>
            </div>
          ) : (
            <p className="text-xs text-gray-400">No unlinked devices available</p>
          )}
        </div>
      )}
    </div>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-xs font-medium text-gray-400">{label}</p>
      <p className="mt-0.5 text-sm font-medium">{value}</p>
    </div>
  );
}
