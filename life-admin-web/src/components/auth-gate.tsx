"use client";
import Link from "next/link";
import { createContext, useContext, useEffect, useState } from "react";
import { Account, currentUser, logout } from "@/lib/auth";
import { ApiError } from "@/lib/documents";
const AccountContext = createContext<Account | null>(null);
export default function AuthGate({ children }: { children: React.ReactNode }) {
  const [account, setAccount] = useState<Account | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    function signedOut() { if (!active) return; setAccount(null); window.location.replace("/login"); }
    function leaving() { active = false; }
    async function check() {
      try {
        const user = await currentUser();
        if (user.deletionPending && window.location.pathname !== "/settings") { window.location.replace("/settings"); return; }
        if (active) setAccount(previous => {
          if (previous && previous.id !== user.id) { window.location.reload(); return null; }
          return user;
        });
      } catch (e) {
        if (!active) return;
        setAccount(null);
        if (e instanceof ApiError && e.status === 401) signedOut();
        else setError("Could not check your login. Please try again.");
      }
    }
    void check();
    const channel = new BroadcastChannel("life-admin-account");
    channel.onmessage = () => signedOut();
    const restored = (event: PageTransitionEvent) => { if (event.persisted) { setAccount(null); void check(); } };
    window.addEventListener("life-admin-unauthorized", signedOut);
    window.addEventListener("life-admin-signing-out", leaving);
    window.addEventListener("pageshow", restored);
    window.addEventListener("focus", check);
    return () => { active = false; channel.close(); window.removeEventListener("life-admin-signing-out", leaving); window.removeEventListener("life-admin-unauthorized", signedOut); window.removeEventListener("pageshow", restored); window.removeEventListener("focus", check); };
  }, []);
  if (!account) return <main className="auth-loading">{error ? <><p role="alert">{error}</p><button className="secondary" onClick={() => window.location.reload()}>Try again</button></> : <p role="status">Checking your login…</p>}</main>;
  return <AccountContext.Provider value={account}>{children}</AccountContext.Provider>;
}
export function useAccount() { return useContext(AccountContext); }
export function AccountArea() {
  const account = useContext(AccountContext);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function signOut() {
    setBusy(true); setError("");
    try {
      await logout();
      const channel = new BroadcastChannel("life-admin-account"); channel.postMessage("logout"); channel.close();
      // A full navigation clears React state and its router cache, including previous private pages.
      window.location.replace("/login");
    } catch { setError("Could not log out. Please try again."); setBusy(false); }
  }
  return <div className="account-area"><span>{account?.email}</span><Link href="/settings">Account & privacy</Link><button onClick={signOut} disabled={busy}>{busy ? "Logging out…" : "Log out"}</button>{error && <p role="alert">{error}</p>}</div>;
}
