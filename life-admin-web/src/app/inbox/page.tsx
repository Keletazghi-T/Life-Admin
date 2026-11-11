"use client";
import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { ChevronRight } from "lucide-react";
import AuthGate from "@/components/auth-gate";
import Sidebar from "@/components/sidebar";
import AttentionList from "@/components/attention-list";
import QuickActions from "@/components/quick-actions";
import { inbox, Inbox } from "@/lib/inbox";
export default function InboxPage(){return <Suspense fallback={<p>Loading Inbox…</p>}><AuthGate><Content/></AuthGate></Suspense>;}
function Content(){
 const params=useSearchParams();const initial=params.get("filter")??"all";
 return <div className="app-shell"><Sidebar active="inbox"/><div className="main-shell"><header className="topbar"><span>Workspace</span><ChevronRight size={14}/><span>Inbox</span></header><main>
  <div className="page-heading"><h1>Life Admin Inbox</h1><p>Your paperwork, receipts and actions that need attention, in one place.</p></div>
  <Filtered key={initial} initial={initial}/>
 </main></div></div>;
}
function Filtered({initial}:{initial:string}){
 const [filter,setFilter]=useState(["all","documents","receipts","tasks","deadlines"].includes(initial)?initial:"all");const [sort,setSort]=useState("urgent");const [page,setPage]=useState(0);
 return <><div className="inbox-filters"><div><label htmlFor="inbox-filter">Show</label><select id="inbox-filter" value={filter} onChange={e=>{setFilter(e.target.value);setPage(0);}}><option value="all">All</option><option value="documents">Documents</option><option value="receipts">Receipts</option><option value="tasks">Tasks</option><option value="deadlines">Deadlines</option></select></div><div><label htmlFor="inbox-sort">Sort</label><select id="inbox-sort" value={sort} onChange={e=>{setSort(e.target.value);setPage(0);}}><option value="urgent">Most urgent first</option><option value="newest">Newest first</option></select></div></div>
 <Results key={`${filter}-${sort}-${page}`} filter={filter} sort={sort} page={page} onPage={setPage}/></>;
}
function Results({filter,sort,page,onPage}:{filter:string;sort:string;page:number;onPage:(n:number)=>void}){
 const [data,setData]=useState<Inbox|null>(null);const [error,setError]=useState(false);const [retry,setRetry]=useState(0);
 useEffect(()=>{let active=true;inbox(filter,sort,page).then(result=>{if(active){setData(result);setError(false);}}).catch(()=>{if(active)setError(true);});return()=>{active=false;};},[filter,sort,page,retry]);
 if(error)return <p role="alert" className="message error">We couldn&apos;t load your Inbox. <button className="secondary" onClick={()=>setRetry(v=>v+1)}>Try again</button></p>;
 if(!data)return <p role="status">Loading Inbox…</p>;
 return <><p className="inbox-count">{data.totalCount} {data.totalCount===1?"item":"items"} needing attention</p>{data.items.length ? <AttentionList items={data.items}/> : <p className="caught-up">{data.attentionCount===0?"You're all caught up.":"No items match this filter or page."}</p>}
 <div className="dialog-actions">{page>0 && <button className="secondary" onClick={()=>onPage(page-1)}>Previous</button>}{data.hasMore && <button className="secondary" onClick={()=>onPage(page+1)}>Next items</button>}</div>
 {data.attentionCount===0 && <QuickActions tax={false} onUploaded={()=>setRetry(v=>v+1)}/>}
 <p className="receipt-note">Tasks with dates also appear in the Deadlines filter. Each action is listed once. Dates use the UTC calendar day.</p></>;
}
