"use client";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useRef, useState } from "react";
import { ChevronRight } from "lucide-react";
import AuthGate from "@/components/auth-gate";
import Sidebar from "@/components/sidebar";
import { categories, categoryName, currentYear, listReceipts, money, Receipt, reviewStatusLabel, receiptSummary, ReceiptSummary } from "@/lib/receipts";
import { formatDate } from "@/lib/reminders";
import ReceiptUpload from "./receipt-upload";
export default function ReceiptsPage() { return <Suspense fallback={<p>Loading receipts…</p>}><AuthGate><ReceiptsContent /></AuthGate></Suspense>; }
function ReceiptsContent() {
  const router = useRouter();
  const params = useSearchParams();
  const [adding, setAdding] = useState(params.get("add") === "1");
  const [filters, setFilters] = useState({ search: "", year: "", category: "", sort: "newest" });
  const [query, setQuery] = useState(filters);
  const [page, setPage] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const [rows, setRows] = useState<Receipt[] | null>(null);
  const [summary, setSummary] = useState<ReceiptSummary | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [error, setError] = useState("");
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    let active = true;
    const params = new URLSearchParams({ ...query, page: String(page) }); if (!query.category) params.delete("category"); if (!query.year) params.delete("year");
    Promise.all([listReceipts(params), receiptSummary(query.year ? Number(query.year) : currentYear())]).then(([list, totals]) => { if (active) { setRows(list.items); setHasMore(list.hasMore); setSummary(totals); } }).catch(() => { if (active) setError("We couldn't load your receipts. Please try again."); });
    return () => { active = false; };
  }, [query, page, refresh]);
  function loading() { setRows(null); setSummary(null); setError(""); }
  return <div className="app-shell"><Sidebar active="receipts" /><div className="main-shell"><header className="topbar"><span>Workspace</span><ChevronRight size={14} /><span>Receipts</span></header><main>
    <div className="page-heading"><h1>Receipts</h1><p>Keep important purchase records organized throughout the year.</p></div>
    {!adding && <button className="primary" onClick={() => setAdding(true)}>+ Add receipt</button>}
    {adding && <section className="privacy-card settings-section"><h2>Add receipt</h2><p>Upload the receipt. Life Admin will try to read available details, then you can review and save its details.</p><ReceiptUpload onCancel={() => setAdding(false)} onSaved={receipt => { router.push(`/receipts/${receipt.id}`); }} /></section>}
    <p className="settings-section"><Link className="secondary" href="/receipts/insights">View spending insights</Link></p>
    <section className="privacy-card settings-section tax-discovery"><h2>Preparing for taxes?</h2><p>Review and export your organized receipt records.</p><Link className="secondary" href="/receipts/tax">Open tax organization</Link></section>
    <form className="receipt-filters" onSubmit={e => { e.preventDefault(); loading(); setPage(0); setQuery({ ...filters }); }}>
      <div><label htmlFor="receipt-search">Search receipts</label><input id="receipt-search" type="search" maxLength={200} placeholder="Merchant or description" value={filters.search} onChange={e => setFilters({ ...filters, search: e.target.value })} /></div>
      <div><label htmlFor="filter-year">Year</label><select id="filter-year" value={filters.year} onChange={e => setFilters({ ...filters, year: e.target.value })}><option value="">All years</option>{Array.from({ length: currentYear() - 1899 }, (_, index) => currentYear() - index).map(year => <option key={year} value={year}>{year}</option>)}</select></div>
      <div><label htmlFor="filter-category">Filter by category</label><select id="filter-category" value={filters.category} onChange={e => setFilters({ ...filters, category: e.target.value })}><option value="">All categories</option>{Object.entries(categories).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></div>
      <div><label htmlFor="receipt-sort">Sort receipts</label><select id="receipt-sort" value={filters.sort} onChange={e => setFilters({ ...filters, sort: e.target.value })}><option value="newest">Newest purchase first</option><option value="oldest">Oldest purchase first</option><option value="amount-high">Amount high to low</option><option value="amount-low">Amount low to high</option></select></div><button className="secondary">Apply filters</button>
    </form>
    {error ? <div role="alert" className="message error">{error}<button className="secondary" onClick={() => { loading(); setRefresh(value => value + 1); }}>Try again</button></div> : !rows ? <p role="status">Loading receipts…</p> : <>
      {summary && <><div className="dashboard-summary receipt-summary"><div><span>{summary.year === currentYear() ? "Receipts this year" : `Receipts in ${summary.year}`}</span><strong>{summary.receiptCount}</strong></div><div><span>Total recorded amount (USD)</span><strong>{money(summary.totalAmount)}</strong></div><div><span>Potentially tax relevant</span><strong>{summary.taxRelevantCount}</strong></div></div><p className="receipt-note">{!query.year && "Showing receipts across all years. "}Summary totals cover reviewed receipts in {summary.year}, independent of search/category filters. Unreviewed receipts without a purchase date appear in their upload year and are excluded from totals.</p></>}
      <section className="documents-section" aria-labelledby="receipt-list-heading"><h2 id="receipt-list-heading" ref={heading} tabIndex={-1}>Your receipts</h2>
        {rows.length === 0 ? <p className="upcoming-empty">No receipts match this year and these filters.</p> : <div className="document-list">{rows.map(receipt => <Link className="document-row receipt-row" href={`/receipts/${receipt.id}`} key={receipt.id}><div className="document-info"><h3>{receipt.merchant || receipt.originalFileName}</h3><p>{receipt.purchaseDate ? <time dateTime={receipt.purchaseDate}>{formatDate(receipt.purchaseDate)}</time> : "Purchase date not entered"} · {categoryName(receipt.category)}</p><span className="receipt-tax-label">{reviewStatusLabel(receipt.reviewStatus)}</span>{receipt.taxRelevant && <span className="receipt-tax-label">Potentially tax relevant</span>}{receipt.status === "DELETION_PENDING" && <span className="receipt-tax-label">Deletion pending</span>}</div><strong>{money(receipt.amount)}</strong><ChevronRight size={18} aria-hidden="true" /></Link>)}</div>}
        <div className="dialog-actions">{page > 0 && <button className="secondary" onClick={() => { loading(); setPage(value => value - 1); heading.current?.focus(); }}>Previous</button>}{hasMore && <button className="secondary" onClick={() => { loading(); setPage(value => value + 1); }}>Next receipts</button>}</div>
      </section>
      {summary && <section className="privacy-card settings-section" aria-labelledby="tax-heading"><h2 id="tax-heading">Tax organization</h2><h3>{summary.year} Tax Organization</h3><p>Choose a year in the Year filter above, then apply filters.</p><p>Life Admin helps organize records for tax preparation. It does not determine whether an expense is deductible.</p>
        {summary.categories.length ? <div className="receipt-totals"><table><caption>Potentially tax-relevant receipts by category (USD)</caption><thead><tr><th scope="col">Category</th><th scope="col">Receipts</th><th scope="col">Recorded amount</th></tr></thead><tbody>{summary.categories.map(group => <tr key={group.category ?? "none"}><th scope="row">{categoryName(group.category)}</th><td>{group.count}</td><td>{money(group.amount)}</td></tr>)}</tbody></table></div> : <p>No receipts marked potentially tax relevant for this year.</p>}
        <p><strong>Total potentially relevant: {money(summary.taxRelevantAmount)} USD</strong></p><p>Number of potentially relevant receipts: {summary.taxRelevantCount}<br />Receipts missing category: {summary.missingCategoryCount}<br />Receipts missing important information: {summary.missingInformationCount}</p><small>Important information means merchant, purchase date and amount; these are required when saving.</small>
      </section>}
    </>}
  </main></div></div>;
}
