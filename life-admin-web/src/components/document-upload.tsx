"use client";
import { useRef, useState } from "react";
import { CheckCircle2, FileText, LoaderCircle, Upload, X } from "lucide-react";
import { uploadDocument } from "@/lib/documents";
const maxSize = 10 * 1024 * 1024;
function size(bytes: number) { return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.ceil(bytes / 1024)} KB`; }
export default function DocumentUpload({ onUploaded, onBusy }: { onUploaded: () => void; onBusy: (busy: boolean) => void }) {
  const loading = false;
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  function choose(selected: File | undefined) {
    if (uploading) return;
    setError(""); setSuccess(""); setFile(null);
    if (!selected) return;
    if (!selected.name.toLowerCase().endsWith(".pdf") || selected.type !== "application/pdf") { setError("Please choose a PDF document."); return; }
    if (selected.size === 0 || selected.size > maxSize) { setError("Choose a PDF between 1 byte and 10 MB."); return; }
    setFile(selected);
  }
  async function upload() {
    if (!file || uploading) return;
    setUploading(true); onBusy(true); setError(""); setSuccess("");
    try {
      await uploadDocument(file);
      onUploaded();
      setSuccess("Document uploaded successfully"); setFile(null);
      if (input.current) input.current.value = "";
    } catch (e) { setError(e instanceof Error ? e.message : "Upload failed. Please try again."); }
    finally { setUploading(false); onBusy(false); }
  }
  return (          <section className="upload-card" aria-labelledby="upload-heading" aria-busy={uploading}>
            <h2 id="upload-heading">Upload a document</h2><p>Upload a PDF and Life Admin will help you understand what it means and what you need to do.</p>
            <input
              ref={input} type="file" id="pdf-file" aria-label="Choose a PDF document"
              accept="application/pdf,.pdf" hidden disabled={uploading || loading}
              onChange={e => { choose(e.target.files?.[0]); e.target.value = ""; }}
            />
            <button
              type="button" className={`dropzone ${dragging ? "dragging" : ""}`}
              disabled={uploading || loading} onClick={() => input.current?.click()}
              onDragOver={e => { e.preventDefault(); if (!uploading && !loading) setDragging(true); }}
              onDragLeave={() => setDragging(false)}
              onDrop={e => { e.preventDefault(); setDragging(false); if (!loading) choose(e.dataTransfer.files[0]); }}
            >
              <span className="upload-icon"><Upload size={28} /></span>
              <strong>Drag and drop your PDF here</strong>
              <span>or <em>Browse files</em></span>
              <small>PDF only • Maximum 10 MB</small>
            </button>
            {file && (
              <div className="upload-selection">
                <div className="selected-file">
                  <FileText size={20} />
                  <div><strong>{file.name}</strong><small>{size(file.size)}</small></div>
                  <button type="button" aria-label="Remove selected file" disabled={uploading} onClick={() => setFile(null)}><X size={18} /></button>
                </div>
                <button type="button" className="primary upload-button" disabled={uploading || loading} onClick={upload}>
                  {uploading ? <LoaderCircle className="spin" size={18} /> : <Upload size={18} />}
                  {uploading ? "Uploading document…" : "Upload document"}
                </button>
              </div>
            )}
            <div className="messages" aria-live="polite">
              {error && <div role="alert" className="message error">{error}</div>}
              {success && <div role="status" className="message success"><CheckCircle2 size={18} />{success}</div>}
            </div>
          </section>);
}
