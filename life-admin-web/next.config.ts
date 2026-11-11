import type { NextConfig } from "next";
const backend = (process.env.BACKEND_URL ?? "http://127.0.0.1:8080").replace(/\/$/, "");
let backendOrigin: URL;
try { backendOrigin = new URL(backend); } catch { throw new Error("BACKEND_URL must be a valid HTTP(S) origin."); }
if (!["http:", "https:"].includes(backendOrigin.protocol) || backendOrigin.username || backendOrigin.password || backendOrigin.search || backendOrigin.hash || backendOrigin.pathname !== "/") {
  throw new Error("BACKEND_URL must be a plain HTTP(S) origin without credentials, path, or query.");
}
const config: NextConfig = {
  // Allow the backend's bounded 60-second AI request to return its safe fallback.
  experimental: { proxyTimeout: 90_000 },
  devIndicators: { position: "top-right" },
  async rewrites() { return [{ source: "/api/:path*", destination: `${backend}/api/:path*` }]; },
};
export default config;
