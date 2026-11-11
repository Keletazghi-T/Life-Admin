"use client";
import { type ReactNode, useEffect, useState, useRef } from "react";
import TaskReminders from "./reminders";
import { LoaderCircle, Plus } from "lucide-react";
import type { DocumentAnalysis } from "@/lib/documents";
import { Task, TaskInput, changeTaskStatus, createTask, deleteTask, generateTasks, listTasks, updateTask } from "@/lib/tasks";

function TaskEditor({ task, busy, onSave, onCancel }: { task: Task | null; busy: boolean; onSave: (input: TaskInput) => Promise<void>; onCancel: () => void }) {
  const [title, setTitle] = useState(task?.title ?? "");
  const [description, setDescription] = useState(task?.description ?? "");
  const [dueDate, setDueDate] = useState(task?.dueDate ?? "");
  return (
    <form className="task-editor" onSubmit={event => { event.preventDefault(); if (title.trim()) void onSave({ title: title.trim(), description: description.trim() || null, dueDate: dueDate || null }); }}>
      <h3>{task ? "Edit task" : "New task"}</h3>
      <label htmlFor="task-title">Task title</label>
      <input id="task-title" value={title} onChange={e => setTitle(e.target.value)} required maxLength={1000} autoFocus disabled={busy} />
      <label htmlFor="task-description">Description <span>(optional)</span></label>
      <textarea id="task-description" value={description} onChange={e => setDescription(e.target.value)} maxLength={4000} rows={3} disabled={busy} />
      <label htmlFor="task-due-date">Due date <span>(optional)</span></label>
      <input id="task-due-date" type="date" min="0001-01-01" max="9999-12-31" value={dueDate} onChange={e => setDueDate(e.target.value)} disabled={busy} />
      <div className="task-form-actions"><button className="primary" disabled={busy || !title.trim()}>{busy ? "Saving…" : "Save task"}</button><button className="secondary" type="button" disabled={busy} onClick={onCancel}>Cancel</button></div>
    </form>
  );
}

