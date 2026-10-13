import { OrderStatus } from '@/components/OrderStatus';

export const dynamic = 'force-dynamic';

export default async function OrderPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <div className="wrap" style={{ padding: '40px 20px 64px', maxWidth: 640 }}>
      <OrderStatus id={id} />
    </div>
  );
}
