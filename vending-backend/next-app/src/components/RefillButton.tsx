"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { createClient } from "@/lib/supabase/browser";
import { RefreshCw } from "lucide-react";

export function RefillButton({ machineId }: { machineId: string }) {
  const [loading, setLoading] = useState(false);
  const router = useRouter();
  const supabase = createClient();

  async function handleRefill() {
    setLoading(true);

    // Set all coils' current_stock to capacity
    const { data: coils } = await supabase
      .from("machine_coils")
      .select("id, capacity")
      .eq("machine_id", machineId);

    if (coils && coils.length > 0) {
      await Promise.all(
        coils.map((coil) =>
          supabase
            .from("machine_coils")
            .update({ current_stock: coil.capacity })
            .eq("id", coil.id)
        )
      );
    }

    // Update refilled_at timestamp
    await supabase
      .from("machines")
      .update({ refilled_at: new Date().toISOString() })
      .eq("id", machineId);

    setLoading(false);
    router.refresh();
  }

  return (
    <button
      onClick={handleRefill}
      disabled={loading}
      className="flex items-center gap-1.5 rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
    >
      <RefreshCw size={14} className={loading ? "animate-spin" : ""} />
      {loading ? "Refilling..." : "Refill All"}
    </button>
  );
}
