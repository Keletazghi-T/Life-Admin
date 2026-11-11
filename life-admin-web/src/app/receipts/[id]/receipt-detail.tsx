"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { ChevronRight } from "lucide-react";
import AuthGate from "@/components/auth-gate";
import Sidebar from "@/components/sidebar";
import { deleteReceipt, getReceipt, Receipt, reviewStatusLabel } from "@/lib/receipts";
import { formatDate } from "@/lib/reminders";
import ReceiptForm from "../receipt-form";
export default function ReceiptDetail({ id }: { id: string }) { return <AuthGate><Content key={id} id={id} /></AuthGate>; }
function Content({ id }: { id: string }) {
  const router = useRouter();
  const [receipt, setReceipt] = useState<Receipt | null>(null);
  const [error, setError] = useState("");
  const [deleteError, setDeleteError] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    let active=true;let timer:ReturnType<typeof setTimeout>|undefined;let polls=0;
    async function load(){try{
      const data=await getReceipt(id);
      if(active){setReceipt(data);
        // A duplicate upload response may arrive while the original request is still reading.
        // Poll metadata only; never start or retry extraction from a render or refresh.
        if(data.reviewStatus==="UPLOADED" && data.extractionStatus==="NOT_ATTEMPTED" && polls++<45)timer=setTimeout(load,2000);
      }
    }catch(e){if(active)setError(e instanceof Error?e.message:"We couldn't load this receipt.");}}
    void load();
    return()=>{active=false;if(timer)clearTimeout(timer);};
  },[id]);
  async function remove() {
    if (busy) return; setBusy(true); setDeleteError("");
    try { await deleteReceipt(id); router.push("/receipts"); }
    catch (e) { setDeleteError(e instanceof Error ? e.message : "Unable to delete receipt. Please retry."); setBusy(false); try { setReceipt(await getReceipt(id)); } catch { /* Keep retry controls available. */ } }
  }
  return <div className="app-shell"><Sidebar active="receipts" /><div className="main-shell"><header className="topbar"><span>Workspace</span><ChevronRight size={14} /><span>Receipts</span></header><main className="receipt-detail">
    <Link className="back-link" href="/receipts">← All receipts</Link>
    {receipt?.reviewStatus === "UPLOADED" && receipt.extractionStatus === "NOT_ATTEMPTED" && <p role="status" className="receipt-reading">Your receipt is saved. Reading may still be finishing; you can enter the details manually.</p>}
    {error && <p role="alert" className="message error">{error}</p>}
    {!receipt && !error && <p role="status">Loading receipt…</p>}
    {receipt && <><div className="page-heading"><h1>{receipt.merchant || receipt.originalFileName}</h1><p>Purchase record · {receipt.status === "SAVED" ? reviewStatusLabel(receipt.reviewStatus) : "Deletion pending"}</p></div>
      {message && <p role="status" className="message success">{message}</p>}
      {receipt.extractionSource === "DEVELOPMENT_SAMPLE" && receipt.reviewStatus === "REVIEWED" && <p className="sample-notice">DEVELOPMENT SAMPLE — Not extracted from the actual receipt.{receipt.reviewStatus === "REVIEWED" && " Details have been saved by the user; verify them against the original."}</p>}
      {receipt.status === "SAVED" && <div className="receipt-review-layout">
        <section className="privacy-card receipt-preview"><h2>Original receipt</h2><p>{receipt.originalFileName}</p><p>Uploaded {formatDate(receipt.createdAt.slice(0,10))}</p>
          {receipt.contentType === "application/pdf" ? <object data={`/api/receipts/${id}/content`} type="application/pdf" aria-label="Receipt PDF preview"><p>Your browser cannot preview this PDF here. Use View receipt below.</p></object> : <ReceiptImage id={id} />}
          <div className="document-action-buttons"><a className="secondary" href={`/api/receipts/${id}/content`} target="_blank" rel="noopener noreferrer">View receipt</a><a className="secondary" href={`/api/receipts/${id}/content?download=true`}>Download receipt</a></div>
        </section>
        <section className="privacy-card"><h2>Review receipt details</h2><p>Check the original receipt, correct the details, then save.</p>
          <ReceiptForm key={`${receipt.id}-${receipt.updatedAt}`} receipt={receipt} onUpdated={setReceipt} onCancel={() => router.push("/receipts")} onSaved={updated => { setReceipt(updated); setMessage("Receipt saved and reviewed."); }} />
        </section>
      </div>}
      <div className="document-action-buttons settings-section"><button className="secondary danger" onClick={() => { setDeleteError(""); dialog.current?.showModal(); }}>{receipt.status === "SAVED" ? "Delete receipt" : "Retry receipt deletion"}</button></div>
      <dialog ref={dialog} className="delete-dialog" aria-labelledby="receipt-delete-heading" onCancel={e => { if (busy) e.preventDefault(); }}><h2 id="receipt-delete-heading">Delete receipt?</h2><p>Permanently delete this receipt and its uploaded file. This action cannot be undone.</p><p className="delete-document-name">{receipt.merchant || receipt.originalFileName}</p>{deleteError && <p role="alert" className="message error">{deleteError}</p>}<div className="dialog-actions"><button className="secondary" autoFocus disabled={busy} onClick={() => dialog.current?.close()}>Cancel</button><button className="primary delete-permanently" disabled={busy} onClick={remove}>{busy ? "Deleting…" : "Delete permanently"}</button></div></dialog>
    </>}
  </main></div></div>;
}

function ReceiptImage({ id }: { id: string }) {
 const [failed,setFailed]=useState(false);
 return failed ? <p role="status">The receipt preview is unavailable. Try View receipt or Download receipt.</p> :
 // Authenticated same-origin content; avoid the public Next image optimizer/cache.
 // eslint-disable-next-line @next/next/no-img-element
 <img src={`/api/receipts/${id}/content`} alt="Uploaded receipt" onError={()=>setFailed(true)} />;
}
