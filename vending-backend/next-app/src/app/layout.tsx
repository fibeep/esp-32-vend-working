import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "VMFlow Vending Backend",
  description: "Cashless vending machine management API",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
