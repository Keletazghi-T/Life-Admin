"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { ChevronRight } from "lucide-react";
import { request } from "@/lib/documents";
import { daysBetween, formatDate, todayDate } from "@/lib/reminders";
type UpcomingTask = { taskId: string; taskTitle: string; dueDate: string; documentId: string; documentName: string; developmentMock: boolean };
export default function UpcomingDeadlines() {
  const [tasks, setTasks] = useState<UpcomingTask[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [today, setToday] = useState(todayDate);
  useEffect(() => {
    let active = true;
    request<UpcomingTask[]>("/api/tasks/upcoming").then(data => { if (active) setTasks(data); })
      .catch(e => { if (active) setError(e instanceof Error ? e.message : "Could not load deadlines."); })
      .finally(() => { if (active) setLoading(false); });
    const timer = setInterval(() => setToday(todayDate()), 60000);
    return () => { active = false; clearInterval(timer); };
  }, []);
  return <section className="upcoming-section" aria-labelledby="upcoming-heading">
    <h2 id="upcoming-heading">Upcoming deadlines</h2>
    {loading ? <p role="status">Loading deadlines…</p> : error ? <p role="alert" className="message error">{error}</p> : tasks.length === 0 ? <p className="upcoming-empty">No incomplete tasks with due dates.</p> : <>
      <div className="upcoming-list">{tasks.map(task => {
        const days = daysBetween(today, task.dueDate);
        return <Link className="upcoming-row" href={`/documents/${task.documentId}`} key={task.taskId}>
          <div><h3>{task.taskTitle}</h3><p>{task.documentName}</p>{task.developmentMock && <span className="sample-task-label">DEVELOPMENT sample task</span>}</div>
          <div className="upcoming-date"><time dateTime={task.dueDate}>Due {formatDate(task.dueDate)}</time><span>{days === 0 ? "Due today" : days < 0 ? `Overdue by ${-days} ${days === -1 ? "day" : "days"}` : `${days} ${days === 1 ? "day" : "days"} remaining`}</span></div><ChevronRight size={16} aria-hidden="true" />
        </Link>;
      })}</div>
      <p className="reminder-note">Nearest deadlines first, including overdue tasks. Up to 50 tasks shown. Day counts use the UTC calendar day.</p>
    </>}
  </section>;
}
