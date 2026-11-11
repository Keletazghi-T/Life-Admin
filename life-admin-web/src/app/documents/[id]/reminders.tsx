"use client";
import { useEffect, useState } from "react";
import type { Task } from "@/lib/tasks";
import { Reminder, addReminder, daysBetween, deleteReminder, editReminder, formatDate, listReminders, setReminderStatus, todayDate } from "@/lib/reminders";

export default function TaskReminders({ task, disabled }: { task: Task; disabled: boolean }) {
  const [open, setOpen] = useState(false);
  return <div className="task-reminders">
    <button className="reminder-toggle" aria-expanded={open} aria-controls={`reminders-${task.id}`} disabled={disabled} onClick={() => setOpen(!open)}>Reminders</button>
    {open && <ReminderPanel key={`${task.id}-${task.status}-${task.dueDate}`} task={task} disabled={disabled} />}
  </div>;
}
function ReminderPanel({ task, disabled }: { task: Task; disabled: boolean }) {
  const [reminders, setReminders] = useState<Reminder[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [preset, setPreset] = useState("7");
  const [date, setDate] = useState("");
  const [editing, setEditing] = useState<string | null>(null);
  const [editedDate, setEditedDate] = useState("");
  const [deleting, setDeleting] = useState<string | null>(null);
  const canAdd = task.status === "TODO" && !!task.dueDate;
  const locked = loading || busy || disabled;
  useEffect(() => {
    let active = true;
    listReminders(task.id).then(data => { if (active) setReminders(data); })
      .catch(e => { if (active) setError(e instanceof Error ? e.message : "Could not load reminders."); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [task.id]);
  async function mutate(operation: () => Promise<void>, success: string) {
    if (locked) return;
    setBusy(true); setError(""); setMessage("");
    try { await operation(); setMessage(success); }
    catch (e) { setError(e instanceof Error ? e.message : "Could not save reminder."); }
    finally { setBusy(false); }
  }
  function replace(updated: Reminder) { setReminders(current => current.map(r => r.id === updated.id ? updated : r)); }
  return <section id={`reminders-${task.id}`} className="reminder-panel" aria-label={`Reminders for ${task.title}`} aria-busy={busy}>
    <p className="reminder-note">Reminders are saved here only. Email, SMS, and push notifications are not sent. Dates use the UTC calendar day.</p>
    {loading ? <p role="status">Loading reminders…</p> : reminders.length === 0 ? <p>No reminders set.</p> : <ul className="reminder-list">
      {[...reminders].sort((a, b) => a.reminderDate.localeCompare(b.reminderDate)).map(reminder => {
        const days = task.dueDate ? daysBetween(reminder.reminderDate, task.dueDate) : null;
        return <li key={reminder.id} data-reminder-id={reminder.id}>
          <p><time dateTime={reminder.reminderDate}>{formatDate(reminder.reminderDate)}</time>{days !== null && days >= 0 && <> · {days === 0 ? "On due date" : `${days} ${days === 1 ? "day" : "days"} before`}</>}</p>
          <span className="reminder-state">{reminder.status === "DISMISSED" ? "Dismissed" : reminder.actionable ? "Pending" : "Inactive — task completed, date passed, or due date changed"}</span>
          <div className="reminder-actions">
            {canAdd && <button disabled={locked} onClick={() => { setEditing(reminder.id); setEditedDate(reminder.reminderDate); setDeleting(null); }}>Edit reminder</button>}
            {reminder.status === "PENDING" ? <button disabled={locked} onClick={() => void mutate(async () => replace(await setReminderStatus(reminder.id, "DISMISSED")), "Reminder dismissed.")}>Dismiss reminder</button> : canAdd && <button disabled={locked} onClick={() => void mutate(async () => replace(await setReminderStatus(reminder.id, "PENDING")), "Reminder restored.")}>Restore reminder</button>}
            <button disabled={locked} onClick={() => { setDeleting(reminder.id); setEditing(null); }}>Delete reminder</button>
          </div>
          {editing === reminder.id && <form className="reminder-form" onSubmit={e => { e.preventDefault(); void mutate(async () => { replace(await editReminder(reminder.id, editedDate)); setEditing(null); }, "Reminder updated."); }}>
            <label htmlFor={`edit-reminder-${reminder.id}`}>Reminder date</label>
            <input id={`edit-reminder-${reminder.id}`} type="date" required min={todayDate()} max={task.dueDate ?? undefined} value={editedDate} disabled={locked} onChange={e => setEditedDate(e.target.value)} />
            <button className="secondary" disabled={locked || !editedDate}>Save reminder</button><button className="secondary" type="button" disabled={locked} onClick={() => setEditing(null)}>Cancel</button>
          </form>}
          {deleting === reminder.id && <div className="reminder-actions"><span>Delete this reminder?</span><button disabled={locked} onClick={() => void mutate(async () => { await deleteReminder(reminder.id); setReminders(current => current.filter(r => r.id !== reminder.id)); setDeleting(null); }, "Reminder deleted.")}>Confirm delete reminder</button><button disabled={locked} onClick={() => setDeleting(null)}>Cancel</button></div>}
        </li>;
      })}
    </ul>}
    {canAdd ? <form className="reminder-form" onSubmit={e => { e.preventDefault(); void mutate(async () => { const saved = await addReminder(task.id, preset === "custom" ? { reminderDate: date } : { daysBefore: Number(preset) }); setReminders(current => [...current, saved]); }, "Reminder saved. No notification has been sent."); }}>
      <label htmlFor={`reminder-preset-${task.id}`}>Remind me</label>
      <select id={`reminder-preset-${task.id}`} value={preset} disabled={locked} onChange={e => setPreset(e.target.value)}><option value="7">7 days before</option><option value="3">3 days before</option><option value="1">1 day before</option><option value="custom">Choose date</option></select>
      {preset === "custom" && <><label htmlFor={`reminder-date-${task.id}`}>Choose reminder date</label><input id={`reminder-date-${task.id}`} type="date" required min={todayDate()} max={task.dueDate ?? undefined} value={date} disabled={locked} onChange={e => setDate(e.target.value)} /></>}
      <button className="secondary" disabled={locked || (preset === "custom" && !date)}>Add reminder</button>
    </form> : <p>{task.status === "COMPLETED" ? "Reminders are inactive while this task is completed. History is kept." : "Set a due date to add reminders. Existing dates are kept."}</p>}
    {error && <p role="alert" className="message error">{error}</p>}
    {message && <p role="status" className="task-feedback">{message}</p>}
  </section>;
}
