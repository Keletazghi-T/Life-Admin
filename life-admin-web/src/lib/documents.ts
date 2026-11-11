export type Document = { id: string; originalFileName: string; displayName: string; fileType: string; fileSize: number; uploadDate: string; status: string };
export class ApiError extends Error {
  constructor(message: string, public readonly status: number) { super(message); }
}
export async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const headers = new Headers(options?.headers);
  if (!["GET", "HEAD", "OPTIONS"].includes((options?.method ?? "GET").toUpperCase())) {
    // Fetch a fresh masked session token: login/logout rotate it, including across tabs.
    const tokenResponse = await fetch("/api/auth/csrf", { credentials: "same-origin", cache: "no-store" });
    if (!tokenResponse.ok) throw new ApiError("Could not secure this request. Refresh and try again.", tokenResponse.status);
    const csrf: { headerName: string; token: string } = await tokenResponse.json();
    headers.set(csrf.headerName, csrf.token);
  }
  const response = await fetch(path, { ...options, credentials: "same-origin", cache: "no-store", headers });
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith("/api/auth/") && typeof window !== "undefined") {
      window.dispatchEvent(new Event("life-admin-unauthorized"));
    }
    const error = await response.json().catch(() => null);
    throw new ApiError(error?.message ?? "Could not reach Life Admin. Check that the backend is running and try again.", response.status);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
export const listDocuments = (page = 0, search = "", sort = "newest") => request<Document[]>(`/api/documents?${new URLSearchParams({ page: String(page), search, sort })}`);
export const documentStatus = (status: string) => status === "DELETION_PENDING" ? "Deletion pending" : status === "UPLOADED" ? "Uploaded" : status;
export const renameDocument = (id: string, displayName: string) => request<Document>(`/api/documents/${id}`, { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ displayName }) });
export const deleteDocument = (id: string) => request<void>(`/api/documents/${id}`, { method: "DELETE" });
export function uploadDocument(file: File) {
  const body = new FormData(); body.append("file", file);
  return request<Document>("/api/documents/upload", { method: "POST", body });
}

export type DocumentAnalysis = {
  source: "AI" | "DEVELOPMENT_MOCK" | "MANUAL";
  meaning: string | null;
  evidence: string[];
  importantDates: { label: string; date: string | null; evidence: string }[];
  confirmedContent: AnalysisContent | null;
  confirmedAt: string | null;
  id: string;
  documentId: string;
  documentType: string;
  summary: string;
  actionRequired: boolean | null;
  deadline: string | null;
  requiredDocuments: string[];
  nextSteps: string[];
  warnings: string[];
  createdAt: string;
  updatedAt: string;
};
export const getDocument = (id: string) => request<Document>(`/api/documents/${encodeURIComponent(id)}`);
export const getDocumentAnalysis = (id: string) => request<DocumentAnalysis>(`/api/documents/${encodeURIComponent(id)}/analysis`);
export const analyzeDocument = (id: string) => request<DocumentAnalysis>(`/api/documents/${encodeURIComponent(id)}/analyze`, { method: "POST" });

export const getDevelopmentCapabilities = () => request<{ mockAnalysisEnabled: boolean }>("/api/development/capabilities");
export const loadDevelopmentAnalysis = (id: string) => request<DocumentAnalysis>(`/api/development/documents/${encodeURIComponent(id)}/mock-analysis`, { method: "POST" });

export type AnalysisContent = Pick<DocumentAnalysis, "documentType" | "summary" | "actionRequired" | "deadline" | "requiredDocuments" | "nextSteps" | "warnings" | "meaning" | "evidence" | "importantDates">;
export type AnalysisAttempt = { attemptId: string; status: "PROCESSING" | "SUCCEEDED" | "FAILED"; startedAt: string; message: string | null };
export const getAnalysisAttempt = (id: string) => request<AnalysisAttempt | undefined>(`/api/documents/${encodeURIComponent(id)}/analysis-attempt`);
export const retryDocumentAnalysis = (id: string, attemptId: string) => request<DocumentAnalysis>(`/api/documents/${encodeURIComponent(id)}/analyze`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ expectedAttemptId: attemptId, retryConfirmed: true }) });
export const confirmDocumentAnalysis = (id: string, content: AnalysisContent, expectedVersion: string | null) => request<DocumentAnalysis>(`/api/documents/${encodeURIComponent(id)}/analysis/confirm`, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ content, expectedVersion }) });
