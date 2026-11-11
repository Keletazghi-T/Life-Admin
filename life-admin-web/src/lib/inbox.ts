import { request } from "./documents";
export type AttentionItem = { id: string; type: "DOCUMENT" | "RECEIPT" | "TASK" | "DEADLINE"; title: string; context: string; reasons: string[]; status: string; date: string | null; dateLabel: string; createdAt: string; href: string; action: string; taskId: string | null; developmentSample: boolean };
export type Activity = { id: string; title: string; name: string; occurredAt: string; href: string; developmentSample: boolean };
export type UnifiedDashboard = { documentCount: number; receiptCount: number; openTaskCount: number; completedTaskCount: number; upcomingDeadlineCount: number; receiptsNeedingReviewCount: number; attentionCount: number; attention: AttentionItem[]; recentActivity: Activity[] };
export type Inbox = { items: AttentionItem[]; totalCount: number; attentionCount: number; hasMore: boolean; today: string };
export const dashboard = () => request<UnifiedDashboard>("/api/dashboard");
export const inbox = (filter: string, sort: string, page: number) => request<Inbox>(`/api/inbox?${new URLSearchParams({ filter, sort, page: String(page) })}`);
