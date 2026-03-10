/**
 * Simple landing page for the vending backend API.
 * Serves as a health check and provides basic information about the service.
 */
export default function Home() {
  return (
    <main
      style={{
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        minHeight: "100vh",
        fontFamily: "system-ui, -apple-system, sans-serif",
        backgroundColor: "#0a0a0a",
        color: "#ededed",
        padding: "2rem",
      }}
    >
      <h1 style={{ fontSize: "2.5rem", marginBottom: "0.5rem" }}>
        VMFlow Vending Backend
      </h1>
      <p style={{ color: "#888", fontSize: "1.1rem", marginBottom: "2rem" }}>
        Cashless vending machine management API
      </p>
      <div
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))",
          gap: "1rem",
          maxWidth: "600px",
          width: "100%",
        }}
      >
        <StatusCard label="API" status="operational" />
        <StatusCard label="MQTT Bridge" status="operational" />
        <StatusCard label="Mosquitto" status="operational" />
      </div>
      <p
        style={{
          marginTop: "3rem",
          color: "#555",
          fontSize: "0.85rem",
        }}
      >
        API documentation available at /docs
      </p>
    </main>
  );
}

function StatusCard({ label, status }: { label: string; status: string }) {
  return (
    <div
      style={{
        border: "1px solid #333",
        borderRadius: "8px",
        padding: "1rem",
        textAlign: "center",
      }}
    >
      <div style={{ fontSize: "0.9rem", color: "#aaa" }}>{label}</div>
      <div
        style={{
          fontSize: "0.85rem",
          color: "#4ade80",
          marginTop: "0.25rem",
          textTransform: "uppercase",
          letterSpacing: "0.05em",
        }}
      >
        {status}
      </div>
    </div>
  );
}
