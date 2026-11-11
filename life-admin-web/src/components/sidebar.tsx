import Link from "next/link";
import { FolderOpen, LayoutDashboard, Leaf, ReceiptText, Inbox } from "lucide-react";
import { AccountArea } from "./auth-gate";
export default function Sidebar({ active }: { active: "dashboard" | "documents" | "settings" | "receipts" | "inbox" }) {
  return <aside className="sidebar">
    <Link className="brand" href="/" aria-label="Life Admin home"><span className="brand-icon"><Leaf size={22} /></span>life admin<span className="brand-dot">.</span></Link>
    <nav aria-label="Main navigation">
      <Link href="/" className={`nav-item ${active === "dashboard" ? "active" : ""}`} aria-current={active === "dashboard" ? "page" : undefined}><LayoutDashboard size={19} />Dashboard</Link>
      <Link href="/inbox" className={`nav-item ${active === "inbox" ? "active" : ""}`} aria-current={active === "inbox" ? "page" : undefined}><Inbox size={19} />Inbox</Link>
      <Link href="/documents" className={`nav-item ${active === "documents" ? "active" : ""}`} aria-current={active === "documents" ? "page" : undefined}><FolderOpen size={19} />Documents</Link>
      <Link href="/receipts" className={`nav-item ${active === "receipts" ? "active" : ""}`} aria-current={active === "receipts" ? "page" : undefined}><ReceiptText size={19} />Receipts</Link>
    </nav>
    <AccountArea />
  </aside>;
}
