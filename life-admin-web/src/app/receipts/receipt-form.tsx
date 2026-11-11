"use client";
import { useRef, useState } from "react";
import { categories, Category, Receipt, ReceiptInput, saveReceipt, taxNotice, extractReceipt, getReceipt } from "@/lib/receipts";
export default function ReceiptForm({ receipt, onUpdated, onSaved, onCancel }: { receipt: Receipt; onUpdated: (receipt: Receipt) => void; onSaved: (receipt: Receipt) => void; onCancel: () => void }) {
  const confirmed = receipt.reviewStatus === "REVIEWED";
  const [values, setValues] = useState<ReceiptInput>({ merchant: confirmed ? receipt.merchant ?? "" : receipt.suggestedMerchant ?? "", purchaseDate: confirmed ? receipt.purchaseDate ?? "" : receipt.suggestedPurchaseDate ?? "", amount: confirmed ? receipt.amount ?? "" : receipt.suggestedAmount ?? "", currency: confirmed ? receipt.currency : receipt.suggestedCurrency ?? "", category: confirmed ? receipt.category : receipt.suggestedCategory, description: confirmed ? receipt.description ?? "" : receipt.suggestedDescription ?? "", taxRelevant: receipt.taxRelevant, notes: receipt.notes ?? "" });
  const [edited, setEdited] = useState<Set<keyof ReceiptInput>>(new Set());
  function hint(key: keyof ReceiptInput, suggestion: string | null) {
    return <small className="extraction-field-note">{edited.has(key) ? "Your entry — save to confirm." : confirmed ? "Confirmed by you" : suggestion ? `Suggested by Life Admin: ${suggestion}` : "Could not determine automatically."}</small>;
  }
  const reading=useRef(false);
  const [retryConfirmation,setRetryConfirmation]=useState(false);
  const [retryAt,setRetryAt]=useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  function field<K extends keyof ReceiptInput>(key: K, value: ReceiptInput[K]) { setValues(old => ({ ...old, [key]: value })); setEdited(old => new Set(old).add(key)); }
  async function submit(event: React.FormEvent) {
    event.preventDefault(); if (busy) return; setError("");
    if (values.currency !== "USD") { setError("Only USD receipts can currently be saved. Changing the currency does not convert the amount."); return; }
    setBusy(true);
    try { onSaved(await saveReceipt(values, receipt.id)); setBusy(false); }
    catch (e) { setError(e instanceof Error ? e.message : "Could not save receipt. Please try again."); setBusy(false); }
  }
  async function readReceipt() {
    if(reading.current || busy)return;
    if(Date.now()<retryAt){setError("Please wait a moment before trying again.");return;}
    reading.current=true;setBusy(true);setError("");setRetryConfirmation(false);
    try{await extractReceipt(receipt.id,receipt.updatedAt);onUpdated(await getReceipt(receipt.id));}
    catch(e){setError(e instanceof Error?e.message:"Could not read receipt details.");}finally{reading.current=false;setBusy(false);setRetryAt(Date.now()+30_000);}
  }
  return <form className="auth-form receipt-form" onSubmit={submit} aria-busy={busy}>
    <h3>{confirmed ? "Confirmed by you" : "Suggested by Life Admin"}</h3>
    {!confirmed && <><p className="receipt-note">Automatically suggested details may be incomplete or incorrect. Check every value against the original before saving.</p>{receipt.extractionError && <p className="message" role="note">{receipt.extractionError}</p>}{receipt.extractionStatus === "NOT_ATTEMPTED" && <p className="receipt-note">This receipt has not been read automatically. Earlier development samples are not used as suggestions.</p>}{(["NOT_ATTEMPTED", "EXTRACTION_UNAVAILABLE"].includes(receipt.extractionStatus) || (receipt.extractionStatus === "OCR_REQUIRED" && receipt.contentType.startsWith("image/"))) && <div className="receipt-retry">{retryConfirmation ? <><p>Try reading again? This makes another reading attempt and replaces the suggestions in this form. Save any corrections you want to keep first.</p><button type="button" className="secondary" disabled={busy} onClick={()=>setRetryConfirmation(false)}>Keep entering manually</button><button type="button" className="secondary" disabled={busy} onClick={readReceipt}>Confirm another attempt</button></> : <button type="button" className="secondary" disabled={busy} onClick={()=>setRetryConfirmation(true)}>{busy ? "Reading your receipt…" : "Try reading again"}</button>}</div>}</>}
    <label htmlFor="merchant">Merchant</label><input id="merchant" required maxLength={200} value={values.merchant} disabled={busy} onChange={e => field("merchant", e.target.value)} />
    {hint("merchant", receipt.suggestedMerchant)}
    <label htmlFor="purchase-date">Purchase date</label><input id="purchase-date" type="date" min="1900-01-01" max={new Date().toISOString().slice(0, 10)} required value={values.purchaseDate} disabled={busy} onChange={e => field("purchaseDate", e.target.value)} />
    {hint("purchaseDate", receipt.suggestedPurchaseDate)}
    <label htmlFor="amount">Amount</label><input id="amount" type="number" inputMode="decimal" step="0.01" min="0.01" max="999999999999.99" required value={values.amount} disabled={busy} onChange={e => field("amount", e.target.value)} />
    {hint("amount", receipt.suggestedAmount)}
    <label htmlFor="receipt-currency">Currency</label><select id="receipt-currency" required value={values.currency} disabled={busy} onChange={e => field("currency", e.target.value)}><option value="">Choose currency</option>{receipt.suggestedCurrency && receipt.suggestedCurrency !== "USD" && <option value={receipt.suggestedCurrency}>{receipt.suggestedCurrency} — not currently supported</option>}<option value="USD">USD — US Dollar</option></select><small>USD is currently the supported currency.</small>
    {hint("currency", receipt.suggestedCurrency)}
    {values.currency && values.currency !== "USD" && <p className="message error">This receipt uses {values.currency}. Only USD receipts can currently be saved. Changing the currency does not convert the amount.</p>}
    <label htmlFor="receipt-category">Category</label><select id="receipt-category" value={values.category ?? ""} disabled={busy} onChange={e => field("category", (e.target.value || null) as Category | null)}><option value="">Uncategorized</option>{Object.entries(categories).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>
    {hint("category", receipt.suggestedCategory ? categories[receipt.suggestedCategory] : null)}
    <label htmlFor="description">Description (optional)</label><textarea id="description" maxLength={2000} value={values.description ?? ""} disabled={busy} onChange={e => field("description", e.target.value)} />
    {hint("description", receipt.suggestedDescription)}
    <label htmlFor="tax-relevant">Potentially tax relevant</label><select id="tax-relevant" value={values.taxRelevant ? "yes" : "no"} disabled={busy} onChange={e => field("taxRelevant", e.target.value === "yes")}><option value="no">No</option><option value="yes">Yes</option></select><p className="receipt-note">{taxNotice}</p>
    <label htmlFor="receipt-notes">Notes (optional)</label><textarea id="receipt-notes" maxLength={4000} value={values.notes ?? ""} disabled={busy} onChange={e => field("notes", e.target.value)} />
    {error && <p role="alert" className="message error">{error}</p>}
    <div className="dialog-actions"><button type="button" className="secondary" disabled={busy} onClick={onCancel}>Cancel</button><button className="primary" disabled={busy}>{busy ? "Saving…" : "Save receipt"}</button></div>
  </form>;
}
