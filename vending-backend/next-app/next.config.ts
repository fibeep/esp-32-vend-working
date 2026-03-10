import type { NextConfig } from "next";

/**
 * Next.js configuration for the vending backend API server.
 *
 * This application runs as a standalone server inside Docker.
 * The `output: "standalone"` setting produces a minimal production build
 * that includes only the files needed to run, which significantly
 * reduces the Docker image size.
 */
const nextConfig: NextConfig = {
  output: "standalone",
  /** Disable the X-Powered-By header for security */
  poweredByHeader: false,
};

export default nextConfig;
