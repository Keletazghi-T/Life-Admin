"use client";
import Link from "next/link";
import { useState } from "react";
import { Leaf } from "lucide-react";
import { authenticate } from "@/lib/auth";
export default function AuthForm({ mode, notice }: { mode: "login" | "register"; notice?: string }) {
  const register = mode === "register";
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function submit(event: React.FormEvent) {
    event.preventDefault(); if (busy) return; setError("");
    if (register && password !== confirm) { setError("Passwords do not match."); return; }
    if (new TextEncoder().encode(password).length > 72) { setError("Use a password of at most 72 UTF-8 bytes."); return; }
    setBusy(true);
    try { await authenticate(mode, email.trim(), password); setPassword(""); setConfirm(""); window.location.replace("/"); }
    catch (e) { setError(e instanceof Error ? e.message : "Could not sign in. Please try again."); setBusy(false); }
  }
  return <main className="auth-page">
    <Link className="brand" href="/" aria-label="Life Admin home"><span className="brand-icon"><Leaf size={22} /></span>life admin<span className="brand-dot">.</span></Link>
    <section className="auth-card"><h1>{register ? "Create your Life Admin account" : "Welcome back"}</h1>
      <p>{register ? "Keep your important documents and next steps together." : "Log in to your documents, tasks, and reminders."}</p>
      {notice && <p role="status" className="message success">{notice}</p>}
      <form onSubmit={submit} className="auth-form" aria-busy={busy}>
        <label htmlFor="email">Email</label><input id="email" type="email" autoComplete="username" required maxLength={254} value={email} disabled={busy} onChange={e => setEmail(e.target.value)} />
        <label htmlFor="password">Password</label><input id="password" type="password" autoComplete={register ? "new-password" : "current-password"} required minLength={register ? 8 : undefined} maxLength={72} value={password} disabled={busy} onChange={e => setPassword(e.target.value)} aria-describedby={register ? "password-help" : undefined} />
        {register && <><small id="password-help">At least 8 characters; up to 72 UTF-8 bytes.</small><label htmlFor="confirm-password">Confirm password</label><input id="confirm-password" type="password" autoComplete="new-password" required minLength={8} maxLength={72} value={confirm} disabled={busy} onChange={e => setConfirm(e.target.value)} /></>}
        {error && <p role="alert" className="message error">{error}</p>}
        <button className="primary" disabled={busy}>{busy ? "Please wait…" : register ? "Create account" : "Log in"}</button>
      </form>
      <p className="auth-alternate">{register ? "Already have an account? " : "Don't have an account? "}<Link href={register ? "/login" : "/register"}>{register ? "Log in" : "Create account"}</Link></p>
    </section>
  </main>;
}
