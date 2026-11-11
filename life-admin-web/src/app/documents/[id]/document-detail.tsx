"use client";

import Link from "next/link";
import AuthGate from "@/components/auth-gate";
import { useEffect, useState } from "react";
import { ArrowLeft, ChevronRight, FileText, LoaderCircle } from "lucide-react";
import { ApiError, documentStatus, Document, DocumentAnalysis, getDocument, getDocumentAnalysis, getDevelopmentCapabilities, loadDevelopmentAnalysis } from "@/lib/documents";

import Sidebar from "@/components/sidebar";
import DocumentActions from "./document-actions";
import ActionPlan from "./action-plan";
import AnalysisReview from "./analysis-review";

function AnalysisList({ items, empty, ordered = false }: { items: string[]; empty: string; ordered?: boolean }) {
  if (items.length === 0) return <p className="analysis-empty">{empty}</p>;
  const List = ordered ? "ol" : "ul";
  return <List>{items.map((item, index) => <li key={index}>{item}</li>)}</List>;
}

export default function DocumentDetail({ id }: { id: string }) { return <AuthGate><DocumentDetailContent id={id} /></AuthGate>; }
function DocumentDetailContent({ id }: { id: string }) {
  const [document, setDocument] = useState<Document | null>(null);
  const [analysis, setAnalysis] = useState<DocumentAnalysis | null>(null);
  const [loading, setLoading] = useState(true);
  const analyzing = false;
  const reviewed = analysis?.confirmedContent ?? analysis;
  const [error, setError] = useState("");
  const [mockEnabled, setMockEnabled] = useState(false);
  const [loadingMock, setLoadingMock] = useState(false);
  const [progress, setProgress] = useState<{ completed: number; total: number } | null>(null);

  useEffect(() => {
    let active = true;
    async function load() {
      try {
        const metadata = await getDocument(id);
        if (!active) return;
        setDocument(metadata);
        if (metadata.status === "DELETION_PENDING") return;
        const capabilities = await getDevelopmentCapabilities().catch(() => ({ mockAnalysisEnabled: false }));
        if (!active) return;
        setMockEnabled(capabilities.mockAnalysisEnabled);
        try {
          const saved = await getDocumentAnalysis(id);
          if (active) setAnalysis(saved);
        } catch (e) {
          if (!(e instanceof ApiError && e.status === 404)) throw e;
        }
      } catch (e) {
        if (active) setError(e instanceof Error ? e.message : "Could not load this document.");
      } finally {
        if (active) setLoading(false);
      }
    }
    void load();
    return () => { active = false; };
  }, [id]);

  async function loadMock() {
    if (loadingMock || analyzing || loading) return;
    setLoadingMock(true); setError("");
    try { setAnalysis(await loadDevelopmentAnalysis(id)); }
    catch (e) { setError(e instanceof Error ? e.message : "Could not load development sample data."); }
    finally { setLoadingMock(false); }
  }

  return (
    <div className="app-shell">
      <Sidebar active="documents" />
      <div className="main-shell">
        <header className="topbar"><span>Workspace</span><ChevronRight size={14} /><span>Documents</span></header>
        <main className="document-detail">
          <Link className="back-link" href="/documents"><ArrowLeft size={16} />My Documents</Link>
          {loading && <p role="status" className="detail-loading"><LoaderCircle className="spin" size={20} />Loading document…</p>}
          {document && (
            <>
              <div className="page-heading detail-heading"><FileText size={28} /><h1>{document.displayName}</h1></div>
              <dl className="document-metadata">
                <div><dt>Upload date</dt><dd><time dateTime={document.uploadDate}>{new Date(document.uploadDate).toLocaleDateString(undefined, { month: "long", day: "numeric", year: "numeric" })}</time></dd></div>
                <div><dt>Status</dt><dd>{documentStatus(document.status)}</dd></div>
                {document.displayName !== document.originalFileName && <div><dt>Original filename</dt><dd>{document.originalFileName}</dd></div>}
              </dl>
              {document.status === "UPLOADED" && <>
              <section className="at-a-glance" aria-labelledby="glance-heading">
                <h2 id="glance-heading">At a glance</h2>{analysis && <p>{analysis.confirmedAt ? "Confirmed by you" : "Unconfirmed suggestions — check the original"}</p>}
                {analysis?.source === "DEVELOPMENT_MOCK" && <p className="sample-notice">DEVELOPMENT SAMPLE · Not real document analysis. Dates and actions are examples; review the original document.</p>}
                <dl className="glance-values">
                  <div><dt>Action required</dt><dd>{!reviewed || reviewed.actionRequired === null ? "Unclear" : reviewed.actionRequired ? "Yes" : "No"}</dd></div>
                  <div><dt>Deadline</dt><dd>{reviewed?.deadline ? <time dateTime={reviewed.deadline}>{new Date(`${reviewed.deadline}T00:00:00Z`).toLocaleDateString(undefined, { month: "long", day: "numeric", year: "numeric", timeZone: "UTC" })}</time> : "No deadline identified"}</dd></div>
                  <div><dt>Tasks</dt><dd>{progress ? `${progress.completed} of ${progress.total} completed` : "Not yet available"}</dd></div>
                </dl>
              </section>
              {!loading && <ActionPlan key={analysis?.confirmedAt ?? analysis?.id ?? "manual"} developmentEnabled={mockEnabled} documentId={id} analysis={analysis} onProgress={setProgress} developmentTools={mockEnabled && !analysis && document.originalFileName.toLowerCase() === "testrecord.pdf" ? <>
                <p>Sample data only, not real AI analysis. This does not read your document or call an AI provider.</p>
                <button className="secondary" disabled={loading || analyzing || loadingMock} onClick={loadMock}>{loadingMock ? "Loading sample…" : "Load mock analysis"}</button>
              </> : null} />}
              {!loading && <AnalysisReview documentId={id} analysis={analysis} onChange={setAnalysis} />}
              </>}
            </>
          )}
          {error && <div role="alert" className="message error">{error}</div>}
          {!loading && !document && <h1 className="unavailable-heading">Document unavailable</h1>}
          {analysis && reviewed && document?.status === "UPLOADED" && (
            <div className="analysis-results" aria-label="Document analysis results">
              <h2>{analysis.confirmedAt ? "Your confirmed information" : "AI-generated explanations and suggestions"}</h2>
              {analysis.source === "AI" && analysis.evidence?.length > 0 && <section className="analysis-card"><h3>Found in the document</h3><p>Exact excerpts from the extracted text. Verify their context in the original.</p>{analysis.evidence.map((quote, index) => <blockquote key={index}>{quote}</blockquote>)}</section>}
              {reviewed.importantDates?.length > 0 && <section className="analysis-card"><h3>Important dates</h3>{reviewed.importantDates.map((date,index) => <p key={index}>{date.label}: {date.date ?? "Unknown — check the original"}</p>)}</section>}
              <section className="analysis-card explanation"><p className="document-type"><strong>Document type:</strong> {reviewed.documentType}</p><h2>Simple summary</h2><p>{reviewed.summary}</p><h3>What this document means</h3><p>{reviewed.meaning ?? "Could not determine automatically."}</p></section>
              <section className="analysis-card"><h2>What you need</h2><AnalysisList items={reviewed.requiredDocuments} empty="No required documents identified." /></section>
              <section className="analysis-card"><h2>Next steps</h2><AnalysisList items={reviewed.nextSteps} empty="No next steps identified." ordered /></section>
              <section className="analysis-card"><h2>Important warnings</h2><AnalysisList items={reviewed.warnings} empty="No warnings identified." /></section>
            </div>
          )}
          {document && <section aria-label="Original document controls"><h2>Original document</h2><DocumentActions document={document} onChange={setDocument} /></section>}
          {document?.status === "UPLOADED" && analysis?.source !== "DEVELOPMENT_MOCK" && <p className="analysis-notice">Life Admin uses AI to help explain documents. Review important information and deadlines against the original document.</p>}
        </main>
      </div>
    </div>
  );
}
