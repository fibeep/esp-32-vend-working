"use client";

import { useState } from "react";
import { Plus } from "lucide-react";
import { MachineForm } from "./MachineForm";

interface Device {
  id: string;
  subdomain: number;
  mac_address: string | null;
}

export function AddMachineButton({
  unlinkedDevices,
}: {
  unlinkedDevices: Device[];
}) {
  const [showForm, setShowForm] = useState(false);

  if (showForm) {
    return (
      <MachineForm
        unlinkedDevices={unlinkedDevices}
        onCancel={() => setShowForm(false)}
      />
    );
  }

  return (
    <button
      onClick={() => setShowForm(true)}
      className="flex items-center gap-1.5 rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
    >
      <Plus size={14} /> New Machine
    </button>
  );
}
