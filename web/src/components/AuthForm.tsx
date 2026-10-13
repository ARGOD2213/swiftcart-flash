'use client';

import Link from 'next/link';
import { useState } from 'react';

/** Sign in / create account. On success, back to where the shopper came from (a drop page, usually). */
export function AuthForm({ mode }: { mode: 'login' | 'register' }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const res = await fetch(`/auth/${mode}`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(mode === 'login' ? { email, password } : { email, password, name }),
      });
      const body = await res.json().catch(() => ({}));
      if (res.ok) {
        const next = new URLSearchParams(window.location.search).get('next');
        // only same-site paths: never bounce a shopper to another site after login
        window.location.href = next && next.startsWith('/') && !next.startsWith('//') ? next : '/';
        return;
      }
      setError(message(body.code, body, res.headers.get('retry-after')));
    } catch {
      setError('The store is not reachable. Check your connection.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="panel form auth" onSubmit={submit}>
      <h3>{mode === 'login' ? 'Sign in' : 'Create your account'}</h3>
      {mode === 'register' && (
        <label className="field">Name<input value={name} onChange={(e) => setName(e.target.value)} required maxLength={80} autoComplete="name" /></label>
      )}
      <label className="field">Email<input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="email" /></label>
      <label className="field">Password
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required
          minLength={mode === 'register' ? 10 : 1} autoComplete={mode === 'login' ? 'current-password' : 'new-password'} />
      </label>
      {mode === 'register' && <span className="faint" style={{ fontSize: 12 }}>At least 10 characters.</span>}
      <button className="btn block" disabled={busy}>{busy ? 'One moment…' : mode === 'login' ? 'Sign in' : 'Create account'}</button>
      {error && <p className="note bad">{error}</p>}
      <p className="muted" style={{ fontSize: 14, margin: 0 }}>
        {mode === 'login' ? <>New here? <Link href="/register">Create an account</Link></>
          : <>Have an account? <Link href="/login">Sign in</Link></>}
      </p>
    </form>
  );
}

function message(code: string | undefined, body: { detail?: string; fields?: Record<string, string> }, retryAfter: string | null): string {
  switch (code) {
    case 'invalid_credentials': return 'Email or password is wrong.';
    case 'too_many_attempts': return `Too many attempts. Try again in ${Math.ceil(Number(retryAfter ?? 60) / 60)} minute(s).`;
    case 'email_taken': return 'An account with this email already exists. Sign in instead.';
    case 'password_too_weak': return 'Pick a stronger password.';
    case 'password_too_long': return 'That password is too long.';
    case 'invalid_request': return Object.entries(body.fields ?? {}).map(([k, v]) => `${k}: ${v}`).join(' · ') || 'Check the form.';
    default: return body.detail ?? 'Something went wrong.';
  }
}
