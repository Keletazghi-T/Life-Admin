import { request } from "./documents";
export const categories = { BUSINESS: "Business", MEDICAL: "Medical", CHARITY: "Charity / Donations", HOME: "Home / Property", EDUCATION: "Education", TRANSPORTATION: "Transportation", MEALS: "Meals", OFFICE: "Office / Supplies", OTHER: "Other" };
export type Category = keyof typeof categories;
export type ReceiptInput = { merchant: string; purchaseDate: string; amount: string; currency: string; category: Category | null; description: string; taxRelevant: boolean; notes: string };
export type Receipt = Omit<ReceiptInput, "merchant" | "purchaseDate" | "amount"> & { merchant: string | null; purchaseDate: string | null; amount: string | null; reviewStatus: "UPLOADED" | "NEEDS_REVIEW" | "REVIEWED"; extractionSource: string | null; extractedAt: string | null; suggestedMerchant: string | null; suggestedDescription: string | null; suggestedPurchaseDate: string | null; suggestedAmount: string | null; suggestedCurrency: string | null; suggestedCategory: Category | null; extractionStatus: string; extractionMethod: string | null; extractionTimestamp: string | null; extractionError: string | null; extractionModel: string | null; id: string; originalFileName: string; contentType: string; fileSize: number; status: string; createdAt: string; updatedAt: string };
export type ReceiptSummary = { year: number; currency: string; receiptCount: number; totalAmount: string; taxRelevantCount: number; taxRelevantAmount: string; missingCategoryCount: number; missingInformationCount: number; categories: { category: Category | null; count: number; amount: string }[] };
export const taxNotice = "Marking a receipt as potentially tax relevant does not mean the expense is tax deductible. Keep records and verify tax treatment when filing.";
export const currentYear = () => new Date().getUTCFullYear();
export function money(value: string | null) { if (value === null) return "Not entered"; const [whole, fraction = ""] = value.split("."); return `$${BigInt(whole || "0").toLocaleString()}.${fraction.padEnd(2, "0").slice(0, 2)}`; }
export const categoryName = (category: Category | null) => category ? categories[category] : "Uncategorized";
export const getReceipt = (id: string) => request<Receipt>(`/api/receipts/${encodeURIComponent(id)}`);
export const receiptSummary = (year: number) => request<ReceiptSummary>(`/api/receipts/summary?year=${year}`);
export const listReceipts = (query: URLSearchParams) => request<{ items: Receipt[]; hasMore: boolean }>(`/api/receipts?${query}`);
export const saveReceipt = (input: ReceiptInput, id?: string, file?: File) => {
  if (id) return request<Receipt>(`/api/receipts/${id}`, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(input) });
  const body = new FormData(); if (file) body.append("file", file); body.append("metadata", new Blob([JSON.stringify(input)], { type: "application/json" }));
  return request<Receipt>("/api/receipts", { method: "POST", body });
};
export const deleteReceipt = (id: string) => request<void>(`/api/receipts/${id}`, { method: "DELETE" });

export type ReceiptSuggestions = { description: string | null; merchant: string | null; purchaseDate: string | null; amount: string | null; currency: string | null; category: Category | null; source: string; notice: string; extractionStatus: string; extractionMethod: string | null; extractionTimestamp: string | null; extractionError: string | null };
export const reviewStatusLabel = (status: Receipt["reviewStatus"]) => ({ UPLOADED: "Uploaded · Needs review", NEEDS_REVIEW: "Needs review", REVIEWED: "Reviewed" })[status];
export const extractionCapabilities = () => request<{ extractionEnabled: boolean }>("/api/receipts/extraction-capabilities");
export const extractReceipt = (id: string, version?: string) => request<ReceiptSuggestions>(`/api/receipts/${encodeURIComponent(id)}/extract`, { method: "POST", headers: version ? { "If-Match": version } : undefined });
export const receiptSuggestions = (id: string) => request<ReceiptSuggestions>(`/api/receipts/${encodeURIComponent(id)}/extraction`);
export const uploadReceipt = (file: File, requestId: string = crypto.randomUUID()) => { const body = new FormData(); body.append("file", file); return request<Receipt>("/api/receipts", { method: "POST", headers: { "Idempotency-Key": requestId }, body }); };
