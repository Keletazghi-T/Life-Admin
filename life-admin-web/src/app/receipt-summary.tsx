"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { currentYear, receiptSummary, ReceiptSummary } from "@/lib/receipts";
export default function DashboardReceiptSummary() {
  const [summary, setSummary] = useState<ReceiptSummary | null>(null);
  const [error, setError] = useState(false);
  useEffect(() => { let active = true; receiptSummary(currentYear()).then(data => { if (active) setSummary(data); }).catch(() => { if (active) setError(true); }); return () => { active = false; }; }, []);
  return <section className="receipt-dashboard" aria-labelledby="dashboard-receipts"><div><h2 id="dashboard-receipts">Receipts</h2>{summary ? <p>Receipts this year: <strong>{summary.receiptCount}</strong> · Potentially tax relevant: <strong>{summary.taxRelevantCount}</strong></p> : <p role={error ? "alert" : "status"}>{error ? "Receipt summary unavailable. Open Receipts to try again." : "Loading receipt summary…"}</p>}</div><Link href="/receipts">View receipts →</Link></section>;
}
