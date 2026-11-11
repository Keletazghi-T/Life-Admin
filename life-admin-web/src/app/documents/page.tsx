import DocumentsPage from "./documents-page";
export default async function Page({ searchParams }: { searchParams: Promise<{ deleted?: string }> }) {
  const { deleted } = await searchParams;
  return <DocumentsPage deleted={deleted === "1"} />;
}
