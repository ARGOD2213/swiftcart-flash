'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import type { OrderView } from '@/lib/api';
import { money } from '@/lib/format';

const LABEL: Record<OrderView['status'], string> = {
  PENDING_PAYMENT: 'Awaiting payment',
  PAID: 'Paid · preparing to ship',
  FULFILLED: 'Handed to the courier',
  EXPIRED: 'Reservation ended',
  CANCELLED: 'Cancelled',
  REVIEW: 'Payment under review',
};

const REFUND_REASON: Record<string, string> = {
  duplicate_payment: 'you paid twice; the extra payment was refunded',
  unknown_order: "we couldn't match the payment to an order",
};

/** Polls while something is still happening (payment pending, refund in flight), then stops. */
export function OrderStatus({ id }: { id: string }) {
  const [order, setOrder] = useState<OrderView | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let stop = false;
    let timer: ReturnType<typeof setTimeout>;
    const load = async () => {
      try {
        const res = await fetch(`/api/sc/orders/${encodeURIComponent(id)}`, { cache: 'no-store' });
        if (res.status === 401) { window.location.href = `/login?next=/orders/${id}`; return; }
        if (res.status === 404) { setError('No such order.'); return; }
        if (!res.ok) throw new Error();
        const o: OrderView = await res.json();
        if (stop) return;
        setOrder(o);
        setError(null);
        const settling = o.status === 'PENDING_PAYMENT' || o.payments.some((p) => p.status === 'REFUND_PENDING');
        if (settling) timer = setTimeout(load, 3000);
      } catch {
        setError('Couldn’t refresh. Retrying…');
        timer = setTimeout(load, 5000);
      }
    };
    load();
    return () => { stop = true; clearTimeout(timer); };
  }, [id]);

  if (!order) return <p className="muted">{error ?? 'Loading…'}</p>;
  return (
    <div className="panel">
      <p className="kicker">Order {order.id.slice(0, 8)}</p>
      <h3>{LABEL[order.status]}</h3>
      <p className="muted" style={{ margin: 0 }}>{order.quantity} × {order.skuName} · {money(order.amountMinor, order.currency)}</p>
      {order.status === 'PENDING_PAYMENT' && <Link className="btn" href={`/drops/${order.drop}`}>Finish paying</Link>}
      {order.payments.length > 0 && (
        <table className="list">
          <thead><tr><th>Payment</th><th>Amount</th><th>Status</th></tr></thead>
          <tbody>
            {order.payments.map((p) => (
              <tr key={p.id}>
                <td className="mono" style={{ fontSize: 12 }}>{p.id}</td>
                <td>{money(p.amountMinor, order.currency)}</td>
                <td>
                  {p.status === 'REFUND_PENDING' ? 'Refund on its way' : p.status === 'REFUNDED' ? 'Refunded' : p.status.toLowerCase()}
                  {p.refundReason && <div className="faint" style={{ fontSize: 12 }}>{REFUND_REASON[p.refundReason] ?? 'the payment arrived after the item sold out'}</div>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {error && <p className="note warn">{error}</p>}
    </div>
  );
}
