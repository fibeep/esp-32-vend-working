import { createClient } from "@/lib/supabase/server";
import { ProductList } from "@/components/ProductList";

export const dynamic = "force-dynamic";

export default async function ProductsPage() {
  const supabase = await createClient();

  const { data: products } = await supabase
    .from("products")
    .select("*")
    .order("name", { ascending: true });

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Products</h1>
      <ProductList products={products ?? []} />
    </div>
  );
}
