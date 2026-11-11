import ReceiptDetail from "./receipt-detail";
export default async function Page({ params }: { params: Promise<{ id: string }> }) { const { id } = await params; return <ReceiptDetail id={id} />; }
