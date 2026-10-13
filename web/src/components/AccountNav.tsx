'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { signedIn } from '@/lib/session';

export function AccountNav() {
  const [state, setState] = useState<'unknown' | 'in' | 'out'>('unknown');
  useEffect(() => setState(signedIn() ? 'in' : 'out'), []);
  if (state === 'unknown') return null;
  if (state === 'out') return <Link href="/login">Sign in</Link>;
  return (
    <>
      <Link href="/orders">My orders</Link>
      <button
        className="linkish"
        onClick={async () => {
          await fetch('/auth/logout', { method: 'POST' });
          window.location.href = '/';
        }}
      >
        Sign out
      </button>
    </>
  );
}
