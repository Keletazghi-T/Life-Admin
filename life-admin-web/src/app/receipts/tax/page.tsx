"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { ChevronRight } from "lucide-react";
import AuthGate from "@/components/auth-gate";
import Sidebar from "@/components/sidebar";
import { categoryName, currentYear, money } from "@/lib/receipts";
import { exportReceipts, taxOverview, taxRecords, TaxOverview, TaxRecords } from "@/lib/receipt-tax";
import { formatDate } from "@/lib/reminders";
export default function TaxPage() { return <AuthGate><Content /></AuthGate>; }
function Content() {
  const [year, setYear] = useState(currentYear);
  const [inputYear, setInputYear] = useState(String(year));
  const [retry, setRetry] = useState(0);
  return <div className="app-shell"><Sidebar active="receipts" /><div className="main-shell"><header className="topbar"><span>Workspace</span><ChevronRight size={14} /><span>Tax organization</span></header><main className="tax-page">
    <Link className="back-link tax-controls" href="/receipts">← Receipts</Link>
    <div className="page-heading tax-screen-heading"><h1>Tax organization</h1><p>Organize your saved receipts for tax preparation.</p></div>
    <form className="tax-year-form" onSubmit={e => { e.preventDefault(); setYear(Number(inputYear)); setRetry(value => value + 1); }}><label htmlFor="tax-year">Tax year</label><input id="tax-year" type="number" required min={1900} max={9998} value={inputYear} onChange={e => setInputYear(e.target.value)} /><button className="secondary">Review year</button></form>
    <Year key={`${year}-${retry}`} year={year} onRetry={() => setRetry(value => value + 1)} />
  </main></div></div>;
}
function Year({ year, onRetry }: { year: number; onRetry: () => void }) {
  const [data, setData] = useState<TaxOverview | null>(null);
  const [error, setError] = useState("");
  const [scope, setScope] = useState("all");
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState("");
  const [filter, setFilter] = useState("all");
  const [sort, setSort] = useState("date-desc");
  const [page, setPage] = useState(0);
  useEffect(() => { let active = true; taxOverview(year).then(result => { if (active) setData(result); }).catch(() => { if (active) setError("We couldn't load your tax organization records. Please try again."); }); return () => { active = false; }; }, [year]);
  async function download() { if (exporting) return; setExporting(true); setExportError(""); try { await exportReceipts(year, scope); } catch (e) { setExportError(e instanceof Error ? e.message : "Export failed. Please try again."); } finally { setExporting(false); } }
  if (error) return <div role="alert" className="message error">{error}<button className="secondary" onClick={onRetry}>Try again</button></div>;
  if (!data) return <p role="status">Loading tax organization…</p>;
  const summary = data.summary;
  return <>
    <div className="tax-print-heading"><strong>Life Admin</strong><h1>Tax Organization — {year}</h1></div>
    <section aria-label="Year summary" className="dashboard-summary tax-summary"><div><span>Total receipts — {year}</span><strong>{summary.receiptCount}</strong></div><div><span>Total recorded amount (USD)</span><strong>{money(summary.totalAmount)}</strong></div><div><span>Potentially tax relevant receipts</span><strong>{summary.taxRelevantCount}</strong></div><div><span>Potentially tax relevant amount (USD)</span><strong>{money(summary.taxRelevantAmount)}</strong></div></section>
    <p className="receipt-note">Totals and CSV exports include reviewed receipts only. Receipts without a purchase date appear for review in their upload year; they do not contribute to totals.</p>
    <section className="privacy-card settings-section tax-categories"><h2>Category totals — {year}</h2><p>Potentially tax-relevant receipts only.</p>
      {summary.categories.length ? <div className="receipt-totals"><table><caption>Recorded amounts in USD</caption><thead><tr><th scope="col">Category</th><th scope="col">Receipts</th><th scope="col">Amount</th></tr></thead><tbody>{summary.categories.map(group => <tr key={group.category ?? "none"}><th scope="row">{categoryName(group.category)}</th><td>{group.count}</td><td>{money(group.amount)}</td></tr>)}</tbody></table></div> : <p>No potentially tax-relevant receipts for this year.</p>}
      <p><strong>Total potentially relevant: {money(summary.taxRelevantAmount)} USD</strong></p>
    </section>
    <section className="privacy-card settings-section tax-review"><h2>Records needing review</h2><p>{data.recordsNeedingReview.length} {data.recordsNeedingReview.length === 1 ? "receipt needs" : "receipts need"} review.</p>
      {data.recordsNeedingReview.length ? <ul className="tax-review-list">{data.recordsNeedingReview.map(record => <li key={record.id}><Link href={`/receipts/${record.id}`}><strong>{record.merchant || "Uploaded receipt"}</strong><span>{record.purchaseDate ? formatDate(record.purchaseDate) : "Purchase date not entered"} · {record.reasons.join("; ")}</span><span className="tax-controls">Open receipt →</span></Link></li>)}</ul> : <p>No missing information or file issues identified.</p>}
      <small>Merchant, purchase date and amount are required when saving. File checks verify availability, size and format signature; they do not verify what a receipt says.</small>
    </section>
    <section className="privacy-card settings-section tax-export"><h2>Export and print</h2><p>Exports include reviewed receipts from the selected year, independently of the table filters below.</p><label htmlFor="export-scope">Receipts to export</label><select id="export-scope" value={scope} disabled={exporting} onChange={e => setScope(e.target.value)}><option value="all">Export all receipts</option><option value="tax-relevant">Export potentially tax relevant receipts only</option></select>
      <p>Potentially tax relevant reflects your organization choice. Life Admin does not determine whether an expense is deductible.</p><div className="document-action-buttons"><button className="primary" disabled={exporting} onClick={download}>{exporting ? "Exporting…" : "Export CSV"}</button><button className="secondary" onClick={() => window.print()}>Print summary</button></div>{exportError && <p role="alert" className="message error">{exportError}</p>}
    </section>
    <section className="tax-records settings-section"><h2>Receipts — {year}</h2><div className="receipt-filters"><div><label htmlFor="tax-filter">Show receipts</label><select id="tax-filter" value={filter} onChange={e => { setFilter(e.target.value); setPage(0); }}><option value="all">All receipts</option><option value="tax-relevant">Potentially tax relevant only</option><option value="needs-review">Needs review</option></select></div><div><label htmlFor="tax-sort">Sort receipts</label><select id="tax-sort" value={sort} onChange={e => { setSort(e.target.value); setPage(0); }}><option value="date-desc">Date: newest first</option><option value="date-asc">Date: oldest first</option><option value="merchant">Merchant</option><option value="category">Category</option><option value="amount-desc">Amount: high to low</option><option value="amount-asc">Amount: low to high</option></select></div></div>
      <Records key={`${filter}-${sort}-${page}`} year={year} filter={filter} sort={sort} page={page} onPage={setPage} />
    </section>
    <p className="receipt-note tax-disclaimer">Life Admin helps organize records for tax preparation. It does not provide tax advice or determine whether an expense is deductible.</p>
  </>;
}
function Records({ year, filter, sort, page, onPage }: { year: number; filter: string; sort: string; page: number; onPage: (page: number) => void }) {
  const [data, setData] = useState<TaxRecords | null>(null);
  const [error, setError] = useState(false);
  const [retry, setRetry] = useState(0);
  useEffect(() => { let active = true; taxRecords(year, filter, sort, page).then(result => { if (active) setData(result); }).catch(() => { if (active) setError(true); }); return () => { active = false; }; }, [year, filter, sort, page, retry]);
  if (error) return <p role="alert">We couldn&apos;t load these receipts. <button className="secondary" onClick={() => { setError(false); setRetry(value => value + 1); }}>Try again</button></p>;
  if (!data) return <p role="status">Loading receipts…</p>;
  return <><p>{data.totalCount} matching receipts</p>{data.items.length ? <div className="tax-receipt-list">{data.items.map(({ receipt, reviewReasons }) => <Link className="tax-receipt-row" href={`/receipts/${receipt.id}`} key={receipt.id}><div><h3>{receipt.merchant || receipt.originalFileName}</h3>{receipt.purchaseDate ? <time dateTime={receipt.purchaseDate}>{formatDate(receipt.purchaseDate)}</time> : <span>Purchase date not entered</span>}<p>{categoryName(receipt.category)}</p></div><div><strong>{money(receipt.amount)} USD</strong><p>Potentially tax relevant: {receipt.taxRelevant ? "Yes" : "No"}</p><small>{[receipt.description?.trim() && "Description saved", receipt.notes?.trim() && "Notes saved"].filter(Boolean).join(" · ") || "No notes or description"}</small>{reviewReasons.length > 0 && <p className="receipt-tax-label">Needs review: {reviewReasons.join("; ")}</p>}</div><span aria-hidden="true">›</span></Link>)}</div> : <p>No receipts match this year and filter.</p>}<div className="dialog-actions">{page > 0 && <button className="secondary" onClick={() => onPage(page - 1)}>Previous receipts</button>}{data.hasMore && <button className="secondary" onClick={() => onPage(page + 1)}>Next receipts</button>}</div></>;
}