export default function ActionPlan({ documentId, analysis, onProgress, developmentTools, developmentEnabled }: { documentId: string; analysis: DocumentAnalysis | null; onProgress: (progress: { completed: number; total: number } | null) => void; developmentTools: ReactNode; developmentEnabled: boolean }) {
  const confirmed = analysis?.confirmedContent;
  const [selected, setSelected] = useState<string[]>([]);
  const mutationLock = useRef(false);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [editor, setEditor] = useState<Task | "new" | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    listTasks(documentId).then(data => { if (active) setTasks(data); })
      .catch(e => { if (active) setError(e instanceof Error ? e.message : "Could not load tasks."); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [documentId]);

  useEffect(() => {
    if (loading) return;
    function openLinkedTask() {
      const anchor = window.location.hash.slice(1);
      if (!/^task-[a-f0-9-]{36}$/.test(anchor)) return;
      const row = document.getElementById(anchor);
      row?.scrollIntoView({ block: "start" });
      row?.focus({ preventScroll: true });
    }
    openLinkedTask();
    window.addEventListener("hashchange", openLinkedTask);
    return () => window.removeEventListener("hashchange", openLinkedTask);
  }, [loading, documentId]);

  async function mutate(operation: () => Promise<void>, success: string) {
    if (mutationLock.current) return;
    mutationLock.current = true; setBusy(true); setError(""); setMessage("");
    try { await operation(); setMessage(success); }
    catch (e) { setError(e instanceof Error ? e.message : "Could not save your changes. Please try again."); }
    finally { mutationLock.current = false; setBusy(false); }
  }
  async function save(input: TaskInput) {
    await mutate(async () => {
      if (editor && editor !== "new") {
        const updated = await updateTask(editor.id, input);
        setTasks(current => current.map(task => task.id === updated.id ? updated : task));
      } else {
        const created = await createTask(documentId, input);
        setTasks(current => [...current, created]);
      }
      setEditor(null);
    }, "Task saved.");
  }
  const completed = tasks.filter(task => task.status === "COMPLETED").length;
  const percentage = tasks.length ? Math.round(completed / tasks.length * 100) : 0;
  useEffect(() => {
    onProgress(loading ? null : { completed, total: tasks.length });
  }, [completed, tasks.length, loading, onProgress]);
  return (
    <section className="action-plan" aria-labelledby="action-plan-heading" aria-busy={busy}>
      <div className="action-plan-heading"><div><h2 id="action-plan-heading">Your action plan</h2><p aria-live="polite">{loading ? "Loading tasks…" : `${completed} of ${tasks.length} completed`}</p></div>
        <button className="secondary" disabled={loading || busy || editor !== null} onClick={() => { setEditor("new"); setDeleting(null); setMessage(""); }}><Plus size={16} />Add task</button>
      </div>
      {confirmed && analysis?.confirmedAt && <fieldset className="task-generation" key={analysis.confirmedAt}>
        <legend>Create tasks from confirmed information</legend><p>Select the actions or dates you want to track. Add reminders using the controls on each created task.</p>
        {confirmed.nextSteps.map((step,index) => <label key={`step-${index}`}><input type="checkbox" checked={selected.includes(`step-${index}`)} disabled={busy} onChange={e => setSelected(items => e.target.checked ? [...items, `step-${index}`] : items.filter(item => item !== `step-${index}`))} />{step}</label>)}
        {confirmed.importantDates?.map((date,index) => <label key={`date-${index}`}><input type="checkbox" checked={selected.includes(`date-${index}`)} disabled={busy || !date.date} onChange={e => setSelected(items => e.target.checked ? [...items, `date-${index}`] : items.filter(item => item !== `date-${index}`))} />{date.label} — {date.date ?? "Date not confirmed"}</label>)}
        {confirmed.deadline && <label><input type="checkbox" checked={selected.includes("deadline")} disabled={busy} onChange={e => setSelected(items => e.target.checked ? [...items, "deadline"] : items.filter(item => item !== "deadline"))} />Deadline — {confirmed.deadline}</label>}
        <button className="secondary" disabled={loading || busy || editor !== null || !selected.length} onClick={() => void mutate(async () => { setTasks(await generateTasks(documentId, { steps: selected.filter(key => key.startsWith("step-")).map(key => Number(key.slice(5))), dates: selected.filter(key => key.startsWith("date-")).map(key => Number(key.slice(5))), includeDeadline: selected.includes("deadline"), confirmedAt: analysis.confirmedAt! })); setSelected([]); }, "Selected tasks are ready. Previously created actions were not duplicated.")}>Create selected tasks</button>
      </fieldset>}
      {analysis && !confirmed && <p>Review and confirm the document details before creating tasks from suggestions. You can still add a manual task.</p>}
      {!loading && <div className="task-progress"><div role="progressbar" aria-label="Task completion" aria-valuemin={0} aria-valuemax={100} aria-valuenow={percentage} aria-valuetext={`${completed} of ${tasks.length} completed`}><span style={{ width: `${percentage}%` }} /></div><span>{percentage}%</span></div>}
      {editor !== null && <TaskEditor key={editor === "new" ? "new" : editor.id} task={editor === "new" ? null : editor} busy={busy} onSave={save} onCancel={() => setEditor(null)} />}
      {error && <p role="alert" className="message error">{error}</p>}
      {message && <p role="status" className="task-feedback">{message}</p>}
      {loading ? <p className="detail-loading"><LoaderCircle className="spin" size={18} />Loading tasks…</p> : tasks.length === 0 ? <p className="task-empty">No tasks yet. Add a task or generate an action plan from a saved analysis.</p> : (
        <div className="task-list">{tasks.map(task => (
          <article tabIndex={-1} id={`task-${task.id}`} key={task.id} className={`task-row ${task.status === "COMPLETED" ? "task-completed" : ""}`} data-task-id={task.id}>
            <input type="checkbox" checked={task.status === "COMPLETED"} disabled={busy || editor !== null}
              aria-label={`Mark ${task.title} ${task.status === "COMPLETED" ? "incomplete" : "completed"}`}
              onChange={() => void mutate(async () => { const updated = await changeTaskStatus(task.id, task.status === "COMPLETED" ? "TODO" : "COMPLETED"); setTasks(current => current.map(item => item.id === updated.id ? updated : item)); }, task.status === "COMPLETED" ? "Task reopened." : "Task completed.")} />
            <div className="task-content"><h3>{task.title}</h3>{task.description && <p className="task-description">{task.description}</p>}
              <p className="task-date">{task.dueDate ? <>Due <time dateTime={task.dueDate}>{new Date(`${task.dueDate}T00:00:00Z`).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric", timeZone: "UTC" })}</time></> : "No due date"}</p>
              {task.developmentMock && <span className="sample-task-label">DEVELOPMENT sample task</span>}
              <TaskReminders task={task} disabled={busy || editor !== null} />
              {deleting === task.id && <div className="task-delete-confirm"><p>Delete this task?</p><button className="secondary danger" disabled={busy} onClick={() => void mutate(async () => { await deleteTask(task.id); setTasks(current => current.filter(item => item.id !== task.id)); setDeleting(null); }, "Task deleted.")}>Delete task</button><button className="secondary" disabled={busy} onClick={() => setDeleting(null)}>Cancel</button></div>}
            </div>
            <div className="task-row-actions"><button disabled={busy || editor !== null} aria-label={`Edit ${task.title}`} onClick={() => { setEditor(task); setDeleting(null); setMessage(""); }}>Edit</button><button disabled={busy || editor !== null} aria-label={`Delete ${task.title}`} onClick={() => { setDeleting(task.id); setMessage(""); }}>Delete</button></div>
          </article>
        ))}</div>
      )}
      {developmentEnabled && (developmentTools || analysis?.source === "DEVELOPMENT_MOCK") && <details className="development-tools">
        <summary>DEVELOPMENT TOOLS</summary>
        {developmentTools}
        {analysis?.source === "DEVELOPMENT_MOCK" && <>
          <p>Sample data, not real AI analysis. Generated tasks and dates are examples for testing.</p>
          {analysis.confirmedContent && analysis.nextSteps.length > 0 && <button className="secondary" disabled={loading || busy || editor !== null} onClick={() => void mutate(async () => setTasks(await generateTasks(documentId)), "Action plan ready. Existing generated tasks were kept without duplication.")}>Generate sample action plan</button>}
        </>}
      </details>}
    </section>
  );
}
