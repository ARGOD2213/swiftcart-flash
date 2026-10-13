'use client';

import { useEffect, useState } from 'react';
import type { DropView } from '@/lib/api';
import { DropGrid } from '@/components/DropGrid';

/**
 * Shown only when the cached home page has no catalogue in it: the copy built by `next build`, which ran without
 * an API. Until the first background refresh replaces that copy (and wherever a CDN still holds it), this loads
 * the drops in the browser, so nobody is told "no drops" during a drop just because a new release went out.
 */
export function DropsFallback() {
  const [drops, setDrops] = useState<DropView[] | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const load = async (attempt: number) => {
      try {
        const res = await fetch('/api/sc/drops', { cache: 'no-store' });
        if (!res.ok) throw new Error(String(res.status));
        const body = (await res.json()) as DropView[];
        if (!cancelled) setDrops(body);
      } catch {
        if (cancelled) return;
        setFailed(true);
        timer = setTimeout(() => load(attempt + 1), Math.min(30_000, 1_000 * 2 ** attempt));
      }
    };
    load(0);
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, []);

  if (drops) return <DropGrid drops={drops} />;
  return <p className="muted" aria-live="polite">{failed ? 'The store is busy. Retrying…' : 'Loading drops…'}</p>;
}
