"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { createClient } from "@/lib/supabase/browser";
import { Plus, Pencil, Trash2, Check, X, RotateCw } from "lucide-react";

interface Coil {
  id: string;
  item_number: number | null;
  product_id: string | null;
  price: number;
  current_stock: number;
  capacity: number;
}

interface Product {
  id: string;
  name: string;
}

export function CoilEditor({
  machineId,
  coils,
  products,
}: {
  machineId: string;
  coils: Coil[];
  products: Product[];
}) {
  const [showAdd, setShowAdd] = useState(false);
  const [editId, setEditId] = useState<string | null>(null);
  const [form, setForm] = useState({ itemNumber: "", productId: "", price: "", capacity: "", currentStock: "" });
  const [loading, setLoading] = useState(false);
  const router = useRouter();
  const supabase = createClient();

  function resetForm() {
    setForm({ itemNumber: "", productId: "", price: "", capacity: "", currentStock: "" });
    setEditId(null);
    setShowAdd(false);
  }

  function startEdit(coil: Coil) {
    setEditId(coil.id);
    setShowAdd(false);
    setForm({
      itemNumber: coil.item_number?.toString() ?? "",
      productId: coil.product_id ?? "",
      price: coil.price.toString(),
      capacity: coil.capacity.toString(),
      currentStock: coil.current_stock.toString(),
    });
  }

  async function handleAdd() {
    if (!form.itemNumber) return;
    setLoading(true);
    await supabase.from("machine_coils").insert([{
      machine_id: machineId,
      item_number: parseInt(form.itemNumber),
      product_id: form.productId || null,
      price: parseFloat(form.price) || 0,
      capacity: parseInt(form.capacity) || 0,
      current_stock: parseInt(form.currentStock) || 0,
    }]);
    setLoading(false);
    resetForm();
    router.refresh();
  }

  async function handleUpdate() {
    if (!editId || !form.itemNumber) return;
    setLoading(true);
    await supabase.from("machine_coils").update({
      item_number: parseInt(form.itemNumber),
      product_id: form.productId || null,
      price: parseFloat(form.price) || 0,
      capacity: parseInt(form.capacity) || 0,
      current_stock: parseInt(form.currentStock) || 0,
    }).eq("id", editId);
    setLoading(false);
    resetForm();
    router.refresh();
  }

  async function handleDelete(id: string) {
    setLoading(true);
    await supabase.from("machine_coils").delete().eq("id", id);
    setLoading(false);
    router.refresh();
  }

  async function handleRefillOne(coil: Coil) {
    setLoading(true);
    await supabase.from("machine_coils").update({ current_stock: coil.capacity }).eq("id", coil.id);
    setLoading(false);
    router.refresh();
  }

  const productMap: Record<string, string> = {};
  for (const p of products) productMap[p.id] = p.name;

  return (
    <div>
      <div className="flex items-center justify-between border-b border-gray-200 px-5 py-3">
        <h2 className="text-sm font-semibold text-gray-500 uppercase">Coil Inventory</h2>
        <button
          onClick={() => { setShowAdd(true); setEditId(null); setForm({ itemNumber: "", productId: "", price: "", capacity: "", currentStock: "" }); }}
          className="flex items-center gap-1.5 rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
        >
          <Plus size={14} /> Add Coil
        </button>
      </div>

      {showAdd && (
        <div className="border-b border-gray-200 bg-blue-50 px-5 py-3">
          <CoilFormRow
            form={form}
            setForm={setForm}
            products={products}
            onSave={handleAdd}
            onCancel={resetForm}
            loading={loading}
          />
        </div>
      )}

      {coils.length === 0 && !showAdd ? (
        <div className="p-8 text-center text-sm text-gray-400">
          No coils configured. Add one to set up the planogram.
        </div>
      ) : (
        <div className="divide-y divide-gray-100">
          {coils.map((coil) => {
            const pct = coil.capacity > 0 ? (coil.current_stock / coil.capacity) * 100 : 0;
            const isLow = coil.current_stock <= 2 && coil.capacity > 0;

            if (editId === coil.id) {
              return (
                <div key={coil.id} className="bg-blue-50 px-5 py-3">
                  <CoilFormRow
                    form={form}
                    setForm={setForm}
                    products={products}
                    onSave={handleUpdate}
                    onCancel={resetForm}
                    loading={loading}
                  />
                </div>
              );
            }

            return (
              <div key={coil.id} className="flex items-center gap-4 px-5 py-3">
                <div className="w-16 text-sm font-medium text-gray-500">
                  Slot {coil.item_number ?? "?"}
                </div>
                <div className="flex-1">
                  <p className="text-sm font-medium">
                    {coil.product_id ? productMap[coil.product_id] ?? "Unknown" : "Empty slot"}
                  </p>
                  <p className="text-xs text-gray-400">${coil.price.toFixed(2)}</p>
                </div>
                <div className="w-32">
                  <div className="flex items-center justify-between text-xs mb-1">
                    <span className={isLow ? "font-medium text-red-600" : "text-gray-500"}>
                      {coil.current_stock}/{coil.capacity}
                    </span>
                  </div>
                  <div className="h-2 overflow-hidden rounded-full bg-gray-100">
                    <div
                      className={`h-full rounded-full transition-all ${isLow ? "bg-red-500" : "bg-green-500"}`}
                      style={{ width: `${Math.min(pct, 100)}%` }}
                    />
                  </div>
                </div>
                <div className="flex gap-1">
                  <button
                    onClick={() => handleRefillOne(coil)}
                    disabled={loading || coil.current_stock >= coil.capacity}
                    className="rounded-lg p-1.5 text-gray-400 hover:bg-blue-50 hover:text-blue-600 disabled:opacity-30"
                    title="Refill this coil"
                  >
                    <RotateCw size={14} />
                  </button>
                  <button
                    onClick={() => startEdit(coil)}
                    className="rounded-lg p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
                  >
                    <Pencil size={14} />
                  </button>
                  <button
                    onClick={() => handleDelete(coil.id)}
                    className="rounded-lg p-1.5 text-gray-400 hover:bg-red-50 hover:text-red-600"
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function CoilFormRow({
  form,
  setForm,
  products,
  onSave,
  onCancel,
  loading,
}: {
  form: { itemNumber: string; productId: string; price: string; capacity: string; currentStock: string };
  setForm: (f: typeof form) => void;
  products: { id: string; name: string }[];
  onSave: () => void;
  onCancel: () => void;
  loading: boolean;
}) {
  return (
    <div className="flex flex-wrap items-end gap-3">
      <div className="w-20">
        <label className="block text-xs font-medium text-gray-500 mb-1">Item #</label>
        <input
          type="number"
          min="0"
          value={form.itemNumber}
          onChange={(e) => setForm({ ...form, itemNumber: e.target.value })}
          className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm"
          placeholder="MDB"
          autoFocus
        />
      </div>
      <div className="flex-1 min-w-[140px]">
        <label className="block text-xs font-medium text-gray-500 mb-1">Product</label>
        <select
          value={form.productId}
          onChange={(e) => setForm({ ...form, productId: e.target.value })}
          className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm bg-white"
        >
          <option value="">None</option>
          {products.map((p) => (
            <option key={p.id} value={p.id}>{p.name}</option>
          ))}
        </select>
      </div>
      <div className="w-24">
        <label className="block text-xs font-medium text-gray-500 mb-1">Price</label>
        <input
          type="number"
          step="0.01"
          min="0"
          value={form.price}
          onChange={(e) => setForm({ ...form, price: e.target.value })}
          className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm"
          placeholder="0.00"
        />
      </div>
      <div className="w-20">
        <label className="block text-xs font-medium text-gray-500 mb-1">Capacity</label>
        <input
          type="number"
          min="0"
          value={form.capacity}
          onChange={(e) => setForm({ ...form, capacity: e.target.value })}
          className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm"
          placeholder="10"
        />
      </div>
      <div className="w-20">
        <label className="block text-xs font-medium text-gray-500 mb-1">Stock</label>
        <input
          type="number"
          min="0"
          value={form.currentStock}
          onChange={(e) => setForm({ ...form, currentStock: e.target.value })}
          className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm"
          placeholder="0"
        />
      </div>
      <button
        onClick={onSave}
        disabled={loading || !form.itemNumber}
        className="rounded-lg bg-blue-600 p-1.5 text-white hover:bg-blue-700 disabled:opacity-50"
      >
        <Check size={18} />
      </button>
      <button
        onClick={onCancel}
        className="rounded-lg border border-gray-300 p-1.5 text-gray-500 hover:bg-gray-50"
      >
        <X size={18} />
      </button>
    </div>
  );
}
