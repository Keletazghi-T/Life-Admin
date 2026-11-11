"use client";
import { useEffect, useRef, useState } from "react";
import { AnalysisAttempt, AnalysisContent, DocumentAnalysis, analyzeDocument, confirmDocumentAnalysis, getAnalysisAttempt, getDocumentAnalysis, retryDocumentAnalysis } from "@/lib/documents";
const empty: AnalysisContent = { documentType: "Unknown", summary: "", meaning: null, actionRequired: null, deadline: null, requiredDocuments: [], nextSteps: [], warnings: [], evidence: [], importantDates: [] };
function ReviewEditor({ analysis, onSave, busy }: { analysis: DocumentAnalysis | null; onSave: (content: AnalysisContent) => void; busy: boolean }) {
  const initial = analysis?.confirmedContent ?? analysis ?? empty;
  const [value, setValue] = useState<AnalysisContent>({ ...initial, evidence: initial.evidence ?? [], importantDates: initial.importantDates ?? [] });
  const [lists, setLists] = useState({ requiredDocuments: initial.requiredDocuments.join("\n"), nextSteps: initial.nextSteps.join("\n"), warnings: initial.warnings.join("\n") });
  return <form className="task-editor" onSubmit={event => { event.preventDefault(); onSave({ ...value, ...Object.fromEntries(Object.entries(lists).map(([key, text]) => [key, text.split("\n").map(line => line.trim()).filter(Boolean)])) }); }}>
    <h3>Review document details</h3><p>Check these details against the original. Your edits are saved separately from the AI suggestions. Confirmation does not create tasks.</p>
    <label htmlFor="analysis-type">Document type / title</label><input id="analysis-type" required maxLength={200} value={value.documentType} onChange={e => setValue({ ...value, documentType: e.target.value })} disabled={busy} />
    <label htmlFor="analysis-summary">Simple summary</label><textarea id="analysis-summary" required maxLength={8000} value={value.summary} onChange={e => setValue({ ...value, summary: e.target.value })} disabled={busy} />
    <label htmlFor="analysis-meaning">What this document means</label><textarea id="analysis-meaning" maxLength={8000} value={value.meaning ?? ""} onChange={e => setValue({ ...value, meaning: e.target.value || null })} disabled={busy} />
    <label htmlFor="analysis-action">Action appears required</label><select id="analysis-action" value={value.actionRequired === null ? "unknown" : String(value.actionRequired)} onChange={e => setValue({ ...value, actionRequired: e.target.value === "unknown" ? null : e.target.value === "true" })} disabled={busy}><option value="unknown">Unclear</option><option value="true">Yes</option><option value="false">No</option></select>
    <label htmlFor="analysis-deadline">Important deadline</label><input id="analysis-deadline" type="date" min="0001-01-01" max="9999-12-31" value={value.deadline ?? ""} onChange={e => setValue({ ...value, deadline: e.target.value || null })} disabled={busy} />
    <p>Leave uncertain dates blank. A missing date is not a promise that no deadline applies.</p>
    {([['requiredDocuments', 'Requested documents or information'], ['nextSteps', 'Recommended next steps'], ['warnings', 'Important warnings']] as const).map(([key, label]) => <div key={key}><label htmlFor={`analysis-${key}`}>{label} (one per line)</label><textarea id={`analysis-${key}`} value={lists[key]} onChange={e => setLists({ ...lists, [key]: e.target.value })} maxLength={50000} rows={3} disabled={busy} /></div>)}
    {value.importantDates.map((date, index) => <fieldset key={index}><legend>Important date {index + 1}</legend><label htmlFor={`date-label-${index}`}>Date label</label><input id={`date-label-${index}`} required maxLength={200} value={date.label} disabled={busy} onChange={e => setValue({ ...value, importantDates: value.importantDates.map((item, i) => i === index ? { ...item, label: e.target.value } : item) })} /><label htmlFor={`date-value-${index}`}>Date</label><input id={`date-value-${index}`} type="date" min="0001-01-01" max="9999-12-31" value={date.date ?? ""} disabled={busy} onChange={e => setValue({ ...value, importantDates: value.importantDates.map((item, i) => i === index ? { ...item, date: e.target.value || null } : item) })} /><p>Source note: {date.evidence}</p><button className="secondary" type="button" disabled={busy} onClick={() => setValue({ ...value, importantDates: value.importantDates.filter((_, i) => i !== index) })}>Remove date</button></fieldset>)}
    <button className="secondary" type="button" disabled={busy || value.importantDates.length >= 20} onClick={() => setValue({ ...value, importantDates: [...value.importantDates, { label: "", date: null, evidence: "Entered by user; verify against the original document." }] })}>Add important date</button>
    <button className="primary" disabled={busy}>{busy ? "Saving…" : "Confirm document details"}</button>
  </form>;
}
export default function AnalysisReview({ documentId, analysis, onChange }: { documentId: string; analysis: DocumentAnalysis | null; onChange: (analysis: DocumentAnalysis) => void }) {
  const [attempt, setAttempt] = useState<AnalysisAttempt | null>(null);
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const lock = useRef(false);
  const [retry, setRetry] = useState(false);
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState("");
  const [cooldown, setCooldown] = useState(0);
  useEffect(() => { let active = true; getAnalysisAttempt(documentId).then(value => { if (active) { setAttempt(value ?? null); setReady(true); } }).catch(() => { if (active) setError("We couldn't check the analysis status. Refresh the page or enter details manually."); }); return () => { active = false; }; }, [documentId]);
  useEffect(() => { if (!attempt) return; const tick = () => setCooldown(Math.max(0, Math.ceil((new Date(attempt.startedAt).getTime() + 30000 - Date.now()) / 1000))); tick(); const timer = setInterval(tick, 1000); return () => clearInterval(timer); }, [attempt]);
  async function run(repeating: boolean) {
    if (lock.current || !ready) return;
    lock.current = true; setBusy(true); setError(""); setRetry(false);
    try { onChange(await (repeating && attempt ? retryDocumentAnalysis(documentId, attempt.attemptId) : analyzeDocument(documentId))); }
    catch (e) { setError(e instanceof Error ? e.message : "We couldn't analyze this document. Enter the details manually."); }
    finally { try { setAttempt(await getAnalysisAttempt(documentId) ?? null); } catch { setReady(false); } lock.current = false; setBusy(false); }
  }
  async function confirm(content: AnalysisContent) {
    if (lock.current) return; lock.current = true; setBusy(true); setError("");
    try { onChange(await confirmDocumentAnalysis(documentId, content, analysis?.updatedAt ?? null)); setEditing(false); }
    catch (e) { setError(e instanceof Error ? e.message : "Could not save your details."); }
    finally { lock.current = false; setBusy(false); }
  }
  return <section className="analysis-card" aria-label="Document understanding" aria-busy={busy}>
    <h2>{analysis?.confirmedAt ? "Confirmed by you" : analysis ? "Suggested by Life Admin" : "Understand this document"}</h2>
    {!analysis && <><p>Analyze readable PDF text for an explanation and suggested next steps. You can also enter details manually.</p>
      {!attempt && <button className="primary" disabled={!ready || busy} onClick={() => void run(false)}>{busy ? "Analyzing document…" : "Analyze document"}</button>}
      {attempt?.status === "PROCESSING" && <><p role="status">An analysis attempt is in progress. Refreshing does not start another request.</p><button className="secondary" disabled={busy} onClick={async () => { try { onChange(await getDocumentAnalysis(documentId)); } catch { try { setAttempt(await getAnalysisAttempt(documentId) ?? null); } catch { setError("Could not check analysis status. Please refresh."); } } }}>Check analysis status</button></>}
      {attempt?.status === "FAILED" && <><p>{attempt.message}</p><button className="secondary" disabled={busy || cooldown > 0} onClick={() => setRetry(true)}>Try analysis again{cooldown > 0 ? ` (${cooldown}s)` : ""}</button></>}
      {retry && <div className="task-delete-confirm"><p>This sends the document text for another analysis attempt and may incur another charge. Continue?</p><button className="primary" disabled={busy || cooldown > 0} onClick={() => void run(true)}>Confirm another analysis attempt</button><button className="secondary" disabled={busy} onClick={() => setRetry(false)}>Cancel retry</button></div>}
      {busy && <p role="status">Reading and explaining your document…</p>}
      {!editing && <button className="secondary" disabled={busy} onClick={() => setEditing(true)}>Enter details manually</button>}
    </>}
    {analysis?.confirmedAt && <><p>Your reviewed details are saved. Select confirmed actions below to create tasks; reminders can be added to each task.</p><button className="secondary" disabled={busy} onClick={() => setEditing(true)}>Edit confirmed details</button></>}
    {(editing || (analysis && !analysis.confirmedAt)) && <ReviewEditor key={analysis?.updatedAt ?? "manual"} analysis={analysis} onSave={content => void confirm(content)} busy={busy} />}
    {error && <p role="alert" className="message error">{error}</p>}
    <p className="analysis-notice">Life Admin provides explanations and suggestions, not legal, tax, immigration, medical or financial advice. Verify important information and dates against the original and, when needed, with the issuing organization or an appropriate professional.</p>
  </section>;
}
