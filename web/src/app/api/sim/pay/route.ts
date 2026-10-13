import { API_URL } from '@/lib/api';

// Local demos only: the simulated gateway's "pay" button. Off unless SIMULATOR_ENABLED=1 (docker compose sets it),
// and the API only has /dev/* in its dev profile anyway, so a real deployment answers 404 twice over.
export async function POST(req: Request) {
  if (process.env.SIMULATOR_ENABLED !== '1') {
    return Response.json({ code: 'not_found', detail: 'No such endpoint' }, { status: 404 });
  }
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
