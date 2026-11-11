import { request } from "./documents";
export type Reminder = { id: string; taskId: string; reminderDate: string; status: "PENDING" | "DISMISSED"; actionable: boolean; createdAt: string; updatedAt: string };
export type ReminderInput = { reminderDate: string } | { daysBefore: number };
const json = (method: string, body: unknown): RequestInit => ({ method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
export const listReminders = (taskId: string) => request<Reminder[]>(`/api/tasks/${taskId}/reminders`);
export const addReminder = (taskId: string, input: ReminderInput) => request<Reminder>(`/api/tasks/${taskId}/reminders`, json("POST", input));
export const editReminder = (id: string, reminderDate: string) => request<Reminder>(`/api/reminders/${id}`, json("PUT", { reminderDate }));
export const deleteReminder = (id: string) => request<void>(`/api/reminders/${id}`, { method: "DELETE" });
export const setReminderStatus = (id: string, status: Reminder["status"]) => request<Reminder>(`/api/reminders/${id}/status`, json("PATCH", { status }));
// Calendar dates are formatted in UTC, never parsed as a local midnight instant.
export const formatDate = (date: string) => new Date(`${date}T00:00:00Z`).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric", timeZone: "UTC" });
export const todayDate = () => new Date().toISOString().slice(0, 10);
export const daysBetween = (from: string, to: string) => Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86400000);
