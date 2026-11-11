import { request } from "./documents";
export type Account = { id: string; email: string; deletionPending?: boolean };
export const currentUser = () => request<Account>("/api/auth/me");
export const authenticate = (mode: "login" | "register", email: string, password: string) => request<Account>(`/api/auth/${mode}`, {
  method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email, password }),
});
export const logout = () => request<void>("/api/auth/logout", { method: "POST" });

export const changePassword = (currentPassword: string, newPassword: string, confirmNewPassword: string) => request<void>("/api/account/password", {
  method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ currentPassword, newPassword, confirmNewPassword }),
});
export const deleteAccount = (currentPassword: string, confirmation: string) => request<void>("/api/account", {
  method: "DELETE", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ currentPassword, confirmation }),
});
export function accountSignedOut(reason: "password-changed" | "account-deleted") {
  window.dispatchEvent(new Event("life-admin-signing-out"));
  const channel = new BroadcastChannel("life-admin-account"); channel.postMessage("logout"); channel.close();
  window.location.replace(`/login?notice=${reason}`);
}
