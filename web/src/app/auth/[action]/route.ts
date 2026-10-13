import { cookies } from 'next/headers';
import { API_URL, TOKEN_COOKIE } from '@/lib/api';
import { SIGNED_IN_COOKIE } from '@/lib/session';

// Sign in, register and sign out. The access token goes into an httpOnly, SameSite=Lax cookie that lives as
// long as the token itself.
export async function POST(req: Request, ctx: { params: Promise<{ action: string }> }) {
  const { action } = await ctx.params;
  const jar = await cookies();
  if (action === 'logout') {
    jar.delete(TOKEN_COOKIE);
    jar.delete(SIGNED_IN_COOKIE);
    return Response.json({ ok: true });
  }
  if (action !== 'login' && action !== 'register') {
    return Response.json({ code: 'not_found' }, { status: 404 });
  }
  let upstream: Response;
  try {
    upstream = await fetch(`${API_URL}/api/auth/${action}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: await req.text(),
      cache: 'no-store',
    });
  } catch {
    return Response.json({ code: 'api_unreachable', detail: 'The store is not reachable right now' }, { status: 503 });
  }
  const body = await upstream.json().catch(() => ({}));
  if (!upstream.ok) {
    return Response.json(body, { status: upstream.status, headers: retryAfter(upstream) });
  }
  const expires = new Date(body.expiresAt);
  const secure = process.env.NODE_ENV === 'production' && !process.env.ALLOW_INSECURE_COOKIES;
  jar.set(TOKEN_COOKIE, body.accessToken, { httpOnly: true, sameSite: 'lax', secure, path: '/', expires });
  // readable hint for the UI only; it grants nothing
  jar.set(SIGNED_IN_COOKIE, '1', { httpOnly: false, sameSite: 'lax', secure, path: '/', expires });
  return Response.json({ customer: body.customer, expiresAt: body.expiresAt });
}

function retryAfter(r: Response): Record<string, string> {
  const v = r.headers.get('retry-after');
  return v ? { 'retry-after': v } : {};
}
