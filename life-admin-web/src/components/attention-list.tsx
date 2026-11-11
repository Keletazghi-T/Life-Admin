import Link from "next/link";
import { ChevronRight } from "lucide-react";
import { AttentionItem } from "@/lib/inbox";
import { formatDate } from "@/lib/reminders";
const statuses: Record<string, string> = { OVERDUE: "Overdue", DUE_TODAY: "Due today", UPCOMING: "Upcoming", NEEDS_REVIEW: "Needs review", MISSING_INFORMATION: "Missing information", OPEN: "Open", INCOMPLETE: "Incomplete" };
const types = { DOCUMENT: "Document", RECEIPT: "Receipt", TASK: "Task", DEADLINE: "Deadline" };
export default function AttentionList({ items }: { items: AttentionItem[] }) {
 return <div className="attention-list">{items.map(item => <Link className="attention-item" href={item.href} key={item.id} data-attention-id={item.id}>
   <div className="attention-main"><div className="attention-labels"><span>{types[item.type]}</span><span className={`attention-status attention-${item.status.toLowerCase()}`}>{statuses[item.status] ?? item.status}</span></div><h3>{item.title}</h3>{item.type === "TASK" && <p>{item.context}</p>}<p>{item.reasons.join(" · ")}</p>{item.developmentSample && <small className="sample-task-label">DEVELOPMENT SAMPLE — review against the original.</small>}</div>
   <div className="attention-date">{item.date && <time dateTime={item.date}>{item.dateLabel} {formatDate(item.date)}</time>}<span>{item.action}</span></div><ChevronRight size={18} aria-hidden="true" />
 </Link>)}</div>;
}
