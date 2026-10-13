import { apiGet, type DropView } from '@/lib/api';
import { DropGrid } from '@/components/DropGrid';
import { DropsFallback } from '@/components/DropsFallback';

// Pre-rendered and cached (ISR): the CDN serves the catalogue, the API only sees a refresh every 30 s.
export const revalidate = 30;

export default async function Home() {
  // null only during `next build` (no API there): that copy loads the catalogue in the browser instead of
  // claiming there are no drops, and is replaced by the first background refresh after deploy
  const drops = await apiGet<DropView[]>('/api/drops', { next: { revalidate: 30 } });
  return (
    <div className="wrap">
      <section className="hero">
        <p className="kicker">Limited drops</p>
        <h1 className="display">One run.<br />No restocks.</h1>
        <p className="muted" style={{ maxWidth: 560 }}>
          When a drop opens you join a fair line, get a short window to check out, and your pair is held while you pay.
          Nothing is ever sold twice.
        </p>
      </section>
      {drops ? <DropGrid drops={drops} /> : <DropsFallback />}
    </div>
  );
}
