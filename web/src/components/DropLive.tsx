'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { DropView, OrderView, Problem, QueueStatus, StockView } from '@/lib/api';
import { clock, money } from '@/lib/format';
import { signedIn } from '@/lib/session';

/**
 * Everything live on a drop page, as one state machine:
 *   signin → upcoming → join → waiting → admitted → pay → paid
 * with exits to soldout / expired / cancelled / review / closed. State survives a refresh: the line position
 * comes back from the API, a pending order from sessionStorage, and a retried checkout reuses its
 * idempotency key, so a flaky network or a double click never creates a second order.
 */

type Phase =
  | 'loading' | 'signin' | 'upcoming' | 'join' | 'waiting' | 'admitted' | 'pay'
  | 'paid' | 'expired' | 'cancelled' | 'review' | 'soldout' | 'closed';

type Result<T> = { ok: boolean; status: number; data?: T; problem?: Problem; retryAfter?: number };

declare global {
  interface Window {
    Razorpay?: new (options: Record<string, unknown>) => { open(): void };
  }
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

function uuid(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

async function call<T>(url: string, init: RequestInit = {}): Promise<Result<T>> {
  try {
    const res = await fetch(url, { ...init, cache: 'no-store' });
    const text = await res.text();
    const body = text ? JSON.parse(text) : undefined;
    const retryAfter = Number(res.headers.get('retry-after') ?? '') || undefined;
    return res.ok ? { ok: true, status: res.status, data: body as T } : { ok: false, status: res.status, problem: body as Problem, retryAfter };
  } catch {
    return { ok: false, status: 0 }; // offline, DNS, connection reset
  }
}

const store = {
  get(k: string) {
    try { return sessionStorage.getItem(k); } catch { return null; }
  },
  set(k: string, v: string) {
    try { sessionStorage.setItem(k, v); } catch { /* private mode: state lives in memory only */ }
  },
  del(k: string) {
    try { sessionStorage.removeItem(k); } catch { /* ignore */ }
  },
};

type Address = { name: string; line1: string; line2: string; city: string; state: string; pincode: string };
const EMPTY_ADDRESS: Address = { name: '', line1: '', line2: '', city: '', state: '', pincode: '' };

export function DropLive({ drop }: { drop: DropView }) {
  const slug = drop.slug;
  const keys = { checkout: `sc:${slug}:key`, order: `sc:${slug}:order` };
  const [phase, setPhase] = useState<Phase>('loading');
  const [now, setNow] = useState(() => Date.now());
  const [stock, setStock] = useState<StockView | null>(null);
  const [queue, setQueue] = useState<QueueStatus | null>(null);
  const [order, setOrder] = useState<OrderView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [sku, setSku] = useState(drop.skus[0]?.code ?? '');
  const [qty, setQty] = useState(1);
  const [phone, setPhone] = useState('');
  const [address, setAddress] = useState<Address>(EMPTY_ADDRESS);
  const entry = useRef<{ token: string; expiresAt: number } | null>(null);
  const visible = useVisible();

  const opensAt = Date.parse(drop.opensAt);
  const queueOpensAt = Date.parse(drop.queueOpensAt);
  const closesAt = Date.parse(drop.closesAt);

  // ---- clock and live stock -------------------------------------------------------------------------------
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  useEffect(() => {
    if (!visible || phase === 'paid' || phase === 'closed') return;
    let stop = false;
    const tick = async () => {
      const r = await call<StockView>(`/api/sc/drops/${slug}/stock`);
      if (!stop && r.ok && r.data) setStock(r.data);
    };
    tick();
    const t = setInterval(tick, 3000);
    return () => { stop = true; clearInterval(t); };
  }, [slug, visible, phase]);

  // ---- restore state after a refresh ----------------------------------------------------------------------
  const applyOrder = useCallback((o: OrderView) => {
    setOrder(o);
    switch (o.status) {
      case 'PENDING_PAYMENT': setPhase('pay'); break;
      case 'PAID': case 'FULFILLED': setPhase('paid'); store.del(keys.order); break;
      case 'EXPIRED': setPhase('expired'); store.del(keys.order); break;
      case 'CANCELLED': setPhase('cancelled'); store.del(keys.order); break;
      case 'REVIEW': setPhase('review'); store.del(keys.order); break;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [slug]);

  const applyQueue = useCallback((q: QueueStatus) => {
    setQueue(q);
    switch (q.state) {
      case 'NOT_IN_QUEUE': setPhase(Date.now() < queueOpensAt ? 'upcoming' : 'join'); break;
      case 'WAITING': setPhase('waiting'); break;
      case 'ADMITTED':
        entry.current = q.entryToken ? { token: q.entryToken, expiresAt: Date.parse(q.entryTokenExpiresAt ?? '') } : null;
        setPhase('admitted');
        break;
      case 'SOLD_OUT': setPhase('soldout'); break;
      case 'CLOSED': setPhase('closed'); break;
    }
  }, [queueOpensAt]);

  useEffect(() => {
    if (!signedIn()) {
      setPhase('signin');
      return;
    }
    (async () => {
      const saved = store.get(keys.order);
      if (saved) {
        const r = await call<OrderView>(`/api/sc/orders/${saved}`);
        if (r.ok && r.data) {
          applyOrder(r.data);
          return;
        }
        store.del(keys.order);
      }
      if (Date.now() >= closesAt) {
        setPhase('closed');
        return;
      }
      const q = await call<QueueStatus>(`/api/sc/drops/${slug}/queue/me`);
      if (q.status === 401) setPhase('signin');
      else if (q.ok && q.data) applyQueue(q.data);
      else setPhase(Date.now() < queueOpensAt ? 'upcoming' : 'join');
    })();
    try {
      const saved = localStorage.getItem('sc:address');
      if (saved) {
        const a = JSON.parse(saved);
        setAddress({ ...EMPTY_ADDRESS, ...a.address });
        setPhone(a.phone ?? '');
      }
    } catch { /* no saved address */ }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [slug]);

  // the waiting room opens while the page is open
  useEffect(() => {
    if (phase === 'upcoming' && now >= queueOpensAt) setPhase('join');
    if ((phase === 'join' || phase === 'upcoming') && now >= closesAt) setPhase('closed');
  }, [phase, now, queueOpensAt, closesAt]);

  // ---- the line -------------------------------------------------------------------------------------------
  const join = useCallback(async () => {
    setBusy(true);
    setError(null);
    for (let attempt = 0; attempt < 5; attempt++) {
      const r = await call<QueueStatus>(`/api/sc/drops/${slug}/queue`, { method: 'POST' });
      if (r.ok && r.data) {
        applyQueue(r.data);
        break;
      }
      const code = r.problem?.code;
      if (r.status === 401) { setPhase('signin'); break; }
      if (code === 'sold_out') { setPhase('soldout'); break; }
      if (code === 'drop_closed') { setPhase('closed'); break; }
      if (code === 'queue_not_open') { setPhase('upcoming'); break; }
      if (r.status === 0 || r.status >= 500 || r.status === 429) {
        setError('The store is very busy. Holding your spot and retrying…');
        await sleep((r.retryAfter ?? 1) * 1000 + Math.random() * 1000);
        continue;
      }
      setError(r.problem?.detail ?? 'Could not join the line');
      break;
    }
    setBusy(false);
  }, [slug, applyQueue]);

  useEffect(() => {
    if (phase !== 'waiting' || !queue) return;
    // poll less often when the tab is in the background
    const delay = (visible ? queue.pollAfterMs : Math.max(queue.pollAfterMs, 10_000)) + Math.random() * 500;
    const t = setTimeout(async () => {
      const r = await call<QueueStatus>(`/api/sc/drops/${slug}/queue/me`);
      if (r.ok && r.data) applyQueue(r.data);
      else setQueue({ ...queue }); // keep polling
    }, delay);
    return () => clearTimeout(t);
  }, [phase, queue, slug, visible, applyQueue]);

  // ---- checkout -------------------------------------------------------------------------------------------
  const reserve = useCallback(async () => {
    const errs = validate(phone, address);
    setFieldErrors(errs);
    if (Object.keys(errs).length) return;
    if (!entry.current) { await join(); return; }
    setBusy(true);
    setError(null);
    try { localStorage.setItem('sc:address', JSON.stringify({ phone, address })); } catch { /* fine */ }
    const body = JSON.stringify({ sku, quantity: qty, phone, address });
    let key = store.get(keys.checkout) ?? uuid();
    store.set(keys.checkout, key);
    for (let attempt = 0; attempt < 6; attempt++) {
      const r = await call<OrderView>(`/api/sc/drops/${slug}/checkout`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', 'idempotency-key': key, 'x-entry-token': entry.current?.token ?? '' },
        body,
      });
      if (r.ok && r.data) {
        store.del(keys.checkout);
        store.set(keys.order, r.data.id);
        applyOrder(r.data);
        break;
      }
      const code = r.problem?.code ?? '';
      // transient: retry with the SAME key, so a request that did go through isn't repeated
      if (r.status === 0 || r.status >= 500 || r.status === 429 || code === 'request_in_progress') {
        setError('Busy moment. Retrying your order (you will not be charged twice)…');
        await sleep(Math.min(8000, 500 * 2 ** attempt) + Math.random() * 400);
        continue;
      }
      if (code === 'idempotency_key_reused') {
        // the form changed since the last attempt: it's a new request
        key = uuid();
        store.set(keys.checkout, key);
        continue;
      }
      store.del(keys.checkout); // a definite answer
      if (code.startsWith('entry_token')) {
        entry.current = null;
        setError('Your checkout window closed. Getting you back in…');
        await join();
        break;
      }
      handleProblem(code, r.problem);
      break;
    }
    setBusy(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sku, qty, phone, address, slug, join, applyOrder]);

  function handleProblem(code: string, p?: Problem) {
    switch (code) {
      case 'sold_out': setPhase('soldout'); return;
      case 'drop_closed': setPhase('closed'); return;
      case 'drop_not_open': setPhase('upcoming'); return;
      case 'unauthorized': case 'invalid_token': setPhase('signin'); return;
      case 'insufficient_stock': setError(`Only ${p?.available ?? 'a few'} left in this size. Lower the quantity or pick another size.`); return;
      case 'limit_reached': setError(`You've reached the limit of ${drop.perCustomerLimit} for this drop.`); return;
      case 'phone_limit_reached': setError('This phone number has already reached the limit for this drop.'); return;
      case 'invalid_phone': setFieldErrors({ phone: 'Enter a 10-digit Indian mobile number' }); return;
      case 'invalid_request': setFieldErrors(mapFields(p?.fields)); return;
      default: setError(p?.detail ?? 'Something went wrong. Please try again.');
    }
  }

  // ---- paying ---------------------------------------------------------------------------------------------
  const refreshOrder = useCallback(async () => {
    if (!order) return;
    const r = await call<OrderView>(`/api/sc/orders/${order.id}`);
    if (r.ok && r.data) applyOrder(r.data);
  }, [order, applyOrder]);

  useEffect(() => {
    if (phase !== 'pay' || !order) return;
    const t = setInterval(refreshOrder, busy ? 1500 : 4000);
    return () => clearInterval(t);
  }, [phase, order, busy, refreshOrder]);

  const reservedLeft = order ? Date.parse(order.reservedUntil) - now : 0;
  useEffect(() => {
    // the hold just ended: ask the server what happened (it may also have been paid at the last second)
    if (phase === 'pay' && order && reservedLeft <= 0) refreshOrder();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phase, reservedLeft <= 0]);

  async function pay(simulated?: 'captured' | 'failed') {
    if (!order) return;
    setError(null);
    const p = order.payment;
    if (!p) {
      setBusy(true);
      const r = await call<OrderView>(`/api/sc/orders/${order.id}/payment-session`, { method: 'POST' });
      setBusy(false);
      if (r.ok && r.data) applyOrder(r.data);
      else setError('The payment provider is still not responding. Your pair stays reserved; try again in a moment.');
      return;
    }
    if (p.gateway === 'stripe' && p.checkoutUrl) {
      window.location.href = p.checkoutUrl;
      return;
    }
    if (p.gateway === 'simulator') {
      setBusy(true);
      await call('/api/sim/pay', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ gatewayOrderId: p.gatewayOrderId, status: simulated ?? 'captured' }),
      });
      await refreshOrder();
      setBusy(false);
      return;
    }
    if (p.gateway === 'razorpay') {
      await loadScript('https://checkout.razorpay.com/v1/checkout.js');
      if (!window.Razorpay) {
        setError('Could not load the payment window. Check your connection and try again.');
        return;
      }
      const rzp = new window.Razorpay({
        key: p.keyId,
        order_id: p.gatewayOrderId,
        amount: p.amountMinor,
        currency: p.currency,
        name: 'SwiftCart',
        description: `${order.quantity} × ${order.skuName}`,
        prefill: { contact: phone },
        handler: async (resp: { razorpay_payment_id: string; razorpay_signature: string }) => {
          setBusy(true);
          const r = await call<OrderView>(`/api/sc/orders/${order.id}/confirm`, {
            method: 'POST',
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify({ paymentId: resp.razorpay_payment_id, signature: resp.razorpay_signature }),
          });
          if (r.ok && r.data) applyOrder(r.data);
          setBusy(false);
        },
        modal: { ondismiss: () => setError('Payment window closed. Your pair is still reserved until the timer runs out.') },
      });
      rzp.open();
    }
  }

  async function cancel() {
    if (!order) return;
    setBusy(true);
    const r = await call<OrderView>(`/api/sc/orders/${order.id}/cancel`, { method: 'POST' });
    setBusy(false);
    if (r.ok && r.data) applyOrder(r.data);
    else if (r.problem?.code === 'already_paid') refreshOrder();
    else setError(r.problem?.detail ?? 'Could not cancel right now');
  }

  function startOver() {
    store.del(keys.order);
    setOrder(null);
    setError(null);
    join();
  }

  // ---- render ---------------------------------------------------------------------------------------------
  const available = (code: string) => stock?.available?.[code];
  const totalLeft = stock ? Object.values(stock.available).reduce((a, b) => a + b, 0) : null;
  const totalUnits = drop.skus.reduce((a, s) => a + s.totalUnits, 0);

  return (
    <div className="side" style={{ gap: 18 }}>
      <div className="sizes" role="group" aria-label="Size">
        {drop.skus.map((s) => {
          const left = available(s.code);
          const out = left === 0;
          return (
            <button key={s.code} className="size" aria-pressed={sku === s.code} disabled={out && phase === 'admitted'}
              onClick={() => setSku(s.code)} type="button">
              <span>{s.name.split('·').pop()?.trim() ?? s.name}</span>
              <small>{left === undefined ? '…' : out ? 'sold out' : left <= 10 ? `${left} left` : 'available'}</small>
            </button>
          );
        })}
      </div>
      {totalLeft !== null && totalUnits > 0 && phase !== 'closed' && (
        <div>
          <div className="meter" aria-hidden><span style={{ width: `${Math.max(0, (1 - totalLeft / totalUnits) * 100)}%` }} /></div>
          <p className="faint mono" style={{ fontSize: 12, margin: '6px 0 0' }}>
            {totalLeft} of {totalUnits} left{stock && stock.heldInCheckout > 0 ? ` · ${stock.heldInCheckout} in checkout right now` : ''}
          </p>
        </div>
      )}

      <div className="panel" aria-live="polite">
        {phase === 'loading' && <p className="muted">Loading…</p>}

        {phase === 'signin' && (
          <>
            <h3>{now < opensAt ? `Opens in ${clock(opensAt - now)}` : 'Live now'}</h3>
            <p className="muted">Sign in to join the line. Limited drops are {drop.perCustomerLimit} per customer, so we need to know who you are.</p>
            <Link className="btn block" href={`/login?next=/drops/${slug}`}>Sign in to join</Link>
          </>
        )}

        {phase === 'upcoming' && (
          <>
            <p className="kicker">The line opens in</p>
            <div className="countdown">{clock(queueOpensAt - now)}</div>
            <p className="muted">Join the line from {new Date(queueOpensAt).toLocaleTimeString()}. Everyone already in line when the drop opens goes in order of arrival.</p>
          </>
        )}

        {phase === 'join' && (
          <>
            <h3>{now < opensAt ? `Drop opens in ${clock(opensAt - now)}` : 'The drop is live'}</h3>
            <button className="btn block" onClick={join} disabled={busy}>{busy ? 'Joining…' : 'Join the line'}</button>
          </>
        )}

        {phase === 'waiting' && queue && (
          <>
            <p className="kicker">You&apos;re in line · ticket #{queue.ticket}</p>
            <div className="big-number">{queue.aheadOfYou.toLocaleString()}</div>
            <p className="muted" style={{ marginTop: -8 }}>
              {queue.aheadOfYou === 1 ? 'person' : 'people'} ahead of you · about {clock(queue.estimatedWaitSeconds * 1000)}
            </p>
            <p className="faint" style={{ fontSize: 13 }}>Keep this page open. Refreshing won&apos;t lose your place.</p>
          </>
        )}

        {phase === 'admitted' && (
          <form className="form" onSubmit={(e) => { e.preventDefault(); reserve(); }} noValidate>
            <p className="kicker">It&apos;s your turn</p>
            <div className="two">
              <label className="field">Quantity
                <select value={qty} onChange={(e) => setQty(Number(e.target.value))}>
                  {Array.from({ length: drop.perCustomerLimit }, (_, i) => i + 1).map((n) => <option key={n} value={n}>{n}</option>)}
                </select>
              </label>
              <Field label="Mobile" value={phone} onChange={setPhone} error={fieldErrors.phone} inputMode="tel" autoComplete="tel" />
            </div>
            <Field label="Full name" value={address.name} onChange={(v) => setAddress({ ...address, name: v })} error={fieldErrors['address.name']} autoComplete="name" />
            <Field label="Address" value={address.line1} onChange={(v) => setAddress({ ...address, line1: v })} error={fieldErrors['address.line1']} autoComplete="address-line1" />
            <Field label="Landmark (optional)" value={address.line2} onChange={(v) => setAddress({ ...address, line2: v })} autoComplete="address-line2" />
            <div className="two">
              <Field label="City" value={address.city} onChange={(v) => setAddress({ ...address, city: v })} error={fieldErrors['address.city']} autoComplete="address-level2" />
              <Field label="State" value={address.state} onChange={(v) => setAddress({ ...address, state: v })} error={fieldErrors['address.state']} autoComplete="address-level1" />
            </div>
            <Field label="PIN code" value={address.pincode} onChange={(v) => setAddress({ ...address, pincode: v })} error={fieldErrors['address.pincode']} inputMode="numeric" autoComplete="postal-code" />
            <button className="btn block" type="submit" disabled={busy}>
              {busy ? 'Reserving…' : `Reserve ${qty} · ${money((drop.skus.find((s) => s.code === sku)?.priceMinor ?? 0) * qty)}`}
            </button>
            <p className="faint" style={{ fontSize: 12, margin: 0 }}>Your pair is held for {Math.round(drop.reservationTtlSeconds / 60)} minutes while you pay.</p>
          </form>
        )}

        {phase === 'pay' && order && (
          <>
            <p className="kicker">Reserved for you</p>
            <div className="countdown" style={{ color: reservedLeft < 60_000 ? 'var(--warn)' : undefined }}>{clock(reservedLeft)}</div>
            <p className="muted" style={{ margin: 0 }}>{order.quantity} × {order.skuName} · {money(order.amountMinor, order.currency)}</p>
            {order.lastPaymentError && <p className="note bad">Last payment didn&apos;t go through: {order.lastPaymentError}. You can try again while your pair is held.</p>}
            {order.payment?.gateway === 'simulator' ? (
              <div className="row">
                <button className="btn" onClick={() => pay('captured')} disabled={busy}>{busy ? 'Confirming…' : `Pay ${money(order.amountMinor, order.currency)}`}</button>
                <button className="btn ghost" onClick={() => pay('failed')} disabled={busy}>Simulate a declined card</button>
              </div>
            ) : (
              <button className="btn block" onClick={() => pay()} disabled={busy}>
                {busy ? 'Confirming…' : order.payment ? `Pay ${money(order.amountMinor, order.currency)}` : 'Set up payment'}
              </button>
            )}
            <button className="linkish" onClick={cancel} disabled={busy}>Cancel and release my reservation</button>
          </>
        )}

        {phase === 'paid' && order && (
          <>
            <p className="kicker" style={{ color: 'var(--ok)' }}>Confirmed</p>
            <h3>You got it.</h3>
            <p className="muted">{order.quantity} × {order.skuName}. A confirmation email is on its way.</p>
            <Link className="btn ghost" href={`/orders/${order.id}`}>View order</Link>
          </>
        )}

        {phase === 'expired' && (
          <>
            <h3>Your reservation ended</h3>
            <p className="muted">The hold ran out before payment completed, so the pair went back on sale. If a payment did go through, it will be honoured if stock remains, or refunded automatically.</p>
            {totalLeft !== 0 && now < closesAt && <button className="btn block" onClick={startOver}>Try again</button>}
          </>
        )}

        {phase === 'cancelled' && (
          <>
            <h3>Reservation cancelled</h3>
            <p className="muted">The pair went back on sale.</p>
            {totalLeft !== 0 && now < closesAt && <button className="btn block" onClick={startOver}>Rejoin</button>}
          </>
        )}

        {phase === 'review' && order && (
          <>
            <h3>We&apos;re checking your payment</h3>
            <p className="muted">Your payment didn&apos;t match the order total, so a person will check it. Your pair is held meanwhile.</p>
            <Link className="btn ghost" href={`/orders/${order.id}`}>View order</Link>
          </>
        )}

        {phase === 'soldout' && (
          <>
            <h3>Sold out</h3>
            <p className="muted">Every pair has found a home. Reservations that aren&apos;t paid go back on sale, so the line may move again.</p>
            {now < closesAt && <button className="btn ghost block" onClick={join} disabled={busy}>Check again</button>}
          </>
        )}

        {phase === 'closed' && (
          <>
            <h3>This drop has ended</h3>
            <p className="muted">Thanks to everyone who came.</p>
          </>
        )}

        {error && <p className="note warn" role="status">{error}</p>}
      </div>
    </div>
  );
}

function Field(props: {
  label: string; value: string; onChange: (v: string) => void; error?: string;
  inputMode?: 'tel' | 'numeric' | 'text'; autoComplete?: string;
}) {
  return (
    <label className="field">
      {props.label}
      <input value={props.value} onChange={(e) => props.onChange(e.target.value)} inputMode={props.inputMode}
        autoComplete={props.autoComplete} aria-invalid={Boolean(props.error)} />
      {props.error && <span className="err">{props.error}</span>}
    </label>
  );
}

/** Mirrors the server's checks so most mistakes are caught before a request; the server stays the judge. */
function validate(phone: string, a: Address): Record<string, string> {
  const e: Record<string, string> = {};
  const digits = phone.replace(/[\s\-().]/g, '').replace(/^(\+91|0091|91(?=\d{10}$)|0(?=\d{10}$))/, '');
  if (!/^[6-9]\d{9}$/.test(digits)) e.phone = 'Enter a 10-digit Indian mobile number';
  if (!a.name.trim()) e['address.name'] = 'Required';
  if (!a.line1.trim()) e['address.line1'] = 'Required';
  if (!a.city.trim()) e['address.city'] = 'Required';
  if (!a.state.trim()) e['address.state'] = 'Required';
  if (!/^[1-9]\d{5}$/.test(a.pincode.trim())) e['address.pincode'] = 'Enter a 6-digit PIN code';
  return e;
}

function mapFields(fields: unknown): Record<string, string> {
  if (!fields || typeof fields !== 'object') return {};
  return Object.fromEntries(Object.entries(fields as Record<string, string>).map(([k, v]) => [k, String(v)]));
}

function useVisible(): boolean {
  const [visible, setVisible] = useState(true);
  useEffect(() => {
    const on = () => setVisible(document.visibilityState === 'visible');
    document.addEventListener('visibilitychange', on);
    return () => document.removeEventListener('visibilitychange', on);
  }, []);
  return visible;
}

function loadScript(src: string): Promise<void> {
  return new Promise((resolve) => {
    if (document.querySelector(`script[src="${src}"]`)) return resolve();
    const s = document.createElement('script');
    s.src = src;
    s.onload = () => resolve();
    s.onerror = () => resolve();
    document.body.appendChild(s);
  });
}
