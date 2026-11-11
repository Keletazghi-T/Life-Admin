"use client";
import { useRef, useState } from "react";
import { ChevronRight } from "lucide-react";
import AuthGate, { useAccount } from "@/components/auth-gate";
import Sidebar from "@/components/sidebar";
import { accountSignedOut, changePassword, deleteAccount } from "@/lib/auth";
export default function SettingsPage() { return <AuthGate><SettingsContent /></AuthGate>; }
function SettingsContent() {
  const account = useAccount();
  const [current, setCurrent] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [deletePassword, setDeletePassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [deleteError, setDeleteError] = useState("");
  const [pending, setPending] = useState(account?.deletionPending ?? false);
  const dialog = useRef<HTMLDialogElement>(null);
  async function savePassword(event: React.FormEvent) {
    event.preventDefault(); if (busy) return; setError("");
    if (password !== confirm) { setError("New passwords do not match."); return; }
    if (password.length < 8 || new TextEncoder().encode(password).length > 72) { setError("Use at least 8 characters and at most 72 UTF-8 bytes."); return; }
    setBusy(true);
    try { await changePassword(current, password, confirm); setCurrent(""); setPassword(""); setConfirm(""); accountSignedOut("password-changed"); }
    catch (e) { setError(e instanceof Error ? e.message : "Unable to change password. Please try again."); setBusy(false); }
  }
  function closeDialog() { if (busy) return; dialog.current?.close(); setDeletePassword(""); setConfirmation(""); setDeleteError(""); }
  async function removeAccount(event: React.FormEvent) {
    event.preventDefault(); if (busy || confirmation !== "DELETE") return; setBusy(true); setDeleteError("");
    try { await deleteAccount(deletePassword, confirmation); setDeletePassword(""); accountSignedOut("account-deleted"); }
    catch (e) {
      setDeleteError(e instanceof Error ? e.message : "Unable to delete account. Please try again.");
      if (e instanceof Error && e.message.includes("retry Delete permanently")) setPending(true);
      setDeletePassword(""); setBusy(false);
    }
  }
  return <div className="app-shell"><Sidebar active="settings" /><div className="main-shell"><header className="topbar"><span>Workspace</span><ChevronRight size={14} /><span>Account & privacy</span></header><main>
    <div className="page-heading"><h1>Account & privacy</h1></div>
    {pending && <p role="alert" className="message error">Account deletion is incomplete. Your remaining data is tracked for cleanup. Retry Delete permanently below.</p>}
    <section className="privacy-card"><h2>Account</h2><p><strong>Email</strong><br />{account?.email}</p></section>
    <section className="privacy-card settings-section"><h2>Security</h2><h3>Change password</h3><p>Use at least 8 characters and at most 72 UTF-8 bytes. Changing your password signs you out on all devices.</p>
      <form className="auth-form settings-password" onSubmit={savePassword} aria-busy={busy}>
        <label htmlFor="current-password">Current password</label><input id="current-password" type="password" autoComplete="current-password" required maxLength={72} disabled={busy || pending} value={current} onChange={e => setCurrent(e.target.value)} />
        <label htmlFor="new-password">New password</label><input id="new-password" type="password" autoComplete="new-password" required minLength={8} maxLength={72} disabled={busy || pending} value={password} onChange={e => setPassword(e.target.value)} />
        <label htmlFor="confirm-new-password">Confirm new password</label><input id="confirm-new-password" type="password" autoComplete="new-password" required minLength={8} maxLength={72} disabled={busy || pending} value={confirm} onChange={e => setConfirm(e.target.value)} />
        {error && <p role="alert" className="message error">{error}</p>}<button className="primary" disabled={busy || pending}>{busy ? "Please wait…" : "Change password"}</button>
      </form>
    </section>
    <section className="privacy-card settings-section"><h2>Privacy & data</h2><p>Your documents and information are private to your account.</p><p>You can view, download, rename, or permanently delete a document from its details page.</p></section>
    <section className="privacy-card settings-section danger-zone"><h2>Danger zone</h2><p>Permanently delete your Life Admin account and all associated data, including uploaded documents, analyses, tasks, reminders, and receipts. This action cannot be undone.</p><button className="secondary danger" disabled={busy} onClick={() => dialog.current?.showModal()}>{pending ? "Retry account deletion" : "Delete account"}</button></section>
    <dialog ref={dialog} className="delete-dialog" aria-labelledby="delete-account-title" onCancel={e => { e.preventDefault(); closeDialog(); }}>
      <h2 id="delete-account-title">Delete account?</h2><p>All uploaded documents, receipts and their files, analyses, tasks, reminders, and your account will be permanently deleted. This action cannot be undone.</p>
      <form className="auth-form" onSubmit={removeAccount} aria-busy={busy}>
        <label htmlFor="delete-password">Password</label><input id="delete-password" type="password" autoComplete="current-password" required maxLength={72} value={deletePassword} disabled={busy} onChange={e => setDeletePassword(e.target.value)} />
        <label htmlFor="delete-confirmation">Type DELETE to confirm</label><input id="delete-confirmation" autoComplete="off" required pattern="DELETE" value={confirmation} disabled={busy} onChange={e => setConfirmation(e.target.value)} />
        {deleteError && <p role="alert" className="message error">{deleteError}</p>}
        <div className="dialog-actions"><button type="button" className="secondary" autoFocus disabled={busy} onClick={closeDialog}>Cancel</button><button className="primary delete-permanently" disabled={busy || confirmation !== "DELETE" || !deletePassword}>{busy ? "Deleting…" : "Delete permanently"}</button></div>
      </form>
    </dialog>
  </main></div></div>;
}
