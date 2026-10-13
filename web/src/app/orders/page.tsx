import Link from 'next/link';
import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import { API_URL, TOKEN_COOKIE, type OrderView } from '@/lib/api';
import { money } from '@/lib/format';

export const dynamic = 'force-dynamic';

export default async function Orders() {
  const token = (await cookies()).get(TOKEN_COOKIE)?.value;
  if (!token) redirect('/login?next=/orders');
  let orders: OrderView[] | null = null;
  let status = 0;
  try {
    const res = await fetch(`${API_URL}/api/orders`, { headers: { authorization: `Bearer ${token}` }, cache: 'no-store' });
    status = res.status;
    if (res.ok) orders = await res.json();
  } catch {
    orders = null;
  }
  if (status === 401) redirect('/login?next=/orders'); // outside the try: redirect() works by throwing
  return (
    <div className="wrap" style={{ padding: '40px 20px 64px' }}>
      <p className="kicker">Your orders</p>
      <h1 className="display" style={{ fontSize: 56, marginBottom: 24 }}>Orders</h1>
      {orders === null ? (
        <p className="note warn">We couldn&apos;t load your orders right now. Refresh in a moment.</p>
      ) : orders.length === 0 ? (
        <p className="muted">No orders yet. <Link href="/">See the drops</Link>.</p>
      ) : (
        <table className="list">
          <thead><tr><th>Item</th><th>Status</th><th>Total</th><th>Placed</th></tr></thead>
          <tbody>
            {orders.map((o) => (
              <tr key={o.id}>
                <td><Link href={`/orders/${o.id}`}>{o.quantity} × {o.skuName}</Link></td>
                <td>{label(o.status)}</td>
                <td>{money(o.amountMinor, o.currency)}</td>
                <td className="muted">{new Date(o.createdAt).toISOString().slice(0, 16).replace('T', ' ')} UTC</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function label(s: OrderView['status']): string {
  return { PENDING_PAYMENT: 'Awaiting payment', PAID: 'Paid', FULFILLED: 'Shipped', EXPIRED: 'Expired', CANCELLED: 'Cancelled', REVIEW: 'Payment under review' }[s];
}
