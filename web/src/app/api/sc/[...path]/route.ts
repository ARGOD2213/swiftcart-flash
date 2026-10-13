import { cookies } from 'next/headers';
import { API_URL, TOKEN_COOKIE } from '@/lib/api';
import { SIGNED_IN_COOKIE } from '@/lib/session';

// Browser → this route → API. Adds the shopper's access token (kept in an httpOnly cookie, out of reach of
// page scripts) and passes through only the headers checkout needs.
const FORWARDED = ['content-type', 'idempotency-key', 'x-entry-token'];
const ALLOWED_PREFIXES = ['drops', 'orders', 'auth/me'];

async function forward(req: Request, ctx: { params: Promise<{ path: string[] }> }) {
  const { path } = await ctx.params;
  const joined = path.map(encodeURIComponent).join('/');
  if (!ALLOWED_PREFIXES.some((p) => joined === p || joined.startsWith(`${p}/`))) {
    return Response.json({ status: 404, code: 'not_found', detail: 'No such endpoint' }, { status: 404 });
  }
  const headers = new Headers();
  for (const h of FORWARDED) {
    const v = req.headers.get(h);
    if (v) headers.set(h, v);
  }
  const token = (await cookies()).get(TOKEN_COOKIE)?.value;
  if (token) headers.set('authorization', `Bearer ${token}`);

  let upstream: Response;
  try {
    upstream = await fetch(`${API_URL}/api/${joined}${new URL(req.url).search}`, {
      method: req.method,
      headers,
      body: req.method === 'GET' || req.method === 'HEAD' ? undefined : await req.text(),
      cache: 'no-store',
    });
  } catch {
    return Response.json({ status: 503, code: 'api_unreachable', detail: 'The store is not reachable right now' },
      { status: 503, headers: { 'retry-after': '2' } });
  }
  const out = new Headers({ 'content-type': upstream.headers.get('content-type') ?? 'application/json' });
  for (const h of ['retry-after', 'idempotent-replayed', 'cache-control']) {
    const v = upstream.headers.get(h);
    if (v) out.set(h, v);
  }
  const text = await upstream.text();
  const res = new Response(text, { status: upstream.status, headers: out });
  if (upstream.status === 401 && token && sessionEnded(text)) {
    // the session ended (not just an expired entry token): forget it so the page asks the shopper to sign in
    const jar = await cookies();
    jar.delete(TOKEN_COOKIE);
    jar.delete(SIGNED_IN_COOKIE);
  }
  return res;
}

function sessionEnded(body: string): boolean {
  try {
    return ['unauthorized', 'invalid_token', 'account_missing'].includes(JSON.parse(body).code);
  } catch {
    return true;
  }
}

export const GET = forward;
export const POST = forward;
