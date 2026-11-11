"use client";
import { useRef, useState } from "react";
import { Receipt, uploadReceipt } from "@/lib/receipts";
import { useAccount } from "@/components/auth-gate";
export default function ReceiptUpload({ onSaved, onCancel }: { onSaved: (receipt: Receipt) => void; onCancel: () => void }) {
 const account=useAccount();
 const [file,setFile]=useState<File>();const [busy,setBusy]=useState(false);const [error,setError]=useState("");
 const submitting=useRef(false);const camera=useRef<HTMLInputElement>(null);
 const pending=useRef<{signature:string;id:string}|null>(null);
 async function submit(event:React.FormEvent){event.preventDefault();if(submitting.current)return;
  if(!file || file.size===0 || file.size>10*1024*1024 || !["application/pdf","image/jpeg","image/png"].includes(file.type)){setError("Choose a PDF, JPEG or PNG between 1 byte and 10 MB.");return;}
  submitting.current=true;setBusy(true);setError("");
  const storageKey=`life-admin-receipt-upload:${account?.id}`;
  const signature=JSON.stringify([file.name,file.size,file.type,file.lastModified]);
  try {
   if(!pending.current){try{pending.current=JSON.parse(sessionStorage.getItem(storageKey)||"null");}catch{/* Storage is optional; the in-memory guard still applies. */}}
   if(pending.current?.signature!==signature)pending.current={signature,id:crypto.randomUUID()};
   const requestId=pending.current.id;
   try{sessionStorage.setItem(storageKey,JSON.stringify(pending.current));}catch{/* Private browsers may deny session storage. */}
   const receipt=await uploadReceipt(file,requestId);
   try{sessionStorage.removeItem(storageKey);}catch{/* No receipt contents are stored here. */}
   onSaved(receipt);
  }catch(e){setError(e instanceof Error?e.message:"Could not finish uploading. Check your receipts before submitting again.");submitting.current=false;setBusy(false);}
 }
 function choose(next?:File){if(submitting.current)return;setFile(next);setError("");}
 return <form className="auth-form receipt-form" onSubmit={submit} aria-busy={busy}>
  <button type="button" className="secondary" disabled={busy} onClick={()=>camera.current?.click()}>Take a photo</button>
  <input ref={camera} aria-label="Take receipt photo" type="file" accept="image/*" capture="environment" hidden disabled={busy} onChange={e=>choose(e.target.files?.[0])}/>
  <label htmlFor="receipt-file">Receipt file</label><input id="receipt-file" type="file" accept="application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png" disabled={busy} onChange={e=>choose(e.target.files?.[0])}/>
  <small>Choose an existing photo or PDF, or use your camera. PDF, JPG/JPEG or PNG • Maximum 10 MB. Images up to 16 megapixels.</small>
  {file && <p className="receipt-note">Selected: {file.name}</p>}
  {busy && <div className="receipt-reading" role="status" aria-live="polite"><span className="receipt-reading-indicator" aria-hidden="true"/><div><strong>Reading your receipt…</strong><p>We’re uploading and reading the file. Your review page will open when it’s ready.</p></div></div>}
  {error && <p className="message error" role="alert">{error}</p>}
  <div className="dialog-actions"><button type="button" className="secondary" disabled={busy} onClick={onCancel}>Cancel</button><button className="primary" disabled={busy}>{busy?"Reading your receipt…":"Upload receipt"}</button></div>
 </form>;
}
