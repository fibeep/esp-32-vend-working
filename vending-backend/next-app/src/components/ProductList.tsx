"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { createClient } from "@/lib/supabase/browser";
import { Plus, Pencil, Trash2, X, Check } from "lucide-react";

interface Product {
  id: string;
  name: string;
  barcode: string | null;
  cost: number;
  created_at: string;
}

export function ProductList({ products }: { products: Product[] }) {
  const [showForm, setShowForm] = useState(false);
  const [editId, setEditId] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [barcode, setBarcode] = useState("");
  const [cost, setCost] = useState("");
  const [loading, setLoading] = useState(false);
  const router = useRouter();
  const supabase = createClient();

  async function handleAdd() {
    if (!name.trim()) return;
    setLoading(true);
    await supabase.from("products").insert([{
      name: name.trim(),
      barcode: barcode.trim() || null,
      cost: parseFloat(cost) || 0,
    }]);
    setName("");
    setBarcode("");
    setCost("");
    setShowForm(false);
    setLoading(false);
    router.refresh();
  }

  async function handleUpdate() {
    if (!editId || !name.trim()) return;
    setLoading(true);
    await supabase
      .from("products")
      .update({
        name: name.trim(),
        barcode: barcode.trim() || null,
        cost: parseFloat(cost) || 0,
      })
      .eq("id", editId);
    setEditId(null);
    setName("");
    setBarcode("");
    setCost("");
    setLoading(false);
    router.refresh();
  }

  async function handleDelete(id: string) {
    setLoading(true);
    await supabase.from("products").delete().eq("id", id);
    setLoading(false);
    router.refresh();
  }

  function startEdit(product: Product) {
    setEditId(product.id);
    setName(product.name);
    setBarcode(product.barcode ?? "");
    setCost(product.cost ? product.cost.toString() : "");
    setShowForm(false);
  }

  function cancelEdit() {
    setEditId(null);
    setName("");
    setBarcode("");
    setCost("");
    setShowForm(false);
  }

  return (
    <div className="rounded-xl border border-gray-200 bg-white">
      <div className="flex items-center justify-between border-b border-gray-200 px-5 py-3">
        <span className="text-sm text-gray-500">
          {products.length} product{products.length !== 1 ? "s" : ""}
        </span>
        <button
          onClick={() => {
            setShowForm(true);
            setEditId(null);
            setName("");
            setBarcode("");
            setCost("");
          }}
          className="flex items-center gap-1.5 rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
        >
          <Plus size={14} /> Add Product
        </button>
      </div>

      {showForm && (
        <div className="flex items-end gap-3 border-b border-gray-200 bg-blue-50 px-5 py-3">
          <div className="flex-1">
            <label className="block text-xs font-medium text-gray-500 mb-1">Name</label>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm"
              placeholder="Product name"
              autoFocus
            />
          </div>
          <div className="w-28">
            <label className="block text-xs font-medium text-gray-500 mb-1">Cost</label>
            <input
              type="number"
              step="0.01"
              min="0"
              value={cost}
              onChange={(e) => setCost(e.target.value)}
              className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm"
              placeholder="0.00"
            />
          </div>
          <div className="flex-1">
            <label className="block text-xs font-medium text-gray-500 mb-1">Barcode</label>
            <input
              value={barcode}
              onChange={(e) => setBarcode(e.target.value)}
              className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm"
              placeholder="Optional"
            />
          </div>
          <button
            onClick={handleAdd}
            disabled={loading || !name.trim()}
            className="rounded-lg bg-blue-600 p-1.5 text-white hover:bg-blue-700 disabled:opacity-50"
          >
            <Check size={18} />
          </button>
          <button
            onClick={cancelEdit}
            className="rounded-lg border border-gray-300 p-1.5 text-gray-500 hover:bg-gray-50"
          >
            <X size={18} />
          </button>
        </div>
      )}

      <div className="divide-y divide-gray-100">
        {products.length === 0 && !showForm ? (
          <div className="p-8 text-center text-sm text-gray-400">
            No products yet. Add one to get started.
          </div>
        ) : (
          products.map((product) => (
            <div
              key={product.id}
              className="flex items-center justify-between px-5 py-3"
            >
              {editId === product.id ? (
                <div className="flex flex-1 items-end gap-3">
                  <div className="flex-1">
                    <input
                      value={name}
                      onChange={(e) => setName(e.target.value)}
                      className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm"
                      autoFocus
                    />
                  </div>
                  <div className="w-28">
                    <input
                      type="number"
                      step="0.01"
                      min="0"
                      value={cost}
                      onChange={(e) => setCost(e.target.value)}
                      className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm"
                      placeholder="Cost"
                    />
                  </div>
                  <div className="flex-1">
                    <input
                      value={barcode}
                      onChange={(e) => setBarcode(e.target.value)}
                      className="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm"
                      placeholder="Barcode"
                    />
                  </div>
                  <button
                    onClick={handleUpdate}
                    disabled={loading || !name.trim()}
                    className="rounded-lg bg-blue-600 p-1.5 text-white hover:bg-blue-700 disabled:opacity-50"
                  >
                    <Check size={18} />
                  </button>
                  <button
                    onClick={cancelEdit}
                    className="rounded-lg border border-gray-300 p-1.5 text-gray-500 hover:bg-gray-50"
                  >
                    <X size={18} />
                  </button>
                </div>
              ) : (
                <>
                  <div>
                    <p className="text-sm font-medium">{product.name}</p>
                    <p className="text-xs text-gray-400">
                      {product.cost > 0 && `Cost: $${product.cost.toFixed(2)}`}
                      {product.cost > 0 && product.barcode && " · "}
                      {product.barcode}
                    </p>
                  </div>
                  <div className="flex gap-1">
                    <button
                      onClick={() => startEdit(product)}
                      className="rounded-lg p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
                    >
                      <Pencil size={14} />
                    </button>
                    <button
                      onClick={() => handleDelete(product.id)}
                      className="rounded-lg p-1.5 text-gray-400 hover:bg-red-50 hover:text-red-600"
                    >
                      <Trash2 size={14} />
                    </button>
                  </div>
                </>
              )}
            </div>
          ))
        )}
      </div>
    </div>
  );
}
