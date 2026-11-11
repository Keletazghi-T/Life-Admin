import Link from "next/link";
import { Document } from "@/lib/documents";
import { daysBetween, formatDate } from "@/lib/reminders";
type Item = { taskId: string; taskTitle: string; dueDate: string | null; documentId: string; documentName: string; developmentMock: boolean };
export type DashboardData = { today: string; documentCount: number; openTaskCount: number; completedTaskCount: number; upcomingDeadlineCount: number; recentDocuments: Document[]; needsAttention: Item[]; upcomingDeadlines: Item[] };
function Rows({ items, today }: { items: Item[]; today: string }) {
  return <div className="upcoming-list">{items.map(item => {
    const days = item.dueDate ? daysBetween(today, item.dueDate) : null;
    return <Link className="upcoming-row" href={`/documents/${item.documentId}`} key={item.taskId}>
      <div><h3>{item.taskTitle}</h3><p>{item.documentName}</p>{item.developmentMock && <small className="sample-task-label">DEVELOPMENT sample task</small>}</div>
      <div className="upcoming-date">{item.dueDate && <time dateTime={item.dueDate}>Due {formatDate(item.dueDate)}</time>}<span>{days === null ? "Unfinished · No due date" : days < 0 ? `Overdue by ${-days} ${days === -1 ? "day" : "days"}` : days === 0 ? "Due today" : `Upcoming · ${days} ${days === 1 ? "day" : "days"} remaining`}</span></div><span aria-hidden="true">›</span>
    </Link>;
  })}</div>;
}
export default function DashboardOverview({ data }: { data: DashboardData }) {
  const total = data.openTaskCount + data.completedTaskCount;
  return <>
    <div className="dashboard-summary" aria-label="Account summary"><div><span>Documents</span><strong>{data.documentCount}</strong></div><div><span>Open tasks</span><strong>{data.openTaskCount}</strong></div><div><span>Upcoming deadlines</span><strong>{data.upcomingDeadlineCount}</strong><small>Next 30 days, after today</small></div></div>
    {total > 0 && <section className="dashboard-progress" aria-label="Task progress"><h2>Tasks</h2><p>{data.completedTaskCount} of {total} completed</p><progress aria-label="Completed tasks" value={data.completedTaskCount} max={total} /></section>}
    <section className="upcoming-section"><h2>Needs attention</h2>{data.needsAttention.length ? <Rows items={data.needsAttention} today={data.today} /> : <p>You&apos;re all caught up.</p>}{data.needsAttention.length === 5 && <small>Showing the first five unfinished tasks, overdue and due today first.</small>}</section>
    <section className="upcoming-section"><h2>Upcoming deadlines</h2>{data.upcomingDeadlines.length ? <Rows items={data.upcomingDeadlines} today={data.today} /> : <p>No deadlines in the next 30 days.</p>}<small>Next 30 days, after today. Up to five shown. Dates use the UTC calendar day.</small></section>
  </>;
}
