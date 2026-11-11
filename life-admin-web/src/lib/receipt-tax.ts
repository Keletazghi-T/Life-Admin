import { request } from "./documents";
import { Receipt, ReceiptSummary } from "./receipts";
export type Review = { id: string; merchant: string | null; purchaseDate: string | null; reasons: string[] };
export type TaxOverview = { summary: ReceiptSummary; recordsNeedingReview: Review[] };
export type TaxRecords = { items: { receipt: Receipt; reviewReasons: string[] }[]; totalCount: number; hasMore: boolean };
export const taxOverview = (year: number) => request<TaxOverview>(`/api/receipts/tax?year=${year}`);
export const taxRecords = (year: number, filter: string, sort: string, page: number) => request<TaxRecords>(`/api/receipts/tax/records?${new URLSearchParams({ year: String(year), filter, sort, page: String(page) })}`);
export async function exportReceipts(year: number, scope: string) {
  const response = await fetch(`/api/receipts/tax/export?${new URLSearchParams({ year: String(year), scope })}`, { credentials: "same-origin", cache: "no-store" });
  if (response.status === 401) window.dispatchEvent(new Event("life-admin-unauthorized"));
  if (!response.ok) throw new Error("We couldn't export your receipts. Please try again.");
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a"); link.href = url; link.download = `life-admin-receipts-${year}.csv`; document.body.appendChild(link); link.click(); link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
