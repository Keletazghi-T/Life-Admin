"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { ChevronRight } from "lucide-react";
import AuthGate from "@/components/auth-gate";
import Sidebar from "@/components/sidebar";
import AttentionList from "@/components/attention-list";
import QuickActions from "@/components/quick-actions";
import { dashboard, UnifiedDashboard } from "@/lib/inbox";
export default function Dashboard(){return <AuthGate><Content/></AuthGate>;}
function Content(){
 const [data,setData]=useState<UnifiedDashboard|null>(null);const [error,setError]=useState("");const [refresh,setRefresh]=useState(0);
 useEffect(()=>{let active=true;dashboard().then(result=>{if(active){setData(result);setError("");}}).catch(()=>{if(active)setError("We couldn't load your dashboard. Please try again.");});return()=>{active=false;};},[refresh]);
 const reload=()=>setRefresh(v=>v+1);
 return <div className="app-shell"><Sidebar active="dashboard"/><div className="main-shell"><header className="topbar"><span>Workspace</span><ChevronRight size={14}/><span>Dashboard</span></header><main id="dashboard">
  <div className="page-heading"><h1>Welcome back</h1><p>Here&apos;s what needs your attention.</p></div>
  {error && <p role="alert" className="message error">{error} <button className="secondary" onClick={reload}>Try again</button></p>}
  {!data && !error && <p role="status">Loading your dashboard…</p>}
  {data && <><section className="unified-summary" aria-label="Account summary">
    <Link href="/documents"><span>Documents</span><strong>{data.documentCount}</strong></Link>
    <Link href="/receipts"><span>Receipts</span><strong>{data.receiptCount}</strong></Link>
    <Link href="/inbox?filter=tasks"><span>Open tasks</span><strong>{data.openTaskCount}</strong></Link>
    <Link href="/inbox?filter=deadlines"><span>Upcoming deadlines</span><strong>{data.upcomingDeadlineCount}</strong><small>Next 30 days, after today</small></Link>
    <Link href="/inbox?filter=receipts"><span>Receipts needing review</span><strong>{data.receiptsNeedingReviewCount}</strong></Link>
   </section>
   <section className="unified-attention" aria-labelledby="attention-heading"><div className="documents-heading"><h2 id="attention-heading">Needs attention</h2>{data.attentionCount>8 && <Link href="/inbox">View all ({data.attentionCount}) →</Link>}</div>
    {data.attention.length ? <AttentionList items={data.attention}/> : <p className="caught-up">You&apos;re all caught up.</p>}
   </section>
  </>}
  <QuickActions onUploaded={reload}/>
  {data && <section className="recent-activity" aria-labelledby="activity-heading"><h2 id="activity-heading">Recent activity</h2>{data.recentActivity.length ? <ul>{data.recentActivity.map(item=><li key={item.id}><Link href={item.href}><div><strong>{item.title}</strong><p>{item.name}</p>{item.developmentSample && <small className="sample-task-label">DEVELOPMENT SAMPLE</small>}</div><time dateTime={item.occurredAt}>{new Date(item.occurredAt).toLocaleString(undefined,{month:"short",day:"numeric",year:"numeric",hour:"numeric",minute:"2-digit"})}</time><ChevronRight size={18} aria-hidden="true"/></Link></li>)}</ul> : <p>No recent activity yet. Your uploads and saved progress will appear here.</p>}<small>Based on current records and their latest saved timestamps.</small></section>}
  <p className="session-note">Your records are private to your account. Deadline labels use the UTC calendar day.</p>
 </main></div></div>;
}
