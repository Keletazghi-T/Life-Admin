"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { ChevronRight, FileText } from "lucide-react";
import AuthGate from "@/components/auth-gate";
import Sidebar from "@/components/sidebar";
import { Document, documentStatus, listDocuments } from "@/lib/documents";
export default function DocumentsPage({ deleted }: { deleted: boolean }) { return <AuthGate><DocumentsContent deleted={deleted} /></AuthGate>; }
function DocumentsContent({ deleted }: { deleted: boolean }) {
  const [input, setInput] = useState("");
  const [search, setSearch] = useState("");
  const [sort, setSort] = useState("newest");
  return <div className="app-shell"><Sidebar active="documents" /><div className="main-shell">
    <header className="topbar"><span>Workspace</span><ChevronRight size={14} /><span>Documents</span></header>
    <main><div className="page-heading"><h1>My Documents</h1><p>Your documents, private to your account.</p></div>
      {deleted && <p role="status" className="message success">Document deleted successfully.</p>}
      <form className="document-search" role="search" onSubmit={e => { e.preventDefault(); setSearch(input.trim()); }}>
        <div><label htmlFor="document-search">Search documents</label><input id="document-search" type="search" placeholder="Search documents…" value={input} maxLength={255} onChange={e => setInput(e.target.value)} /></div>
        <button className="secondary">Search</button>
        <div><label htmlFor="document-sort">Sort documents</label><select id="document-sort" value={sort} onChange={e => setSort(e.target.value)}><option value="newest">Newest first</option><option value="oldest">Oldest first</option><option value="name-asc">Name A–Z</option><option value="name-desc">Name Z–A</option></select></div>
      </form>
      <DocumentResults key={`${search}:${sort}`} search={search} sort={sort} />
    </main>
  </div></div>;
}
function DocumentResults({ search, sort }: { search: string; sort: string }) {
  const [documents, setDocuments] = useState<Document[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [page, setPage] = useState(0);
  const [more, setMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  useEffect(() => {
    let active = true;
    listDocuments(0, search, sort).then(data => { if (active) { setDocuments(data); setMore(data.length === 50); } })
      .catch(e => { if (active) setError(e instanceof Error ? e.message : "Could not load documents."); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [search, sort]);
  async function loadMore() {
    setLoadingMore(true); setError("");
    try { const data = await listDocuments(page + 1, search, sort); setDocuments(current => [...current, ...data.filter(d => !current.some(old => old.id === d.id))]); setPage(page + 1); setMore(data.length === 50); }
    catch (e) { setError(e instanceof Error ? e.message : "Could not load more documents."); }
    finally { setLoadingMore(false); }
  }
  return <div aria-live="polite">
    {error && <p role="alert" className="message error">{error}</p>}
    {loading ? <p role="status">Loading documents…</p> : documents.length === 0 && !error ? <div className="empty-state"><h2>{search ? "No documents match your search" : "No documents uploaded"}</h2><p>{search ? "Try a different document name." : "Documents you upload on the Dashboard will appear here."}</p></div> : <div className="document-list">{documents.map(doc => <Link key={doc.id} className="document-row document-link" href={`/documents/${doc.id}`}>
      <span className="pdf-icon"><FileText size={23} /><small>PDF</small></span><div className="document-info"><h3>{doc.displayName}</h3><p>Uploaded <time dateTime={doc.uploadDate}>{new Date(doc.uploadDate).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" })}</time></p></div><span className="status">{documentStatus(doc.status)}</span><ChevronRight className="document-chevron" size={18} aria-hidden="true" />
    </Link>)}</div>}
    {more && <button className="load-more" disabled={loadingMore} onClick={loadMore}>{loadingMore ? "Loading…" : "Load more documents"}</button>}
  </div>;
}
