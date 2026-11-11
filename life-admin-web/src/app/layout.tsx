import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = { title: "Life Admin · Your documents, a little simpler", description: "A calmer place for life's important paperwork." };
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body>{children}</body></html>;
}
