"use client";
import Link from "next/link";
import { useRef, useState } from "react";
import DocumentUpload from "./document-upload";
export default function QuickActions({ onUploaded, tax = true }: { onUploaded: () => void; tax?: boolean }) {
 const dialog=useRef<HTMLDialogElement>(null);const [busy,setBusy]=useState(false);const [version,setVersion]=useState(0);
 return <section className="quick-actions" aria-label="Quick actions"><h2>Quick actions</h2><div className="document-action-buttons"><button className="primary" onClick={()=>{setVersion(v=>v+1);dialog.current?.showModal();}}>Upload document</button><Link className="secondary" href="/receipts?add=1">Add receipt</Link>{tax && <Link className="secondary" href="/receipts/tax">View tax organization</Link>}</div>
  <dialog ref={dialog} className="upload-dialog" aria-labelledby="upload-heading" onCancel={e=>{if(busy)e.preventDefault();}}><DocumentUpload key={version} onUploaded={onUploaded} onBusy={setBusy}/><button className="secondary" disabled={busy} onClick={()=>dialog.current?.close()}>Close</button></dialog>
 </section>;
}
