// Server-side access to the SwiftCart API. The browser never talks to the API directly: it calls the
// /api/sc proxy route, which attaches the shopper's token from an httpOnly cookie.

export const API_URL = process.env.API_URL ?? 'http://localhost:8080';
export const TOKEN_COOKIE = 'sc_token';

export type Sku = { code: string; name: string; priceMinor: number; currency: string; totalUnits: number };

export type DropView = {
  slug: string;
  name: string;
  description: string;
  imageUrl: string | null;
  queueOpensAt: string;
  opensAt: string;
  closesAt: string;
  perCustomerLimit: number;
  reservationTtlSeconds: number;
  phase: 'UPCOMING' | 'QUEUE_OPEN' | 'LIVE' | 'ENDED';
  skus: Sku[];
};

export type StockView = {
  state: 'UPCOMING' | 'QUEUE_OPEN' | 'LIVE' | 'ENDED' | 'SOLD_OUT';
  available: Record<string, number>;
  heldInCheckout: number;
  asOf: string;
};

export type QueueStatus = {
  state: 'NOT_IN_QUEUE' | 'WAITING' | 'ADMITTED' | 'SOLD_OUT' | 'CLOSED';
  ticket: number;
  aheadOfYou: number;
  estimatedWaitSeconds: number;
  pollAfterMs: number;
  entryToken: string | null;
  entryTokenExpiresAt: string | null;
};

export type OrderView = {
  id: string;
  status: 'PENDING_PAYMENT' | 'PAID' | 'FULFILLED' | 'EXPIRED' | 'CANCELLED' | 'REVIEW';
  drop: string;
  sku: string;
  skuName: string;
  quantity: number;
  unitPriceMinor: number;
  amountMinor: number;
  currency: string;
  reservedUntil: string;
  createdAt: string;
  paidAt: string | null;
  lastPaymentError: string | null;
  payment: {
    gateway: 'razorpay' | 'stripe' | 'simulator';
    gatewayOrderId: string;
    keyId: string | null;
    checkoutUrl: string | null;
    amountMinor: number;
    currency: string;
  } | null;
  paymentError: string | null;
  payments: { id: string; status: string; amountMinor: number; refundReason: string | null }[];
};

export type Problem = { status: number; code: string; detail: string; [k: string]: unknown };

/**
 * GET for server components. Returns null for a 404. Any other failure throws at runtime, so an ISR page keeps
 * serving its last good version instead of caching an error; during `next build` (no API around) it returns null.
 */
export async function apiGet<T>(path: string, init: RequestInit & { next?: { revalidate?: number } } = {}): Promise<T | null> {
  const building = process.env.NEXT_PHASE === 'phase-production-build';
  try {
    const res = await fetch(`${API_URL}${path}`, init);
    if (res.status === 404) return null;
    if (!res.ok) throw new Error(`API ${res.status} for ${path}`);
    return (await res.json()) as T;
  } catch (e) {
    if (building) return null;
    throw e;
  }
}
