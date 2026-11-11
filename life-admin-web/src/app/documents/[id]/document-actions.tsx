"use client";
import { useRef, useState } from "react";
import { Document, deleteDocument, getDocument, renameDocument } from "@/lib/documents";
export default function DocumentActions({ document, onChange }: { document: Document; onChange: (document: Document) => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [renaming, setRenaming] = useState(false);
  const [name, setName] = useState(document.displayName);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [deleteError, setDeleteError] = useState("");
  const [message, setMessage] = useState("");
  const pending = document.status === "DELETION_PENDING";
  async function save(event: React.FormEvent) {
    event.preventDefault(); if (busy) return; setBusy(true); setError(""); setMessage("");
    try { onChange(await renameDocument(document.id, name.trim())); setRenaming(false); setMessage("Document renamed."); }
    catch (e) { setError(e instanceof Error ? e.message : "Could not rename this document."); }
    finally { setBusy(false); }
  }
  async function remove() {
    if (busy) return; setBusy(true); setDeleteError("");
    try { await deleteDocument(document.id); window.location.replace("/documents?deleted=1"); }
    catch (e) {
      setDeleteError(e instanceof Error ? e.message : "Could not delete this document. Please retry.");
      try { onChange(await getDocument(document.id)); } catch { /* Keep the deletion error visible. */ }
      setBusy(false);
    }
  }
  return <div className="document-actions">
    {pending && <p role="status" className="message error">Deletion is pending. The document is unavailable until cleanup finishes. Retry deleting it to finish.</p>}
    <div className="document-action-buttons">
      {!pending && <><a className="secondary" href={`/api/documents/${document.id}/file`} target="_blank" rel="noopener noreferrer">View original PDF</a><a className="secondary" href={`/api/documents/${document.id}/file?download=true`}>Download PDF</a><button className="secondary" disabled={busy} onClick={() => { setName(document.displayName); setRenaming(true); setError(""); setMessage(""); }}>Rename document</button></>}
      <button className="secondary danger" disabled={busy} onClick={() => { setDeleteError(""); dialog.current?.showModal(); }}>{pending ? "Retry deletion" : "Delete document"}</button>
    </div>
    {renaming && !pending && <form className="rename-form" onSubmit={save}>
      <label htmlFor="display-name">Document display name</label>
      <input id="display-name" value={name} maxLength={255} required autoFocus disabled={busy} onChange={e => setName(e.target.value)} />
      <div><button className="primary" disabled={busy || !name.trim()}>{busy ? "Saving…" : "Save name"}</button><button className="secondary" type="button" disabled={busy} onClick={() => setRenaming(false)}>Cancel</button></div>
    </form>}
    {error && <p role="alert" className="message error">{error}</p>}
    {message && <p role="status" className="task-feedback">{message}</p>}
    <dialog ref={dialog} className="delete-dialog" aria-labelledby="delete-document-title" aria-describedby="delete-document-description" onCancel={e => { if (busy) e.preventDefault(); }}>
      <h2 id="delete-document-title">Delete document?</h2><p className="delete-document-name">{document.displayName}</p>
      <div id="delete-document-description"><p>This will permanently delete:</p><ul><li>The uploaded PDF</li><li>Its analysis</li><li>Tasks</li><li>Reminders</li></ul><p>This cannot be undone.</p></div>
      {deleteError && <p role="alert" className="message error">{deleteError}</p>}
      <div className="dialog-actions"><button className="secondary" autoFocus disabled={busy} onClick={() => dialog.current?.close()}>Cancel</button><button className="primary delete-permanently" disabled={busy} onClick={remove}>{busy ? "Deleting…" : "Delete permanently"}</button></div>
    </dialog>
  </div>;
}
