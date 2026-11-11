import AuthForm from "@/components/auth-form";
export default async function LoginPage({ searchParams }: { searchParams: Promise<{ notice?: string }> }) {
  const { notice } = await searchParams;
  const message = notice === "password-changed" ? "Password changed successfully." : notice === "account-deleted" ? "Your account has been deleted." : undefined;
  return <AuthForm mode="login" notice={message} />;
}
