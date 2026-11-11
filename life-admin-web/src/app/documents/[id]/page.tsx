import DocumentDetail from "./document-detail";
export default async function DocumentPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <DocumentDetail key={id} id={id} />;
}
