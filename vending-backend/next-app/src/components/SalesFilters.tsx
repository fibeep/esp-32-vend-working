"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

interface Props {
  machines: { id: string; name: string | null }[];
  currentChannel?: string;
  currentFrom?: string;
  currentTo?: string;
  currentMachine?: string;
}

const channels = ["cash", "yappy", "ble", "mqtt"];

export function SalesFilters({
  machines,
  currentChannel,
  currentFrom,
  currentTo,
  currentMachine,
}: Props) {
  const router = useRouter();
  const [channel, setChannel] = useState(currentChannel ?? "");
  const [from, setFrom] = useState(currentFrom ?? "");
  const [to, setTo] = useState(currentTo ?? "");
  const [machine, setMachine] = useState(currentMachine ?? "");

  function applyFilters() {
    const sp = new URLSearchParams();
    if (channel) sp.set("channel", channel);
    if (from) sp.set("from", from);
    if (to) sp.set("to", to);
    if (machine) sp.set("machine", machine);
    router.push(`/dashboard/sales?${sp.toString()}`);
  }

  function clearFilters() {
    setChannel("");
    setFrom("");
    setTo("");
    setMachine("");
    router.push("/dashboard/sales");
  }

  return (
    <div className="flex flex-wrap items-end gap-3 rounded-xl border border-gray-200 bg-white p-4">
      <div>
        <label className="block text-xs font-medium text-gray-500 mb-1">
          Channel
        </label>
        <select
          value={channel}
          onChange={(e) => setChannel(e.target.value)}
          className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm"
        >
          <option value="">All</option>
          {channels.map((c) => (
            <option key={c} value={c}>
              {c}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label className="block text-xs font-medium text-gray-500 mb-1">
          Machine
        </label>
        <select
          value={machine}
          onChange={(e) => setMachine(e.target.value)}
          className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm"
        >
          <option value="">All</option>
          {machines.map((m) => (
            <option key={m.id} value={m.id}>
              {m.name ?? m.id.slice(0, 8)}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label className="block text-xs font-medium text-gray-500 mb-1">
          From
        </label>
        <input
          type="date"
          value={from}
          onChange={(e) => setFrom(e.target.value)}
          className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm"
        />
      </div>

      <div>
        <label className="block text-xs font-medium text-gray-500 mb-1">
          To
        </label>
        <input
          type="date"
          value={to}
          onChange={(e) => setTo(e.target.value)}
          className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm"
        />
      </div>

      <button
        onClick={applyFilters}
        className="rounded-lg bg-blue-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
      >
        Filter
      </button>
      <button
        onClick={clearFilters}
        className="rounded-lg border border-gray-300 px-4 py-1.5 text-sm font-medium text-gray-600 hover:bg-gray-50"
      >
        Clear
      </button>
    </div>
  );
}
