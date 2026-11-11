import { request } from "./documents";
export type Breakdown = { name: string | null; count: number; amount: string };
export type ReceiptInsights = {
  year: number; currency: string; years: number[]; receiptCount: number;
  totalAmount: string; averageAmount: string;
  categories: Breakdown[]; merchants: Breakdown[]; months: { month: number; amount: string }[];
};
export const receiptInsights = (year: number) => request<ReceiptInsights>(`/api/receipts/insights?year=${year}`);
