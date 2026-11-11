"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import AuthGate from "@/components/auth-gate";
import Sidebar from "@/components/sidebar";
import { Category, categoryName, currentYear, money } from "@/lib/receipts";
import { Breakdown, ReceiptInsights, receiptInsights } from "@/lib/receipt-insights";

const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
export default function InsightsPage() { return <AuthGate><Insights /></AuthGate>; }
function BreakdownTable({ title, rows, category = false }: { title: string; rows: Breakdown[]; category?: boolean }) {
  return <section className="privacy-card settings-section"><h2>{title}</h2>{!rows.length ? <p>No spending recorded.</p> : <div className="receipt-totals"><table><caption>{title} (USD)</caption><thead><tr><th scope="col">{category ? "Category" : "Merchant"}</th><th scope="col">Receipts</th><th scope="col">Total spent</th></tr></thead><tbody>{rows.map(row => <tr key={row.name ?? "none"}><th scope="row">{category ? categoryName(row.name as Category | null) : row.name || "Merchant not recorded"}</th><td>{row.count}</td><td>{money(row.amount)}</td></tr>)}</tbody></table></div>}</section>;
}
function Insights() {
  const [year, setYear] = useState(currentYear());
  const [years, setYears] = useState([currentYear()]);
  const [data, setData] = useState<ReceiptInsights | null>(null);
  const [error, setError] = useState(false);
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    let active = true;
    receiptInsights(year).then(result => { if (active) { setData(result); setYears([...new Set([...result.years, year])].sort((a, b) => b - a)); } }).catch(() => { if (active) setError(true); });
    return () => { active = false; };
  }, [year, refresh]);
  return <div className="app-shell"><Sidebar active="receipts" /><div className="main-shell"><header className="topbar"><Link href="/receipts">Receipts</Link><span> / Spending Insights</span></header><main className="spending-insights">
    <div className="page-heading"><h1>Spending Insights</h1><p>Understand your spending from receipts you have reviewed and confirmed.</p></div>
    <div className="receipt-filters"><div><label htmlFor="insights-year">Year</label><select id="insights-year" value={year} onChange={e => { setData(null); setError(false); setYear(Number(e.target.value)); }}>{years.map(value => <option key={value} value={value}>{value}</option>)}</select></div></div>
    <p className="receipt-note">USD only. Totals include reviewed receipts with a confirmed amount and purchase date in the selected year. Unsaved suggestions and receipts pending deletion are excluded. These totals do not determine tax deductibility.</p>
    {error ? <div role="alert" className="message error">We couldn&apos;t load your spending insights.<button className="secondary" onClick={() => { setError(false); setData(null); setRefresh(value => value + 1); }}>Try again</button></div> : !data ? <p role="status">Loading spending insights…</p> : <>
      <div className="dashboard-summary receipt-summary"><div><span>Total spending in {data.year} (USD)</span><strong>{money(data.totalAmount)}</strong></div><div><span>Reviewed receipts included</span><strong>{data.receiptCount}</strong></div><div><span>Average receipt amount (USD)</span><strong>{money(data.averageAmount)}</strong></div></div>
      {data.receiptCount === 0 && <p className="upcoming-empty">No reviewed receipts with confirmed amounts for {data.year}.</p>}
      <BreakdownTable title="Spending by category" rows={data.categories} category />
      <BreakdownTable title="Spending by merchant" rows={data.merchants} />
      <section className="privacy-card settings-section"><h2>Monthly spending</h2><div className="receipt-totals"><table><caption>Monthly totals (USD)</caption><thead><tr><th scope="col">Month</th><th scope="col">Total spent</th></tr></thead><tbody>{data.months.map(row => <tr key={row.month}><th scope="row">{months[row.month - 1]}</th><td>{money(row.amount)}</td></tr>)}</tbody></table></div></section>
    </>}
  </main></div></div>;
}
