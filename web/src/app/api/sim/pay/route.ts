import { API_URL } from '@/lib/api';

// Local demos only: the simulated gateway's "pay" button. The API exposes /dev/* only in its dev profile, so
// against a real deployment this answers 404 and the storefront uses Razorpay or Stripe instead.
export async function POST(req: Request) {
  try {
    const upstream = await fetch(`${API_URL}/dev/simulator/pay`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: await req.text(),
      cache: 'no-store',
    });
    return new Response(await upstream.text(), { status: upstream.status, headers: { 'content-type': 'application/json' } });
  } catch {
    return Response.json({ code: 'api_unreachable' }, { status: 503 });
  }
}
